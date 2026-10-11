package io.aodbridge.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.aodbridge.common.Bridge;

/**
 * 按 App 里的勾选把中转 JSON 拼成 AOD 文案。
 * 失败语义：中转 JSON 的 "failed" 字典列出在 hub 侧读取失败的字段（值=原因），
 * 对应数据位置渲染 ❌（金额为 ¥❌）——绝不把失败静默当成 0 显示。
 * 格式与 relay.py 的 build_text() 保持一致；中转的 text 字段只作为兜底。
 */
final class UsageText {
    private static final String FAIL_MARK = "❌";

    static String build(JSONObject payload, int mask) {
        JSONObject failed = payload.optJSONObject("failed");
        List<String> lines = new ArrayList<>();
        if ((mask & Bridge.LINE_TODAY) != 0) {
            lines.add("今日 " + tokensField(payload, failed, "total_tokens") + " tok｜"
                    + moneyField(payload, failed, "cost_cny"));
        }
        if ((mask & Bridge.LINE_WEEK) != 0
                && (payload.optLong("week_total_tokens") > 0
                || isFailed(failed, "week_total_tokens")
                || isFailed(failed, "week_cost_cny"))) {
            lines.add("本周 " + tokensField(payload, failed, "week_total_tokens") + " tok｜"
                    + moneyField(payload, failed, "week_cost_cny"));
        }
        if ((mask & Bridge.LINE_MONTH) != 0
                && (payload.optLong("month_total_tokens") > 0
                || isFailed(failed, "month_total_tokens")
                || isFailed(failed, "month_cost_cny"))) {
            lines.add("本月 " + tokensField(payload, failed, "month_total_tokens") + " tok｜"
                    + moneyField(payload, failed, "month_cost_cny"));
        }
        if ((mask & Bridge.LINE_REQUESTS) != 0) {
            lines.add("请求 " + intField(payload, failed, "requests")
                    + "｜错误 " + intField(payload, failed, "errors"));
        }
        if ((mask & Bridge.LINE_IO) != 0
                && (payload.optLong("prompt_tokens") > 0
                || payload.optLong("completion_tokens") > 0
                || isFailed(failed, "prompt_tokens")
                || isFailed(failed, "completion_tokens"))) {
            lines.add("输入 " + tokensField(payload, failed, "prompt_tokens")
                    + "｜输出 " + tokensField(payload, failed, "completion_tokens"));
        }
        if ((mask & Bridge.LINE_CACHE) != 0
                && (payload.optLong("cached_tokens") > 0
                || isFailed(failed, "cached_tokens"))) {
            lines.add("缓存命中 " + tokensField(payload, failed, "cached_tokens"));
        }
        return String.join("\n", lines);
    }

    private static boolean isFailed(JSONObject failed, String name) {
        return failed != null && failed.has(name);
    }

    private static String tokensField(JSONObject payload, JSONObject failed, String name) {
        return isFailed(failed, name) ? FAIL_MARK : tokens(payload.optLong(name));
    }

    private static String intField(JSONObject payload, JSONObject failed, String name) {
        return isFailed(failed, name) ? FAIL_MARK : String.valueOf(payload.optInt(name));
    }

    private static String moneyField(JSONObject payload, JSONObject failed, String name) {
        return isFailed(failed, name) ? "¥" + FAIL_MARK : money(payload.opt(name));
    }

    /** 与 relay.py fmt_tokens() 相同：1 万 / 1 亿 分档，去掉多余的 0。 */
    static String tokens(long n) {
        if (n < 10000) {
            return String.valueOf(n);
        }
        String text;
        if (n < 100000000L) {
            text = trim(String.format(Locale.US, "%.1f", n / 10000.0));
            return text + "万";
        }
        text = trim(String.format(Locale.US, "%.2f", n / 100000000.0));
        return text + "亿";
    }

    /** 与 relay.py fmt_money() 相同；字段缺失时显示 ¥?。 */
    static String money(Object value) {
        try {
            return String.format(Locale.US, "¥%.2f", Double.parseDouble(String.valueOf(value)));
        } catch (Exception error) {
            return "¥?";
        }
    }

    private static String trim(String value) {
        if (value.indexOf('.') < 0) {
            return value;
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && value.charAt(end - 1) == '.') {
            end--;
        }
        return value.substring(0, end);
    }

    private UsageText() {
    }
}
