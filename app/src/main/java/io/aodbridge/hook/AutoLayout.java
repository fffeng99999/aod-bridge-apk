package io.aodbridge.hook;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动避让布局（v0.1.9）：把注入的用量文本框放进「原生息屏内容之间的空隙」里。
 *
 * 设计依据（真机 SystemUI.apk 实证）：ColorOS 各时钟样式（数字/表盘/全局主题/工坊）
 * 没有统一的「方形边框」，圆盘样式是圆的、纯文字样式没有边——所以不假设边框，
 * 而是在运行时测量根容器里每个可见子视图的真实 bounds，直接避让内容本身：
 *   1. 收集根容器内全部可见、有实际大小、且非全屏背景/蒙版的子视图矩形，
 *      换算到根坐标系后合并成「内容块」；
 *   2. 屏下指纹感应区恒定为「底部居中的一条带」预留（硬件位置固定，永远避开）；
 *   3. 在去除内容块后的垂直空隙里挑落点：优先「时钟块下方放得下的最高空隙」，
 *      其次「全局放得下的最高空隙」，都放不下才退化为最高空隙并打警告；
 *   4. 文本框始终水平居中，垂直在空隙内居中，四周留 MARGIN_DP 安全距。
 *
 * 重算时机：注入时（多次延时）、根容器 layout 变化（防抖）、文本/配置广播、
 * 防烧屏 60s 循环（捕捉媒体/充电图标等迟到元素）。矩形等价时不做任何改动。
 */
final class AutoLayout {
    /** 与原生内容的最小安全距。 */
    private static final int MARGIN_DP = 14;
    /** 屏幕上下边缘安全区。 */
    private static final int EDGE_DP = 26;
    /** 指纹预留带：屏幕底部 15%，水平居中 68% 宽。 */
    private static final int FINGERPRINT_TOP_PCT = 85;
    private static final int FINGERPRINT_HALF_WIDTH_PCT = 34;
    /** layout 抖动防抖窗口。 */
    private static final long DEBOUNCE_MS = 300;
    /** 根容器/文本框都还没布局时的重试次数与间隔。 */
    private static final int MAX_RETRY = 10;
    private static final long RETRY_DELAY_MS = 360;
    /** 注入后的固定重算时刻（捕捉入场动画结束、电量/通知行迟到）。 */
    private static final long[] INITIAL_DELAYS = {250, 1000, 2600, 5200};

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<ViewGroup> sRoot = new WeakReference<>(null);
    private static volatile WeakReference<View> sClock = new WeakReference<>(null);
    private static volatile WeakReference<View> sBox = new WeakReference<>(null);
    private static volatile ViewTreeObserver sObserver;
    private static volatile boolean sScheduled;
    private static volatile boolean sPlaced;
    private static volatile int sRetry;
    private static volatile long sSignature;

    private static final ViewTreeObserver.OnGlobalLayoutListener LAYOUT_LISTENER =
            AutoLayout::onGlobalLayout;

    private static final Runnable COMPUTE = AutoLayout::compute;

    private AutoLayout() {
    }

    /** 注入完成后调用：记住现场、挂 layout 监听、排定若干次延时重算。 */
    static void attach(ViewGroup root, View clock, View box) {
        sRoot = new WeakReference<>(root);
        sClock = new WeakReference<>(clock);
        sBox = new WeakReference<>(box);
        sPlaced = false;
        sRetry = 0;
        sSignature = 0;
        watch(root);
        for (long delay : INITIAL_DELAYS) {
            request(delay);
        }
    }

    /** 请求一次重算（默认防抖窗口）。 */
    static void request() {
        request(DEBOUNCE_MS);
    }

    static void request(long delayMs) {
        if (sScheduled) {
            return;
        }
        sScheduled = true;
        HANDLER.postDelayed(COMPUTE, Math.max(0, delayMs));
    }

    private static void watch(ViewGroup root) {
        try {
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (sObserver != null && sObserver.isAlive() && sObserver != observer) {
                try {
                    sObserver.removeOnGlobalLayoutListener(LAYOUT_LISTENER);
                } catch (Throwable ignored) {
                }
            }
            if (observer != null && observer.isAlive() && observer != sObserver) {
                observer.addOnGlobalLayoutListener(LAYOUT_LISTENER);
                sObserver = observer;
            }
        } catch (Throwable t) {
            AodInjector.log(Log.WARN, "auto layout watch failed: " + t);
        }
    }

    private static void onGlobalLayout() {
        View box = sBox.get();
        if (box == null || !box.isAttachedToWindow()) {
            return;
        }
        request();
    }

