package io.aodbridge.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import io.aodbridge.common.Bridge;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 把广播里的文本注入到 AOD 视图。
 *
 * 锚点（v0.1.8 依据真机 SystemUI.apk DEX 核实）：
 *   主：com.oplus.systemui.aod.aodclock.off.AodClockLayout#startShowAod / showClock
 *       —— thisObject 本身是时钟 View，注入到它的父容器。
 *   辅：com.oplus.systemui.aod.AodRecord 里名字含 show 的入口（onDreamCreate 等）。
 *   注：AodRecord#startShow 在真机 DEX 中不存在，只是历史猜测，留作空挂兜底。
 *
 * 所有回调包 try/catch + ExceptionMode.PROTECTIVE，绝不把 SystemUI 带崩。
 */
final class AodInjector {
    static final String TAG = "AODBridge";
    private static final String VIEW_TAG = "aodbridge_usage_view";
    private static final String TITLE_TAG = "aodbridge_title_view";
    private static final String BODY_TAG = "aodbridge_body_view";
    private static final String CREDITS_TAG = "aodbridge_credits_view";

    private static XposedModule module;
    private static ClassLoader sClassLoader;
    private static volatile boolean installed;
    private static volatile boolean receiverRegistered;
    private static volatile String lastText = "";
    private static volatile String lastState = Bridge.STATE_OK;
    private static volatile WeakReference<View> injectedView = new WeakReference<>(null);
    private static volatile WeakReference<TextView> injectedTitle = new WeakReference<>(null);
    private static volatile WeakReference<TextView> injectedBody = new WeakReference<>(null);
    private static volatile WeakReference<TextView> injectedCredits = new WeakReference<>(null);
    private static volatile boolean lastShift = Bridge.SHIFT_DEFAULT;
    private static volatile int lastDurationSec = Bridge.DURATION_DEFAULT;
    private static volatile boolean lastKeepAod = Bridge.KEEP_AOD_DEFAULT;
    private static volatile boolean lastHideFp = Bridge.HIDE_FP_DEFAULT;
    private static volatile int lastWindowFrom = Bridge.WINDOW_FROM_DEFAULT;
    private static volatile int lastWindowTo = Bridge.WINDOW_TO_DEFAULT;
    private static volatile String lastCreditsCn = "";
    private static volatile String lastCreditsIntl = "";
    private static volatile boolean windowActive;
    /** 还原后置位：所有注入/保活/改写入口变空操作，直到 SystemUI 重启（模块重新加载）。 */
    private static volatile boolean suspended;
    private static volatile boolean countdownActive;
    private static volatile boolean countdownScheduled;
    private static volatile boolean timedOut;
    private static volatile long screenOffAtElapsed;
    private static volatile int shiftIndex;
    private static volatile boolean shiftScheduled;
    private static volatile WeakReference<ViewGroup> lastRoot = new WeakReference<>(null);
    private static volatile WeakReference<View> lastClock = new WeakReference<>(null);
    private static volatile Object powerManager;

    /** 防烧屏：AOD 刷新率约 1 次/分钟，每轮换一个偏移位置，避免同一片像素长期点亮。 */
    private static final int SHIFT_INTERVAL_MS = 60000;
    private static final int[][] SHIFT_PATTERN_DP = {
            {0, 0}, {6, -4}, {-6, -4}, {6, 4}, {-6, 4}, {0, -6}, {0, 6}, {-6, 0}, {6, 0}
    };

    static void install(XposedModule m, ClassLoader classLoader) {
        module = m;
        if (installed) {
            log(Log.INFO, "already installed, skip");
            return;
        }
        installed = true;
        sClassLoader = classLoader;
        int hooks = 0;
        hooks += hookAodRecord(classLoader);
        hooks += hookClockLayout(classLoader);
        log(Log.INFO, "install done, hooks=" + hooks);
        if (hooks == 0) {
            Diag.dumpOnce(classLoader, "no anchors");
        }
        tryRegisterReceiver(0);
        startShiftLoop();
        KeepAlive.install(m, classLoader);
        FpIconHider.setEnabled(lastHideFp);
        FpIconHider.install(m, classLoader);
    }

