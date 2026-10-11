package io.aodbridge.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;

import io.aodbridge.common.Bridge;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 息屏指纹图标防烧屏（v0.2.0）。
 *
 * 背景：本模块的「息屏保活」会让 AOD 长时间不熄灭，而 ColorOS 在息屏状态下会一直
 * 点亮屏下指纹图标（呼吸式亮灭），固定位置长期发光即为烧屏风险。
 *
 * 两层处理（依据真机 SystemUI.apk 反汇编）：
 *  1. 原生开关：ColorOS 自带 Settings.Secure 键 `show_fingerprint_when_screen_off`
 *     （OnScreenFpSwitchHelper 注册了 ContentObserver，showInScreenOff != 1 时
 *     OnScreenFingerprintUiMech#notifyShowAodIcon 不再显示图标），hook 侧写
 *     0 = 不显示 / 1 = 显示，改动立即生效；指纹识别与解锁时的点亮由独立的
 *     OnScreenHighLightControl / OnScreenBrightnessUtils 驱动，不受影响。
 *  2. 视图兜底：即便某些 ROM 忽略该键，也把 OnScreenFingerprintIcon#setVisibility
 *     在「息屏 && 手指没按 && 过了手指抬起宽限期」时改写成 INVISIBLE；
 *     手指按下（onFpTouch/setTouchDownNow 收到 true）期间一律放行，不干扰解锁。
 *
 * 所有回调 PROTECTIVE + try/catch，任何异常都不影响 SystemUI。
 */
final class FpIconHider {
    private static final String ICON_CLASS =
            "com.oplus.systemui.biometrics.finger.udfps.OnScreenFingerprintIcon";
    private static final String MECH_CLASS =
            "com.oplus.systemui.biometrics.finger.udfps.OnScreenFingerprintUiMech";
    private static final String KEY_SHOW_IN_SCREEN_OFF = "show_fingerprint_when_screen_off";
    private static final String KEY_CONTINUE_FP = "continue_using_fp_while_screen_off";
    /** 手指抬起后继续放行点亮的时间，避免解锁动画/认证过程中被误伤。 */
    private static final long TOUCH_GRACE_MS = 5000;
    /** UserHandle.USER_CURRENT 是 @SystemApi，公共 SDK 里不可见；该常量恒为 -2。 */
    private static final int USER_CURRENT = -2;

    private static XposedModule module;
    private static volatile boolean installed;
    private static volatile boolean enabled = Bridge.HIDE_FP_DEFAULT;
    private static volatile boolean touching;
    private static volatile long touchAt;
    private static volatile WeakReference<View> iconRef = new WeakReference<>(null);
    private static volatile int blocked;
    private static volatile int enforced;
    private static volatile boolean settingWritePending;
    private static volatile boolean settingLogged;

    static void install(XposedModule m, ClassLoader classLoader) {
        module = m;
        if (installed) {
            return;
        }
        installed = true;
        int hooks = 0;
        hooks += hookIcon(classLoader);
        hooks += hookMech(classLoader);
        log(Log.INFO, "fp icon hider: hooks=" + hooks
                + " enabled=" + enabled + " (settings 键 "
                + KEY_SHOW_IN_SCREEN_OFF + " 0=隐藏/1=显示)");
        applySetting();
    }

    static void setEnabled(boolean value) {
        if (value != enabled) {
            enabled = value;
            log(Log.INFO, "fp icon hider: " + (value ? "开启" : "关闭") + "（AOD 指纹图标）");
        }
        applySetting();
    }

    static boolean isEnabled() {
        return enabled;
    }

    /** 手指按下/抬起（来自 OnScreenFingerprintUiMech#onFpTouch / setTouchDownNow）。 */
    static void noteTouch(boolean down) {
        touching = down;
        touchAt = SystemClock.elapsedRealtime();
    }

    static void onScreenOff() {
        if (!touching) {
            // 让宽限期立即过期：息屏一出现就把图标熄掉
            touchAt = SystemClock.elapsedRealtime() - TOUCH_GRACE_MS;
        }
        enforce("screen off");
    }

    static void onScreenOn() {
        touching = false;
        touchAt = SystemClock.elapsedRealtime();
    }

    /** 防烧屏循环里顺带巡检 + 写入开关的重试。 */
    static void tick() {
        if (!settingLogged) {
            applySetting();
        }
        enforce("tick");
    }

    private static boolean shouldHide() {
        return enabled && !touching && !AodInjector.deviceInteractive()
                && SystemClock.elapsedRealtime() - touchAt > TOUCH_GRACE_MS;
    }

