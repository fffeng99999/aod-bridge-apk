package io.aodbridge.app;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 极简 HTTP 客户端：GET {relay}/aod/usage，Bearer 令牌。 */
final class RelayClient {
    static JSONObject fetchUsage(String baseUrl, String token, String range) throws IOException {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            throw new IOException("中转地址为空");
        }
        if (!trimmed.contains("://")) {
            // 裸地址补协议：纯 IP/端口（首字段是数字）按 http，域名（反代+证书）按 https
            trimmed = (Character.isDigit(trimmed.charAt(0)) ? "http://" : "https://") + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("中转令牌为空");
        }
        URL url = new URL(trimmed + "/aod/usage?range=" + (range == null ? "today" : range));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(stream);
            JSONObject payload;
            try {
                payload = new JSONObject(body);
            } catch (Exception parseError) {
                throw new IOException("HTTP " + code + " 响应无法解析: " + body);
            }
            if (code != 200 || !payload.optBoolean("ok", false)) {
                throw new IOException("HTTP " + code + " " + payload.optString("error", body));
            }
            return payload;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private RelayClient() {
    }

    /** 账号禁用表（App 内专用；该数据禁止上 AOD）。 */
    static JSONObject fetchAccounts(String baseUrl, String token) throws IOException {
        String trimmed = normalizeBase(baseUrl);
        if (token == null || token.trim().isEmpty()) {
            throw new IOException("中转令牌为空");
        }
        URL url = new URL(trimmed + "/aod/accounts");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token.trim());
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = readAll(stream);
            JSONObject payload;
            try {
                payload = new JSONObject(body);
            } catch (Exception parseError) {
                throw new IOException("HTTP " + code + " 响应无法解析: " + body);
            }
            if (code != 200 || !payload.optBoolean("ok", false)) {
                throw new IOException("HTTP " + code + " " + payload.optString("error", body));
            }
            return payload;
        } finally {
            conn.disconnect();
        }
    }

    private static String normalizeBase(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (!trimmed.contains("://")) {
            trimmed = (Character.isDigit(trimmed.charAt(0)) ? "http://" : "https://") + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