    private static int hookAodRecord(ClassLoader classLoader) {
        int count = 0;
        try {
            Class<?> record = classLoader.loadClass("com.oplus.systemui.aod.AodRecord");
            for (Method method : record.getDeclaredMethods()) {
                // startShow 是已知锚点；名字里带 show 的其它入口也一并挂（回调很轻，重复注入会自动去重）
                if (!method.getName().toLowerCase(Locale.US).contains("show")) {
                    continue;
                }
                try {
                    module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                try {
                                    if (suspended) {
                                        return result;
                                    }
                                    onAodShown(chain.getThisObject());
                                } catch (Throwable t) {
                                    log(Log.WARN, "onAodShown failed: " + t);
                                }
                                return result;
                            });
                    count++;
                } catch (Throwable t) {
                    log(Log.WARN, "hook AodRecord.startShow failed: " + t);
                }
            }
            if (count == 0) {
                log(Log.WARN, "AodRecord.startShow not found");
            }
        } catch (Throwable t) {
            log(Log.WARN, "AodRecord class missing: " + t);
        }
        return count;
    }

    private static int hookClockLayout(ClassLoader classLoader) {
        String[] candidates = {
                "com.oplus.systemui.aod.aodclock.AodClockLayout",
                "com.oplus.systemui.aod.aodclock.off.AodClockLayout",
        };
        int count = 0;
        for (String name : candidates) {
            Class<?> layout;
            try {
                layout = classLoader.loadClass(name);
            } catch (Throwable ignored) {
                continue;
            }
            for (Method method : layout.getDeclaredMethods()) {
                String methodName = method.getName();
                if (!"showAodClock".equals(methodName) && !"startShowAod".equals(methodName)) {
                    continue;
                }
                try {
                    module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                try {
                                    Object self = chain.getThisObject();
                                    if (self instanceof View) {
                                        onClockLayoutShown((View) self);
                                    }
                                } catch (Throwable t) {
                                    log(Log.WARN, "onClockLayoutShown failed: " + t);
                                }
                                return result;
                            });
                    count++;
                } catch (Throwable t) {
                    log(Log.WARN, "hook " + methodName + " failed: " + t);
                }
            }
        }
        return count;
    }

    private static void onAodShown(Object record) {
        ViewGroup container = null;
        View clock = null;
        Object rootObject = findField(record, "mRootLayout", "rootLayout", "mAodRootLayout");
        Object clockObject = findField(record, "mAodClockLayout", "mClockLayout", "mClockView", "mAodClockView");
        if (rootObject instanceof ViewGroup) {
            container = (ViewGroup) rootObject;
        }
        if (clockObject instanceof View) {
            clock = (View) clockObject;
        }
        if (container == null || clock == null) {
            // ColorOS 16 可能改了字段名：扫一遍对象所有字段，取“子视图最多”的 ViewGroup 当容器
            View[] bestClock = new View[1];
            ViewGroup best = scanContainer(record, bestClock);
            if (container == null) {
                container = best;
            }
            if (clock == null) {
                clock = bestClock[0];
            }
        }
        if (container == null && clock != null) {
            container = resolveContainer(clock);
        }
        if (container != null) {
            log(Log.INFO, "aod container: " + container.getClass().getName()
                    + " children=" + container.getChildCount()
                    + " attached=" + container.isAttachedToWindow()
                    + " clock=" + (clock == null ? "none" : clock.getClass().getSimpleName()));
            injectInto(container, clock);
            return;
        }
        log(Log.WARN, "root layout not found on "
                + (record == null ? "null" : record.getClass().getName()));
        Diag.dumpOnce(sClassLoader, "root layout missing");
    }

    /** 兜底容器查找：遍历对象（含父类）全部字段，选子视图最多的 ViewGroup；顺带记住最像时钟的 View。 */
    private static ViewGroup scanContainer(Object target, View[] clockOut) {
        if (target == null) {
            return null;
        }
        ViewGroup best = null;
        int bestChildren = -1;
        for (Class<?> cls = target.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(target);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) value;
                    if (group.getChildCount() >= bestChildren) {
                        bestChildren = group.getChildCount();
                        best = group;
                    }
                } else if (value instanceof View && clockOut != null) {
                    String name = field.getName().toLowerCase(Locale.US);
                    if (clockOut[0] == null || name.contains("clock")) {
                        clockOut[0] = (View) value;
                    }
                }
            }
        }
        return best;
    }

    private static void onClockLayoutShown(View clockView) {
        ViewGroup container = resolveContainer(clockView);
        if (container != null) {
            injectInto(container, clockView);
        } else {
            log(Log.WARN, "clock view has no usable container yet");
        }
    }

    /**
     * 注入容器：从时钟视图往上走，尽量用「全屏容器」——
     * 这样电量/通知/媒体/指纹等兄弟层都能被 AutoLayout 测量到，落点也不会被上层遮住。
     * 最多上探 2 层，且要求父容器覆盖屏幕 90% 以上；否则用时钟的直接父容器（现状）。
     */
    private static ViewGroup resolveContainer(View clock) {
        if (!(clock.getParent() instanceof ViewGroup)) {
            return null;
        }
        ViewGroup best = (ViewGroup) clock.getParent();
        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        ViewGroup cursor = best;
        for (int i = 0; i < 2; i++) {
            if (!(cursor.getParent() instanceof ViewGroup)) {
                break;
            }
            ViewGroup parent = (ViewGroup) cursor.getParent();
            if (parent == cursor.getRootView()) {
                break;
            }
            if (parent.getWidth() >= dm.widthPixels * 0.9f
                    && parent.getHeight() >= dm.heightPixels * 0.9f) {
                best = parent;
                cursor = parent;
            } else {
                break;
            }
        }
        return best;
    }

    private static void injectInto(final ViewGroup root, final View clock) {
        injectInto(root, clock, 0);
    }

    private static void injectInto(final ViewGroup root, final View clock, final int attempt) {
        if (suspended) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                View tagged = root.findViewWithTag(VIEW_TAG);
                if (tagged instanceof LinearLayout) {
                    LinearLayout existing = (LinearLayout) tagged;
                    injectedView = new WeakReference<>(existing);
                    lastRoot = new WeakReference<>(root);
                    lastClock = new WeakReference<>(clock);
                    bindTextViews(existing);
                    applyText();
                    applyTransform(existing);
                    applyVisibility();
                    AutoLayout.attach(root, clock, existing);
                    return;
                }
                if (tagged != null) {
                    root.removeView(tagged);
                }
                LinearLayout box = new LinearLayout(root.getContext());
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                box.setTag(VIEW_TAG);
                box.setPadding(dp(10), dp(4), dp(10), dp(4));

                TextView title = new TextView(root.getContext());
                title.setTag(TITLE_TAG);
                title.setText(Bridge.TITLE);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setLetterSpacing(0.02f);
                title.setGravity(Gravity.CENTER);
                box.addView(title, wrapParams());

                TextView body = new TextView(root.getContext());
                body.setTag(BODY_TAG);
                body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7f);
                body.setLineSpacing(0f, 1.15f);
                body.setAlpha(0.85f);
                body.setGravity(Gravity.CENTER);
                box.addView(body, wrapParams());

                TextView credits = new TextView(root.getContext());
                credits.setTag(CREDITS_TAG);
                credits.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                credits.setTypeface(Typeface.DEFAULT_BOLD);
                credits.setGravity(Gravity.CENTER);
                box.addView(credits, wrapParams());

                box.setAlpha(0.001f);
                ViewGroup previous = lastRoot.get();
                root.addView(box, basicParams(root));
                if (previous != null && previous != root) {
                    View stale = previous.findViewWithTag(VIEW_TAG);
                    if (stale != null) {
                        previous.removeView(stale);
                    }
                }
                injectedView = new WeakReference<>(box);
                lastRoot = new WeakReference<>(root);
                lastClock = new WeakReference<>(clock);
                bindTextViews(box);
                applyText();
                applyTransform(box);
                applyVisibility();
                AutoLayout.attach(root, clock, box);
                boolean attached = box.isAttachedToWindow();
                log(Log.INFO, "view injected: " + body.getText()
                        + " root=" + root.getClass().getSimpleName()
                        + " children=" + root.getChildCount()
                        + " attached=" + attached);
                if (!attached && attempt < 2) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> injectInto(root, clock, attempt + 1), 700);
                }
            } catch (Throwable t) {
                log(Log.WARN, "inject failed: " + t);
            }
        }, attempt == 0 ? 120 : 0);
    }

    private static LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void bindTextViews(LinearLayout box) {
        View title = box.findViewWithTag(TITLE_TAG);
        View body = box.findViewWithTag(BODY_TAG);
        View credits = box.findViewWithTag(CREDITS_TAG);
        injectedTitle = new WeakReference<>(title instanceof TextView ? (TextView) title : null);
        injectedBody = new WeakReference<>(body instanceof TextView ? (TextView) body : null);
        injectedCredits = new WeakReference<>(
                credits instanceof TextView ? (TextView) credits : null);
    }

    /** 标题、正文、倒数行都在这里统一刷新。 */
    private static void applyText() {
        TextView title = injectedTitle.get();
        TextView body = injectedBody.get();
        TextView credits = injectedCredits.get();
        int color = colorFor(lastState);
        if (title != null) {
            title.setTextColor(color);
        }
        if (body != null) {
            body.setTextColor(color);
            body.setText((lastText.isEmpty() ? Bridge.TEXT_WAITING : lastText) + countdownLine());
        }
        if (credits != null) {
            credits.setTextColor(color);
            credits.setText(buildCreditsLine());
        }
    }

    /** 积分行：左侧国内、右侧国际，同一行内左右对齐。 */
    private static String buildCreditsLine() {
        String left = "国内 " + (lastCreditsCn.isEmpty() ? "—" : lastCreditsCn);
        String right = (lastCreditsIntl.isEmpty() ? "—" : lastCreditsIntl) + " 国际";
        int gap = Math.max(2, 24 - left.length() - right.length());
        StringBuilder line = new StringBuilder(left);
        for (int i = 0; i < gap; i++) {
            line.append(' ');
        }
        line.append(right);
        return line.toString();
    }

    /** 倒数行：限时显示时给剩余时间（跑完隐藏正文）；保持显示时不加提示行。 */
    private static String countdownLine() {
        if (lastDurationSec == Bridge.DURATION_KEEP) {
            return "";
        }
        long remain = remainingSec();
        if (remain < 0) {
            return "";
        }
        return String.format(Locale.US, "\n剩余 %d:%02d", remain / 60, remain % 60);
    }

    /** 返回剩余秒数；未在倒计时状态时返回 -1。 */
    private static long remainingSec() {
        if (!countdownActive || lastDurationSec <= 0) {
            return -1;
        }
        long elapsed = (SystemClock.elapsedRealtime() - screenOffAtElapsed) / 1000;
        return Math.max(0, lastDurationSec - elapsed);
    }

    private static void applyVisibility() {
        View view = injectedView.get();
        if (view != null) {
            view.setVisibility((suspended || timedOut || !windowActive) ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 还原原始息屏：撤掉注入视图、恢复指纹图标系统开关、停止保活拦截与防烧屏位移。
     * 之后所有入口空转，配合「重启系统界面」即可完全回到模块未生效的状态（卸载前用）。
     */
    static void restoreOriginal() {
        if (suspended) {
            return;
        }
        suspended = true;
        countdownActive = false;
        timedOut = false;
        lastText = "";
        lastCreditsCn = "";
        lastCreditsIntl = "";
        KeepAlive.clearPending();
        FpIconHider.setEnabled(false);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                View view = injectedView.get();
                if (view != null && view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
                injectedView = new WeakReference<>(null);
                injectedTitle = new WeakReference<>(null);
                injectedBody = new WeakReference<>(null);
                injectedCredits = new WeakReference<>(null);
                log(Log.INFO, "restore: 注入视图已撤除，指纹开关已恢复");
            } catch (Throwable t) {
                log(Log.WARN, "restore failed: " + t);
            }
        });
    }

    private static String durationLabel() {
        return lastDurationSec == Bridge.DURATION_KEEP ? "keep" : lastDurationSec + "s";
    }

    static boolean keepAodEnabled() {
        return !suspended && lastKeepAod;
    }

    /** 屏幕已熄且未解锁（倒计时进行中）——保活只在这个区间内生效。 */
    static boolean screenOffHolding() {
        return countdownActive;
    }

    static boolean countdownExpired() {
        return timedOut;
    }

    /** AOD 视图当前是否真的在屏上（已附着且可见）——保活只在“有东西可保”时动手。 */
    static boolean aodVisible() {
        View view = injectedView.get();
        return view != null && view.isAttachedToWindow() && view.getVisibility() == View.VISIBLE;
    }

    /**
     * 设备当前是否处于“可交互”状态（亮屏/解锁后为 true，AOD/熄屏期间为 false）。
     * 保活用它挡住亮屏/解锁瞬间的竞态；取不到 PowerManager 时返回 false（沿用广播状态兜底）。
     */
    static boolean deviceInteractive() {
        try {
            Object pm = powerManager;
            if (pm == null) {
                Context context = appContext();
                if (context == null) {
                    return false;
                }
                pm = context.getSystemService(Context.POWER_SERVICE);
                if (pm == null) {
                    return false;
                }
                powerManager = pm;
            }
            Object result = pm.getClass().getMethod("isInteractive").invoke(pm);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    private static final int COUNTDOWN_TICK_MS = 5000;

    private static final Runnable COUNTDOWN_TICK = new Runnable() {
        @Override
        public void run() {
            countdownScheduled = false;
            try {
                if (!countdownActive) {
                    return;
                }
                if (lastDurationSec > 0 && remainingSec() <= 0) {
                    timedOut = true;
                    fadeOutAndHide();
                    log(Log.INFO, "countdown end: 文案已按设定隐藏 " + lastDurationSec + "s");
                    // 保活期间被拦下的“熄灭”动作在这里补发，避免 AOD 永远关不掉
                    KeepAlive.replayPendingExit();
                    return;
                }
                refreshWindowState();
                applyText();
            } catch (Throwable t) {
                log(Log.WARN, "countdown tick failed: " + t);
            }
            scheduleCountdownTick();
        }
    };

    private static void scheduleCountdownTick() {
        if (countdownScheduled) {
            return;
        }
        countdownScheduled = true;
        new Handler(Looper.getMainLooper()).postDelayed(COUNTDOWN_TICK, COUNTDOWN_TICK_MS);
    }

    /** 锁屏：开始独立计时（与系统息屏显示时长无关）。 */
    private static void onScreenOff() {
        screenOffAtElapsed = SystemClock.elapsedRealtime();
        countdownActive = true;
        timedOut = false;
        FpIconHider.onScreenOff();
        log(Log.INFO, "screen off: countdown="
                + (lastDurationSec == Bridge.DURATION_KEEP ? "keep" : lastDurationSec + "s"));
        fadeShow();
        applyVisibility();
        applyText();
        if (lastDurationSec > 0) {
            scheduleCountdownTick();
        }
    }

    /** 亮屏/解锁：取消计时并恢复显示，下一次锁屏重新开始。 */
    private static void onScreenOn() {
        countdownActive = false;
        timedOut = false;
        KeepAlive.clearPending();
        FpIconHider.onScreenOn();
        log(Log.INFO, "screen on: countdown reset");
        View view = injectedView.get();
        if (view != null) {
            view.setAlpha(0.001f);
        }
        applyVisibility();
        applyText();
    }

    /** 锁屏淡入：文字从透明渐变到目标透明度，避免突然出现。 */
    private static void fadeShow() {
        View view = injectedView.get();
        if (view == null) {
            return;
        }
        view.setAlpha(0.001f);
        view.animate().cancel();
        view.animate().alpha(0.85f).setDuration(800).start();
    }

    /** 到点淡出后隐藏，避免瞬间消失。 */
    private static void fadeOutAndHide() {
        final View view = injectedView.get();
        if (view == null) {
            return;
        }
        view.animate().cancel();
        view.animate().alpha(0.001f).setDuration(600)
                .withEndAction(() -> view.setVisibility(View.GONE))
                .start();
    }

    /** 注入时的初始占位 LayoutParams（先隐身后由 AutoLayout 算真实落点）。 */
    private static ViewGroup.LayoutParams basicParams(ViewGroup root) {
        if (root instanceof RelativeLayout) {
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.addRule(RelativeLayout.CENTER_HORIZONTAL);
            params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            params.topMargin = dp(140);
            return params;
        }
        if (root instanceof FrameLayout) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(140);
            return params;
        }
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void updateVisibleView() {
        new Handler(Looper.getMainLooper()).post(() -> {
            View view = injectedView.get();
            if (view != null) {
                applyText();
                applyTransform(view);
                applyVisibility();
                AutoLayout.request();
            }
        });
    }

    /** 防烧屏：只在 AutoLayout 留出的安全空隙内做小范围平移（不再叠加人工偏移）。 */
    private static void applyTransform(View view) {
        int shiftX = 0;
        int shiftY = 0;
        if (lastShift) {
            int[] step = SHIFT_PATTERN_DP[shiftIndex % SHIFT_PATTERN_DP.length];
            shiftX = step[0];
            shiftY = step[1];
        }
        view.setTranslationX(dp(shiftX));
        view.setTranslationY(dp(shiftY));
    }

    private static void startShiftLoop() {
        if (shiftScheduled) {
            return;
        }
        shiftScheduled = true;
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    shiftIndex = (shiftIndex + 1) % SHIFT_PATTERN_DP.length;
                    View view = injectedView.get();
                    if (view != null && lastShift && view.getVisibility() == View.VISIBLE) {
                        applyTransform(view);
                    }
                    // 每分钟顺带重算一次避让：捕捉媒体/充电图标等迟到元素
                    AutoLayout.request(0);
                    FpIconHider.tick();
                    if (countdownActive && lastKeepAod && !aodVisible()) {
                        log(Log.INFO, "keep-alive diag: 息屏中但 AOD 视图已不在（可能被系统隐藏）");
                    }
                } catch (Throwable t) {
                    log(Log.WARN, "shift failed: " + t);
                }
                handler.postDelayed(this, SHIFT_INTERVAL_MS);
            }
        }, SHIFT_INTERVAL_MS);
    }

    /** 只更新显示配置（不带文本的广播）：刷新文案、倒数行与避让落点，不重建视图。 */
    private static void reInject() {
        ViewGroup root = lastRoot.get();
        if (root == null) {
            return;
        }
        updateVisibleView();
    }

    private static void applyConfigFrom(Intent intent) {
        if (intent.hasExtra(Bridge.EXTRA_SHIFT)) {
            lastShift = intent.getBooleanExtra(Bridge.EXTRA_SHIFT, lastShift);
        }
        if (intent.hasExtra(Bridge.EXTRA_DURATION_SEC)) {
            int duration = intent.getIntExtra(Bridge.EXTRA_DURATION_SEC, lastDurationSec);
            if (duration != lastDurationSec) {
                lastDurationSec = duration;
                timedOut = false;
                if (countdownActive) {
                    // 改档位从当前时刻重新计时
                    screenOffAtElapsed = SystemClock.elapsedRealtime();
                    if (duration > 0) {
                        scheduleCountdownTick();
                    }
                }
            }
        }
        if (intent.hasExtra(Bridge.EXTRA_KEEP_AOD)) {
            lastKeepAod = intent.getBooleanExtra(Bridge.EXTRA_KEEP_AOD, lastKeepAod);
        }
        if (intent.hasExtra(Bridge.EXTRA_HIDE_FP)) {
            boolean hideFp = intent.getBooleanExtra(Bridge.EXTRA_HIDE_FP, lastHideFp);
            if (hideFp != lastHideFp) {
                lastHideFp = hideFp;
                FpIconHider.setEnabled(hideFp);
            }
        }
        if (intent.hasExtra(Bridge.EXTRA_WINDOW_FROM)) {
            lastWindowFrom = intent.getIntExtra(Bridge.EXTRA_WINDOW_FROM, lastWindowFrom);
        }
        if (intent.hasExtra(Bridge.EXTRA_WINDOW_TO)) {
            lastWindowTo = intent.getIntExtra(Bridge.EXTRA_WINDOW_TO, lastWindowTo);
        }
        refreshWindowState();
    }

    /** 当前本地时间（分钟）是否落在显示时段内；终点 <= 起点视为全天。 */
    private static void refreshWindowState() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int nowMinutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + calendar.get(java.util.Calendar.MINUTE);
        if (lastWindowTo <= lastWindowFrom) {
            windowActive = true;
        } else {
            windowActive = nowMinutes >= lastWindowFrom && nowMinutes < lastWindowTo;
        }
        View view = injectedView.get();
        if (view != null) {
            applyVisibility();
        }
    }

    /** AOD 上的字色跟着数据状态走：正常浅青、缓存兜底浅琥珀、离线暖橙。 */
    private static int colorFor(String state) {
        if (Bridge.STATE_OFFLINE.equals(state)) {
            return 0xFFFFA48A;
        }
        if (Bridge.STATE_STALE.equals(state)) {
            return 0xFFFFD59E;
        }
        return 0xFF9FE1CB;
    }

    private static void tryRegisterReceiver(int attempt) {
        if (receiverRegistered) {
            return;
        }
        Context context = appContext();
        if (context == null) {
            if (attempt < 30) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> tryRegisterReceiver(attempt + 1), 1000);
            } else {
                log(Log.WARN, "app context unavailable, receiver not registered");
            }
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                IntentFilter filter = new IntentFilter(Bridge.ACTION);
                filter.addAction(Bridge.ACTION_RESET);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ctx, Intent intent) {
                        String action = intent.getAction();
                        if (Bridge.ACTION_RESET.equals(action)) {
                            log(Log.INFO, "reset broadcast received: 还原原始息屏");
                            restoreOriginal();
                            return;
                        }
                        if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                            refreshWindowState();
                            onScreenOff();
                            return;
                        }
                        if (Intent.ACTION_SCREEN_ON.equals(action)
                                || Intent.ACTION_USER_PRESENT.equals(action)) {
                            onScreenOn();
                            return;
                        }
                        applyConfigFrom(intent);
                        String text = intent.getStringExtra(Bridge.EXTRA_TEXT);
                        if (text != null && !text.isEmpty()) {
                            lastText = text;
                            String state = intent.getStringExtra(Bridge.EXTRA_STATE);
                            lastState = state == null ? Bridge.STATE_OK : state;
                            lastCreditsCn = intent.getStringExtra(Bridge.EXTRA_CREDITS_CN);
                            lastCreditsIntl = intent.getStringExtra(Bridge.EXTRA_CREDITS_INTL);
                            if (lastCreditsCn == null) {
                                lastCreditsCn = "";
                            }
                            if (lastCreditsIntl == null) {
                                lastCreditsIntl = "";
                            }
                            log(Log.INFO, "broadcast received [" + lastState + "]: " + text
                                    + " shift=" + lastShift
                                    + " duration=" + durationLabel() + " keepAod=" + lastKeepAod
                                    + " hideFp=" + lastHideFp);
                            updateVisibleView();
                            return;
                        }
                        log(Log.INFO, "config received: shift=" + lastShift
                                + " duration=" + durationLabel()
                                + " keepAod=" + lastKeepAod
                                + " hideFp=" + lastHideFp);
                        reInject();
                    }
                };
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
                receiverRegistered = true;
                log(Log.INFO, "receiver registered");
            } catch (Throwable t) {
                log(Log.WARN, "receiver register failed: " + t);
            }
        });
    }

    static Context appContext() {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Object app = Class.forName("android.app.AppGlobals")
                    .getMethod("getInitialApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findField(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (Class<?> cls = target.getClass(); cls != null; cls = cls.getSuperclass()) {
            for (String name : names) {
                try {
                    Field field = cls.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException ignored) {
                    // 继续试下一个候选名
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    private static int dp(int value) {
        return (int) (value * Resources.getSystem().getDisplayMetrics().density);
    }

    static void log(int priority, String message) {
        try {
            if (module != null) {
                module.log(priority, TAG, message);
            }
        } catch (Throwable ignored) {
        }
        try {
            Log.println(priority, TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private AodInjector() {
    }
}
