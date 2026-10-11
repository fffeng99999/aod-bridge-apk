package io.aodbridge.common;

/** App 进程（轮询服务）与 SystemUI 进程（hook）之间的广播契约。 */
public final class Bridge {
    public static final String ACTION = "io.aodbridge.USAGE";
    public static final String TARGET_PACKAGE = "com.android.systemui";

    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_TOKENS = "total_tokens";
    public static final String EXTRA_COST = "cost_cny";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_UPDATED_AT = "updated_at";
    public static final String EXTRA_STATE = "state";

    /** 数据状态：正常 / 中转为缓存兜底 / 取数失败（离线）。 */
    public static final String STATE_OK = "ok";
    public static final String STATE_STALE = "stale";
    public static final String STATE_OFFLINE = "offline";

    /** 显示文案：AOD 上还没拿到过数据 / 从未连上中转 / 状态后缀。 */
    public static final String TEXT_WAITING = "AOD Bridge · 等待数据";
    public static final String TEXT_NO_CONNECTION = "⚠ 未连接中转";
    public static final String TEXT_CACHE_SUFFIX = " ⚠缓存";
    public static final String TEXT_OFFLINE_SUFFIX = " ⚠离线";

    /** AOD 最上方标题（hook 侧单独一行，字号更大）。 */
    public static final String TITLE = "Cyberfox AI API Usage";

    /**
     * 显示位置/垂直微调：v0.1.9 起废弃——落点由 hook 侧的 AutoLayout 自动避让
     * （原生息屏内容 + 指纹区），App 不再提供设置。常量保留只为兼容旧版 App/文档。
     */
    public static final String EXTRA_POS = "pos";
    public static final String EXTRA_OFFSET_DP = "offset_dp";
    public static final String EXTRA_SHIFT = "shift";

    /** 锁屏后显示时长（秒）；0 = 保持显示不自动关闭（hook 侧独立计时，与系统 AOD 时长无关）。 */
    public static final String EXTRA_DURATION_SEC = "duration_sec";
    public static final int DURATION_KEEP = 0;
    public static final int DURATION_DEFAULT = DURATION_KEEP;

    /**
     * 息屏保活：系统息屏（AOD）到点要熄灭时，hook 拦下“结束/隐藏”类调用，
     * 让 AOD 按本模块的显示时长走；到点或亮屏后放行。默认开。
     */
    public static final String EXTRA_KEEP_AOD = "keep_aod";
    public static final boolean KEEP_AOD_DEFAULT = true;

    /**
     * 息屏指纹图标防烧屏（v0.2.0）：隐藏 AOD 上常亮的屏下指纹图标。
     * 实现 = hook 侧写 ColorOS 自带开关（Settings.Secure
     * "show_fingerprint_when_screen_off" = 0）+ 视图级兜底拦截；指纹解锁不受影响。
     * 默认开。
     */
    public static final String EXTRA_HIDE_FP = "hide_fp";
    public static final boolean HIDE_FP_DEFAULT = true;

    public static final String POS_TOP = "top";
    public static final String POS_CENTER = "center";
    public static final String POS_BOTTOM = "bottom";
    public static final String POS_UNDER_CLOCK = "under_clock";
    public static final String POS_DEFAULT = POS_CENTER;
    public static final int OFFSET_DP_DEFAULT = 0;
    public static final int OFFSET_DP_MIN = -400;
    public static final int OFFSET_DP_MAX = 400;
    public static final boolean SHIFT_DEFAULT = true;

    /** App 侧「显示哪些数据行」的位掩码，默认全选。 */
    public static final int LINE_TODAY = 1;
    public static final int LINE_WEEK = 1 << 1;
    public static final int LINE_MONTH = 1 << 2;
    public static final int LINE_REQUESTS = 1 << 3;
    public static final int LINE_IO = 1 << 4;
    public static final int LINE_CACHE = 1 << 5;
    public static final int LINE_ALL = (1 << 6) - 1;

    private Bridge() {
    }
}