    private static int hookIcon(ClassLoader classLoader) {
        int count = 0;
        try {
            Class<?> iconClass = classLoader.loadClass(ICON_CLASS);
            for (Method method : iconClass.getDeclaredMethods()) {
                if (!"setVisibility".equals(method.getName())) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length < 1 || params[0] != int.class) {
                    continue;
                }
                try {
                    module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object self = chain.getThisObject();
                                if (self instanceof View) {
                                    iconRef = new WeakReference<>((View) self);
                                }
                                try {
                                    if (shouldHide()) {
                                        List<Object> args = chain.getArgs();
                                        Object first = args.isEmpty() ? null : args.get(0);
                                        if (first instanceof Integer
                                                && (Integer) first == View.VISIBLE) {
                                            Object[] replaced = args.toArray();
                                            replaced[0] = View.INVISIBLE;
                                            blocked++;
                                            if (blocked <= 3 || blocked % 20 == 0) {
                                                log(Log.INFO, "fp icon: 拦截点亮 x" + blocked);
                                            }
                                            return chain.proceed(replaced);
                                        }
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, "fp icon: 拦截失败 " + t);
                                }
                                return chain.proceed();
                            });
                    count++;
                } catch (Throwable t) {
                    log(Log.WARN, "fp icon: hook " + method + " 失败 " + t);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, "fp icon: 找不到 " + ICON_CLASS + "（" + t + "），仅用系统开关");
        }
        return count;
    }

    private static int hookMech(ClassLoader classLoader) {
        int count = 0;
        try {
            Class<?> mechClass = classLoader.loadClass(MECH_CLASS);
            for (Method method : mechClass.getDeclaredMethods()) {
                String name = method.getName();
                if (!"onFpTouch".equals(name) && !"setTouchDownNow".equals(name)) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != boolean.class) {
                    continue;
                }
                try {
                    module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                try {
                                    List<Object> args = chain.getArgs();
                                    Object first = args.isEmpty() ? null : args.get(0);
                                    if (first instanceof Boolean) {
                                        noteTouch((Boolean) first);
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, "fp icon: touch 记录失败 " + t);
                                }
                                return result;
                            });
                    count++;
                } catch (Throwable t) {
                    log(Log.WARN, "fp icon: hook " + name + " 失败 " + t);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, "fp icon: 找不到 " + MECH_CLASS + "（" + t + "），仅用系统开关");
        }
        return count;
    }

    /** 兜底：若图标已被点亮（例如拦截安装前就已显示），主动调它自己的 setVisibility 熄掉。 */
    private static void enforce(String reason) {
        if (!shouldHide()) {
            return;
        }
        View icon = iconRef.get();
        if (icon == null || icon.getVisibility() != View.VISIBLE) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                View view = iconRef.get();
                if (view != null && shouldHide() && view.getVisibility() == View.VISIBLE) {
                    view.setVisibility(View.INVISIBLE);
                    enforced++;
                    log(Log.INFO, "fp icon: 强制熄灭 x" + enforced + "（" + reason + "）");
                }
            } catch (Throwable t) {
                log(Log.WARN, "fp icon: 强制熄灭失败 " + t);
            }
        });
    }

    /** 写 ColorOS 原生开关：开启 = 0（息屏不显示指纹图标），关闭 = 1（恢复显示）。 */
    private static void applySetting() {
        if (settingWritePending) {
            return;
        }
        settingWritePending = true;
        final boolean want = enabled;
        new Thread(() -> {
            try {
                Context context = AodInjector.appContext();
                if (context == null) {
                    return;
                }
                ContentResolver resolver = context.getContentResolver();
                int target = want ? 0 : 1;
                int current = getSecureForUser(resolver, KEY_SHOW_IN_SCREEN_OFF, -1);
                if (current != target) {
                    boolean ok = putSecureForUser(resolver, KEY_SHOW_IN_SCREEN_OFF, target);
                    log(Log.INFO, "fp icon: " + KEY_SHOW_IN_SCREEN_OFF + " "
                            + current + " -> " + target + " (ok=" + ok + ")");
                } else if (!settingLogged) {
                    log(Log.INFO, "fp icon: " + KEY_SHOW_IN_SCREEN_OFF + " 已是 " + target);
                }
                if (!settingLogged) {
                    int cont = getSecureForUser(resolver, KEY_CONTINUE_FP, -1);
                    log(Log.INFO, "fp icon: " + KEY_CONTINUE_FP + "=" + cont
                            + "（识别继续可用性，模块不改它）");
                    settingLogged = true;
                }
            } catch (Throwable t) {
                log(Log.WARN, "fp icon: 写系统开关失败 " + t);
            } finally {
                settingWritePending = false;
            }
        }, "aodbridge-fpswitch").start();
    }

    private static void log(int priority, String message) {
        AodInjector.log(priority, message);
        if (module == null) {
            return;
        }
        try {
            module.log(priority, AodInjector.TAG, message);
        } catch (Throwable ignored) {
        }
    }

    /**
     * getIntForUser / putIntForUser 是 @SystemApi，公共 SDK 里不可见，运行时用反射调真机
     * 实现（SystemUI 是平台签名进程，不受隐藏 API 限制）；反射失败退回公开 API。
     */
    private static int getSecureForUser(ContentResolver resolver, String key, int def) {
        try {
            Object value = Settings.Secure.class
                    .getMethod("getIntForUser", ContentResolver.class,
                            String.class, int.class, int.class)
                    .invoke(null, resolver, key, def, USER_CURRENT);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
        }
        return Settings.Secure.getInt(resolver, key, def);
    }

    private static boolean putSecureForUser(ContentResolver resolver, String key, int value) {
        try {
            Object ok = Settings.Secure.class
                    .getMethod("putIntForUser", ContentResolver.class,
                            String.class, int.class, int.class)
                    .invoke(null, resolver, key, value, USER_CURRENT);
            if (ok instanceof Boolean) {
                return (Boolean) ok;
            }
        } catch (Throwable ignored) {
        }
        return Settings.Secure.putInt(resolver, key, value);
    }

    private FpIconHider() {
    }
}