    private static void retry(String why) {
        sRetry++;
        if (sRetry <= MAX_RETRY) {
            request(RETRY_DELAY_MS);
            return;
        }
        if (!sPlaced) {
            View box = sBox.get();
            if (box != null && box.getAlpha() < 1f) {
                box.setAlpha(1f);
            }
            AodInjector.log(Log.WARN, "auto layout 放弃（" + why + "），保留默认落点");
        }
    }

    private static void compute() {
        sScheduled = false;
        try {
            ViewGroup root = sRoot.get();
            View box = sBox.get();
            View clock = sClock.get();
            if (root == null || box == null || !box.isAttachedToWindow()) {
                return;
            }
            int rootW = root.getWidth();
            int rootH = root.getHeight();
            if (rootW <= 0 || rootH <= 0) {
                retry("根容器未布局");
                return;
            }
            int boxW = box.getWidth();
            int boxH = box.getHeight();
            if (boxW <= 0 || boxH <= 0) {
                box.measure(View.MeasureSpec.makeMeasureSpec(rootW, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(rootH, View.MeasureSpec.AT_MOST));
                boxW = box.getMeasuredWidth();
                boxH = box.getMeasuredHeight();
            }
            if (boxW <= 0 || boxH <= 0) {
                retry("文本框未测量");
                return;
            }

            int margin = dp(MARGIN_DP);
            List<Rect> obstacles = new ArrayList<>();
            collect(root, root, box, rootW, rootH, obstacles);
            obstacles = merge(obstacles, dp(8));
            Rect fingerprint = new Rect(rootW * (50 - FINGERPRINT_HALF_WIDTH_PCT) / 100,
                    rootH * FINGERPRINT_TOP_PCT / 100,
                    rootW * (50 + FINGERPRINT_HALF_WIDTH_PCT) / 100,
                    rootH);
            Rect clockRect = new Rect();
            boolean hasClock = clock != null && rectIn(clock, root, clockRect);

            int centerX = rootW / 2;
            int bandLeft = centerX - boxW / 2 - margin;
            int bandRight = centerX + boxW / 2 + margin;
            int edge = Math.min(dp(EDGE_DP), rootH / 4);
            int usableTop = edge;
            int usableBottom = rootH - edge;

            List<int[]> spans = new ArrayList<>();
            for (Rect rect : obstacles) {
                if (rect.right <= bandLeft || rect.left >= bandRight) {
                    continue;
                }
                addSpan(spans, rect, margin, usableTop, usableBottom);
            }
            addSpan(spans, fingerprint, margin, usableTop, usableBottom);
            spans.sort((a, b) -> Integer.compare(a[0], b[0]));

            List<int[]> gaps = new ArrayList<>();
            int cursor = usableTop;
            for (int[] span : spans) {
                if (span[0] > cursor) {
                    gaps.add(new int[] {cursor, Math.min(span[0], usableBottom)});
                }
                cursor = Math.max(cursor, span[1]);
            }
            if (cursor < usableBottom) {
                gaps.add(new int[] {cursor, usableBottom});
            }

            int need = boxH + margin;
            int[] chosen = null;
            if (hasClock) {
                int clockBottom = clockRect.bottom;
                for (int[] gap : gaps) {
                    if (gap[0] >= clockBottom - dp(8) && gap[1] - gap[0] >= need
                            && (chosen == null || gap[1] - gap[0] > chosen[1] - chosen[0])) {
                        chosen = gap;
                    }
                }
            }
            if (chosen == null) {
                for (int[] gap : gaps) {
                    if (gap[1] - gap[0] >= need
                            && (chosen == null || gap[1] - gap[0] > chosen[1] - chosen[0])) {
                        chosen = gap;
                    }
                }
            }
            boolean degraded = false;
            if (chosen == null) {
                degraded = true;
                for (int[] gap : gaps) {
                    if (chosen == null || gap[1] - gap[0] > chosen[1] - chosen[0]) {
                        chosen = gap;
                    }
                }
            }
            int y;
            if (chosen == null) {
                y = Math.max(usableTop, hasClock ? clockRect.bottom + margin : usableTop);
            } else {
                y = chosen[0] + Math.max(0, (chosen[1] - chosen[0] - boxH) / 2);
            }
            // 退化路径兜底：任何情况下都别把文本框推出可用区
            int lowest = Math.max(usableTop, usableBottom - boxH);
            y = Math.max(usableTop, Math.min(y, lowest));

            long signature = 1125899906842597L;
            signature = signature * 31 + rootW;
            signature = signature * 31 + rootH;
            signature = signature * 31 + boxW;
            signature = signature * 31 + boxH;
            signature = signature * 31 + y;
            for (Rect rect : obstacles) {
                signature = signature * 31 + rect.left;
                signature = signature * 31 + rect.top;
                signature = signature * 31 + rect.right;
                signature = signature * 31 + rect.bottom;
            }

            boolean first = !sPlaced;
            if (first || signature != sSignature) {
                sSignature = signature;
                apply(box, root, y);
                AodInjector.log(Log.INFO, "auto layout: y=" + y
                        + " gap=" + gapText(chosen)
                        + " box=" + boxW + "x" + boxH
                        + " root=" + rootW + "x" + rootH
                        + " obstacles=" + obstacles.size()
                        + " clock=" + (hasClock ? rectText(clockRect) : "none")
                        + " fingerprint=" + rectText(fingerprint)
                        + (degraded ? " [降级:空隙不足]" : "")
                        + (first ? " [first]" : ""));
            }
            sPlaced = true;
        } catch (Throwable t) {
            AodInjector.log(Log.WARN, "auto layout failed: " + t);
        }
    }

    private static void addSpan(List<int[]> spans, Rect rect, int margin, int usableTop, int usableBottom) {
        int top = Math.max(usableTop, rect.top - margin);
        int bottom = Math.min(usableBottom, rect.bottom + margin);
        if (bottom > top) {
            spans.add(new int[] {top, bottom});
        }
    }

    /** 收集根容器内的内容矩形（跳过自己的视图、不可见、无尺寸、全屏背景/蒙版）。 */
    private static void collect(ViewGroup parent, ViewGroup root, View box,
                                int rootW, int rootH, List<Rect> out) {
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = parent.getChildAt(i);
            if (child == box || isOurs(child)) {
                continue;
            }
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.08f) {
                continue;
            }
            if (child.getWidth() <= 0 || child.getHeight() <= 0) {
                continue;
            }
            if (child instanceof ViewGroup) {
                collect((ViewGroup) child, root, box, rootW, rootH, out);
            }
            Rect rect = new Rect();
            if (!rectIn(child, root, rect)) {
                continue;
            }
            if (!rect.intersect(0, 0, rootW, rootH)) {
                continue;
            }
            boolean fullBackground = rect.width() >= rootW * 0.9f && rect.height() >= rootH * 0.9f;
            if (!fullBackground) {
                out.add(rect);
            }
        }
    }

    /** 视图在 root 坐标系下的矩形；跨不过 root 或尺寸为 0 时返回 false。 */
    static boolean rectIn(View view, ViewGroup root, Rect out) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) {
            return false;
        }
        out.set(0, 0, view.getWidth(), view.getHeight());
        View cur = view;
        while (cur != null && cur != root) {
            if (!(cur.getParent() instanceof View)) {
                return false;
            }
            View parent = (View) cur.getParent();
            out.offset(cur.getLeft() + (int) cur.getTranslationX() - parent.getScrollX(),
                    cur.getTop() + (int) cur.getTranslationY() - parent.getScrollY());
            cur = parent;
        }
        return cur == root;
    }

    /** 反复合并相交（含 slack 膨胀）的矩形，得到内容块。 */
    private static List<Rect> merge(List<Rect> rects, int slack) {
        List<Rect> open = new ArrayList<>(rects);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < open.size() && !changed; i++) {
                for (int j = i + 1; j < open.size(); j++) {
                    Rect probe = new Rect(open.get(i));
                    probe.inset(-slack, -slack);
                    if (Rect.intersects(probe, open.get(j))) {
                        open.get(i).union(open.get(j));
                        open.remove(j);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return open;
    }

    private static void apply(View box, ViewGroup root, int y) {
        ViewGroup.LayoutParams raw = box.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = y;
            box.setLayoutParams(params);
        } else if (raw instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) raw;
            params.removeRule(RelativeLayout.CENTER_VERTICAL);
            params.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            params.removeRule(RelativeLayout.BELOW);
            params.removeRule(RelativeLayout.ABOVE);
            params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            params.addRule(RelativeLayout.CENTER_HORIZONTAL);
            params.topMargin = y;
            params.bottomMargin = 0;
            box.setLayoutParams(params);
        } else {
            // 兜底：宿主不是 Frame/Relative 容器（罕见），直接用 x/y 定位
            box.setX(root.getWidth() / 2f - box.getWidth() / 2f);
            box.setY(y);
        }
        if (box.getAlpha() < 1f) {
            box.setAlpha(1f);
        }
    }

    private static boolean isOurs(View view) {
        Object tag = view.getTag();
        return tag instanceof String && ((String) tag).startsWith("aodbridge_");
    }

    private static String gapText(int[] gap) {
        return gap == null ? "none" : "[" + gap[0] + "," + gap[1] + "]";
    }

    private static String rectText(Rect rect) {
        return "[" + rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom + "]";
    }

    private static int dp(int value) {
        return (int) (value * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
    }
}
