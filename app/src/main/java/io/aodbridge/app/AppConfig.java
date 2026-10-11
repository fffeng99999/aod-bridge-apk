package io.aodbridge.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.aodbridge.common.Bridge;

/** 模块 App 自身的配置与状态（只存在 App 进程，不涉及 hook 进程）。 */
public final class AppConfig {
    private static final String PREFS = "aodbridge";
    private static final String KEY_URL = "relay_url";
    private static final String KEY_TOKEN = "relay_token";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_RANGE = "range";
    private static final String KEY_STATUS = "last_status";
    private static final String KEY_SHIFT = "shift";
    private static final String KEY_DURATION_SEC = "duration_sec";
    private static final String KEY_LINES = "lines";
    private static final String KEY_KEEP_AOD = "keep_aod";
    private static final String KEY_HIDE_FP = "hide_fp";
    private static final String KEY_POLL_SEC = "poll_sec";

    public static final String DEFAULT_URL = "http://192.168.1.100:8790";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String url(Context context) {
        return prefs(context).getString(KEY_URL, DEFAULT_URL);
    }

    public static String token(Context context) {
        return prefs(context).getString(KEY_TOKEN, "");
    }

    public static String range(Context context) {
        return prefs(context).getString(KEY_RANGE, "today");
    }

    public static boolean enabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static boolean shift(Context context) {
        return prefs(context).getBoolean(KEY_SHIFT, Bridge.SHIFT_DEFAULT);
    }

    public static int durationSec(Context context) {
        return prefs(context).getInt(KEY_DURATION_SEC, Bridge.DURATION_DEFAULT);
    }

    public static int linesMask(Context context) {
        return prefs(context).getInt(KEY_LINES, Bridge.LINE_ALL);
    }

    public static boolean keepAod(Context context) {
        return prefs(context).getBoolean(KEY_KEEP_AOD, Bridge.KEEP_AOD_DEFAULT);
    }

    public static boolean hideFp(Context context) {
        return prefs(context).getBoolean(KEY_HIDE_FP, Bridge.HIDE_FP_DEFAULT);
    }

    /** 轮询间隔（秒），夹在 [POLL_MIN_SEC, POLL_MAX_SEC]。 */
    public static int pollSec(Context context) {
        int value = prefs(context).getInt(KEY_POLL_SEC, Bridge.POLL_DEFAULT_SEC);
        return Math.max(Bridge.POLL_MIN_SEC, Math.min(Bridge.POLL_MAX_SEC, value));
    }

    public static void setPollSec(Context context, int seconds) {
        int value = Math.max(Bridge.POLL_MIN_SEC, Math.min(Bridge.POLL_MAX_SEC, seconds));
        prefs(context).edit().putInt(KEY_POLL_SEC, value).apply();
    }

    public static void saveDisplay(Context context, boolean shift,
                                   int durationSec, int linesMask, boolean keepAod,
                                   boolean hideFp) {
        prefs(context).edit()
                .putBoolean(KEY_SHIFT, shift)
                .putInt(KEY_DURATION_SEC, durationSec)
                .putInt(KEY_LINES, linesMask)
                .putBoolean(KEY_KEEP_AOD, keepAod)
                .putBoolean(KEY_HIDE_FP, hideFp)
                .apply();
    }

    public static void save(Context context, String url, String token, String range, boolean enabled) {
        prefs(context).edit()
                .putString(KEY_URL, url)
                .putString(KEY_TOKEN, token)
                .putString(KEY_RANGE, range)
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static void setStatus(Context context, String status) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        prefs(context).edit().putString(KEY_STATUS, "[" + stamp + "] " + status).apply();
    }

    public static String status(Context context) {
        return prefs(context).getString(KEY_STATUS, "未启动");
    }

    private AppConfig() {
    }
}
