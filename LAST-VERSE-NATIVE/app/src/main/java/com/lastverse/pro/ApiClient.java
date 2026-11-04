package com.lastverse.pro;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small dependency-free HTTP client for the LAST VERSE API. */
public final class ApiClient {
    private final SessionStore store;

    public ApiClient(SessionStore store) { this.store = store; }

    public JSONObject request(String method, String path, JSONObject body) throws Exception {
        String base = store.getBaseUrl();
        if (base == null || base.trim().isEmpty()) throw new IllegalStateException("اكتب رابط الخادم أولًا من الإعدادات.");
        base = base.trim().replaceAll("/+$", "");
        if (!base.startsWith("https://")) throw new IllegalStateException("رابط الخادم لازم يبدأ بـ https:// لحماية الحساب.");
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "LAST-VERSE-ANDROID/2.65.1");
        String cookie = store.readCookie();
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        }
        int status = connection.getResponseCode();
        String setCookie = connection.getHeaderField("Set-Cookie");
        if (setCookie != null && setCookie.startsWith("sid=")) {
            String first = setCookie.split(";", 2)[0];
            if (first.equals("sid=") || setCookie.contains("Max-Age=0")) store.clearCookie();
            else store.saveCookie(first);
        }
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = readAll(stream);
        connection.disconnect();
        JSONObject result;
        try { result = text.isEmpty() ? new JSONObject() : new JSONObject(text); }
        catch (Exception parseError) { throw new IllegalStateException("رد الخادم غير مفهوم (HTTP " + status + ")."); }
        if (status < 200 || status >= 300 || !result.optBoolean("ok", status < 300)) {
            throw new ApiException(status, result.optString("error", "تعذر تنفيذ الطلب."));
        }
        return result;
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }

    public static final class ApiException extends Exception {
        public final int status;
        public ApiException(int status, String message) { super(message); this.status = status; }
    }
}
