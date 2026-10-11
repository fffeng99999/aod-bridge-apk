package io.aodbridge.hook;

import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 息屏保活（v0.1.8）：我们的文字寄生在系统 AOD 视图里，系统 AOD 到点一熄灭，文字就跟着没了。
 *
 * v0.1.8 依据真机 SystemUI（ColorOS 16 / PLR110，SystemUI.apk 内 8 个 dex 反汇编）钉死了
 * 「到点熄灭」的真实链路：
 *
 *   1. AodClockLayout#performLayoutUpdate 在满足条件时调用 AodUpdateManager#setHideAlarm()，
 *      后者通过 AlarmManager 在 5 秒后触发 mHideIntent（日志 "setHideAlarm: delayedTime-->5000"）；
 *   2. 到点后 AodUpdateManager$$ExternalSyntheticLambda4#onReceive（hide 分支）按条件调用
 *      AODDisplayUtil#updateDisplayStateWithoutAodClientPolicy("mHideAodReceiver")；
 *   3. 该方法就是真正的「强制熄屏」——内部把 Display 打到 STATE_OFF（Final-state=OFF）；
 *   4. AodClockLayout#hideClock / BaseRootLayout#hideAllViewOrNot(true) / AodClockLayout#setVisibility(8)
 *      负责同一时刻把 AOD 视图整体清空。
 *
 * 保活策略（仅在「保活开启 + 屏幕已熄 + 未亮屏 + 自己的倒计时没到 + 我们的视图真的在屏上」时）：
 *   - 精确拦下上述锚点（updateDisplayStateWithoutAodClientPolicy 的全部调用都只为强制熄屏，
 *     整条拦下），并记下现场；
 *   - 倒计时到点 → 按记录顺序把拦下的调用补发（replay），系统照常收尾；
 *   - 亮屏 / 解锁 → 丢弃挂起的调用，一切恢复系统行为。
 *
 * 另保留 v0.1.7 的泛化兜底（aod/doze 类里“结束类动词”方法，作为未来机型变更的保险）
 * 与 PowerManager#goToSleep 拦截。全部 try/catch + PROTECTIVE，异常不会影响 SystemUI。
 */
final class KeepAlive {
    private static volatile boolean installed;
    private static volatile boolean replaying;
    private static final Object PENDING_LOCK = new Object();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final int PENDING_MAX = 16;
    private static final Set<String> HOOKED = new HashSet<>();
    private static final Map<String, Long> LAST_LOG = new HashMap<>();
    private static final int MAX_HOOKS = 400;

    private static final String[] EXIT_WORDS = {
            "stop", "stopped", "hide", "dismiss", "exit", "finish", "close", "end", "off",
            "pause", "cancel", "abort",
    };

    private enum Gate {
        ALL,
        TRUE_ONLY,
        GONE_ONLY,
    }

    private static final class Pending {
        final Method method;
        final Object receiver;
        final Object[] args;

        Pending(Method method, Object receiver, Object[] args) {
            this.method = method;
            this.receiver = receiver;
            this.args = args;
        }
    }

    static void install(XposedModule module, ClassLoader classLoader) {
        if (installed) {
            AodInjector.log(Log.INFO, "keep-alive already installed");
            return;
        }
        installed = true;
        int precise = installPrecise(module, classLoader);
        int generic = installGeneric(module, classLoader);
        int power = hookPowerSleep(module);
        AodInjector.log(Log.INFO, "keep-alive hooks: precise=" + precise
                + " generic=" + generic + " power=" + power);
    }

    /** v0.1.8：真机 DEX 里逐条核实过的锚点（类名/方法名/签名均来自 SystemUI.apk）。 */
    private static int installPrecise(XposedModule module, ClassLoader classLoader) {
        int count = 0;
        count += hookDeclared(module, classLoader,
                "com.oplus.systemui.aod.display.AODDisplayUtil",
                "updateDisplayStateWithoutAodClientPolicy", Gate.ALL);
        count += hookDeclared(module, classLoader,
                "com.oplus.systemui.aod.aodclock.off.AodUpdateManager",
                "setHideAlarm", Gate.ALL);
        count += hookDeclared(module, classLoader,
                "com.oplus.systemui.aod.aodclock.off.AodUpdateManager$2",
                "hideAodByDarkLight", Gate.ALL);
        count += hookDeclared(module, classLoader,
                "com.oplus.systemui.aod.aodclock.off.AodClockLayout",
                "hideClock", Gate.ALL);
        count += hookDeclared(module, classLoader,
                "com.oplus.systemui.aod.aodclock.off.AodClockLayout",
                "setVisibility", Gate.GONE_ONLY);
        count += hookDeclared(module, classLoader,
                "com.oplus.systemui.aod.aodclock.off.BaseRootLayout",
                "hideAllViewOrNot", Gate.TRUE_ONLY);
        return count;
    }

    private static int hookDeclared(XposedModule module, ClassLoader classLoader,
                                    String className, String methodName, Gate gate) {
        int count = 0;
        try {
            Class<?> cls = Class.forName(className, false, classLoader);
            for (Method method : cls.getDeclaredMethods()) {
                if (!methodName.equals(method.getName())
                        || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (hookOne(module, method, gate)) {
                    count++;
                }
            }
        } catch (Throwable t) {
            AodInjector.log(Log.WARN, "keep-alive: anchor miss "
                    + className + "#" + methodName + ": " + t);
        }
        return count;
    }

    private static boolean hookOne(XposedModule module, Method method, Gate gate) {
        String key = method.getDeclaringClass().getName() + "#" + method.getName()
                + "/" + method.getParameterTypes().length;
        synchronized (HOOKED) {
            if (!HOOKED.add(key)) {
                return false;
            }
        }
        try {
            module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (gatePassed(gate, chain) && shouldHold()) {
                            remember(chain);
                            logHold(method);
                            return defaultResult(method);
                        }
                        return chain.proceed();
                    });
            return true;
        } catch (Throwable t) {
            synchronized (HOOKED) {
                HOOKED.remove(key);
            }
            return false;
        }
    }

    private static int installGeneric(XposedModule module, ClassLoader classLoader) {
        int hooks = 0;
        int classes = 0;
        try {
            TreeSet<String> names = classNames(classLoader);
            for (String name : names) {
                if (hooks >= MAX_HOOKS) {
                    AodInjector.log(Log.WARN, "keep-alive hook cap reached: " + MAX_HOOKS);
                    break;
                }
                if (!isCandidate(name)) {
                    continue;
                }
                Class<?> cls;
                try {
                    cls = Class.forName(name, false, classLoader);
                } catch (Throwable ignored) {
                    continue;
                }
                if (cls.isInterface() || Modifier.isAbstract(cls.getModifiers())) {
                    continue;
                }
                int before = hooks;
                for (Method method : cls.getDeclaredMethods()) {
                    if (!isExitLike(method)) {
                        continue;
                    }
                    if (hookOne(module, method, Gate.ALL)) {
                        hooks++;
                    }
                }
                if (hooks > before) {
                    classes++;
                }
            }
        } catch (Throwable t) {
            AodInjector.log(Log.WARN, "keep-alive generic pass failed: " + t);
        }
        return hooks;
    }

    /**
     * 保险：万一系统 AOD 的关闭是靠 SystemUI 直接调 PowerManager.goToSleep() 让屏幕彻底休眠，
     * 也一并拦下（同样只在保活条件成立时生效）。
     */
    private static int hookPowerSleep(XposedModule module) {
        int count = 0;
        try {
            Class<?> cls = Class.forName("android.os.PowerManager");
            for (Method method : cls.getDeclaredMethods()) {
                if (!"goToSleep".equals(method.getName())) {
                    continue;
                }
                if (hookOne(module, method, Gate.ALL)) {
                    count++;
                }
            }
        } catch (Throwable t) {
            AodInjector.log(Log.WARN, "keep-alive: PowerManager hook failed: " + t);
        }
        return count;
    }

    /** 当前是否应该拦下“熄灭/隐藏”动作。 */
    private static boolean shouldHold() {
        if (replaying || !AodInjector.keepAodEnabled()) {
            return false;
        }
        if (!AodInjector.screenOffHolding() || AodInjector.countdownExpired()) {
            return false;
        }
        // 亮屏/解锁瞬间的竞态：设备已交互就把控制权交还系统
        if (AodInjector.deviceInteractive()) {
            return false;
        }
        // 只有 AOD 真的在屏上（我们视图已附着且可见）才拦，口袋里/已熄屏时不阻挠系统
        return AodInjector.aodVisible();
    }

    private static boolean gatePassed(Gate gate, XposedInterface.Chain chain) {
        if (gate == Gate.ALL) {
            return true;
        }
        List<Object> args;
        try {
            args = chain.getArgs();
        } catch (Throwable t) {
            return false;
        }
        Object first = (args == null || args.isEmpty()) ? null : args.get(0);
        if (gate == Gate.TRUE_ONLY) {
            return Boolean.TRUE.equals(first);
        }
        if (gate == Gate.GONE_ONLY) {
            return first instanceof Integer && ((Integer) first) == 8;
        }
        return true;
    }

    private static void remember(XposedInterface.Chain chain) {
        try {
            Executable executable = chain.getExecutable();
            if (!(executable instanceof Method)) {
                return;
            }
            Method method = (Method) executable;
            List<Object> args = chain.getArgs();
            Object[] copy = args == null ? new Object[0] : args.toArray(new Object[0]);
            String label = label(method, copy);
            synchronized (PENDING_LOCK) {
                for (Pending pending : PENDING) {
                    if (label(pending.method, pending.args).equals(label)) {
                        return;
                    }
                }
                if (PENDING.size() >= PENDING_MAX) {
                    PENDING.remove(0);
                }
                PENDING.add(new Pending(method, chain.getThisObject(), copy));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 倒计时到点时调用：把先前拦下的“熄灭”动作按顺序补发。 */
    static void replayPendingExit() {
        List<Pending> snapshot;
        synchronized (PENDING_LOCK) {
            if (PENDING.isEmpty()) {
                return;
            }
            snapshot = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        replaying = true;
        try {
            for (Pending pending : snapshot) {
                try {
                    pending.method.setAccessible(true);
                    pending.method.invoke(pending.receiver, pending.args);
                    AodInjector.log(Log.INFO, "keep-alive: replayed " + label(pending.method, pending.args));
                } catch (Throwable t) {
                    AodInjector.log(Log.WARN, "keep-alive: replay failed "
                            + pending.method.getName() + ": " + t);
                }
            }
        } finally {
            replaying = false;
        }
    }

    /** 亮屏/解锁时调用：丢弃挂起的熄灭动作，交回系统处理。 */
    static void clearPending() {
        synchronized (PENDING_LOCK) {
            PENDING.clear();
        }
    }

    private static Object defaultResult(Method method) {
        if (method.getReturnType() == boolean.class) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static void logHold(Method method) {
        String key = describe(method);
        long now = SystemClock.elapsedRealtime();
        synchronized (LAST_LOG) {
            Long last = LAST_LOG.get(key);
            if (last != null && now - last < 15000) {
                return;
            }
            LAST_LOG.put(key, now);
        }
        AodInjector.log(Log.INFO, "keep-alive: blocked " + key);
    }

    private static String label(Method method, Object[] args) {
        return describe(method) + argsSummary(args);
    }

    private static String argsSummary(Object[] args) {
        if (args == null || args.length == 0) {
            return "()";
        }
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(args[i]);
        }
        return builder.append(")").toString();
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    /** 只挂 AOD / Doze 相关类，避免扩大影响面。 */
    private static boolean isCandidate(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.contains("test")) {
            return false;
        }
        if (lower.contains("doze")) {
            return true;
        }
        return lower.startsWith("com.oplus") && lower.contains("aod");
    }

    /** 方法名按驼峰拆词，“某个词”等于结束类动词才算，避免 send/offset 这类误伤。 */
    private static boolean isExitLike(Method method) {
        int modifiers = method.getModifiers();
        if (Modifier.isNative(modifiers) || Modifier.isStatic(modifiers)) {
            return false;
        }
        Class<?> type = method.getReturnType();
        if (type != void.class && type != boolean.class) {
            return false;
        }
        String name = method.getName();
        if (name.startsWith("access$") || name.startsWith("lambda$") || name.startsWith("<")) {
            return false;
        }
        for (String word : name.split("(?<=\\p{Ll})(?=\\p{Lu})|_+|\\$|\\d+")) {
            String token = word.toLowerCase(Locale.US);
            for (String exit : EXIT_WORDS) {
                if (token.equals(exit)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 枚举 SystemUI 自身 dex 里的类名（同 Diag 的做法，只读）。 */
    private static TreeSet<String> classNames(ClassLoader classLoader) throws Exception {
        TreeSet<String> names = new TreeSet<>();
        Object pathList = get(classLoader, "pathList");
        Object[] elements = pathList == null ? null : (Object[]) get(pathList, "dexElements");
        if (elements == null) {
            return names;
        }
        for (Object element : elements) {
            if (element == null) {
                continue;
            }
            Object dexFile = get(element, "dexFile");
            if (dexFile == null) {
                continue;
            }
            Enumeration<?> entries = (Enumeration<?>)
                    dexFile.getClass().getMethod("entries").invoke(dexFile);
            while (entries != null && entries.hasMoreElements()) {
                Object raw = entries.nextElement();
                if (raw instanceof String && isCandidate((String) raw)) {
                    names.add((String) raw);
                }
            }
        }
        return names;
    }

    private static Object get(Object target, String fieldName) {
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            try {
                Field field = cls.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private KeepAlive() {
    }
}
