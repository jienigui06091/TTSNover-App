package com.ttsnover.reader;

import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 极简 HTTP 客户端：只依赖框架（HttpURLConnection + org.json），不需要 OkHttp/Retrofit。
 *  401 时自动续期一次再重试，避免"续期把并发请求挤掉"导致莫名掉登录。 */
public class Api {

    public static class Err extends Exception {
        public final int status;

        public Err(int status, String msg) {
            super(msg);
            this.status = status;
        }

        public boolean isAuth() {
            return status == 401;
        }
    }

    private static final Object REFRESH_LOCK = new Object();
    private static volatile long lastRefreshAt = 0;

    public static String normBase(String b) {
        b = (b == null ? "" : b.trim());
        if (b.isEmpty()) return "";
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://" + b;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static HttpURLConnection open(String path, String method, byte[] raw, String ctype,
                                          int readTimeout, String token) throws Exception {
        String base = normBase(Prefs.base());
        if (base.isEmpty()) throw new Err(0, "还没设置服务器地址");
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "TTSNover-Android/1.0");
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (raw != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            c.setFixedLengthStreamingMode(raw.length);
            OutputStream os = c.getOutputStream();
            os.write(raw);
            os.flush();
            os.close();
        }
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Err httpErr(int code, String txt) {
        String msg = "HTTP " + code;
        try {
            JSONObject o = new JSONObject(txt);
            if (o.has("error")) msg = o.optString("error", msg);
        } catch (Exception ignored) {
        }
        return new Err(code, msg);
    }

    /** 用指定 token 发请求；401 不在这里清 token（交给上层判断）。 */
    private static JSONObject reqWith(String method, String path, byte[] raw, String ctype,
                                      int readTimeout, String token) throws Err {
        HttpURLConnection c = null;
        try {
            c = open(path, method, raw, ctype, readTimeout, token);
            int code = c.getResponseCode();
            String txt = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw httpErr(code, txt);
            if (txt.trim().isEmpty()) return new JSONObject();
            return new JSONObject(txt);
        } catch (Err e) {
            throw e;
        } catch (Exception e) {
            throw new Err(0, "网络错误：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] bytesWith(String path, byte[] raw, String ctype, int readTimeout,
                                    String token) throws Err {
        HttpURLConnection c = null;
        try {
            c = open(path, "POST", raw, ctype, readTimeout, token);
            int code = c.getResponseCode();
            if (code >= 400) throw httpErr(code, readAll(c.getErrorStream()));
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            byte[] out = bo.toByteArray();
            if (out.length < 512) throw new Err(0, "服务端返回的音频为空");
            return out;
        } catch (Err e) {
            throw e;
        } catch (Exception e) {
            throw new Err(0, "网络错误：" + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 401 时静默续期：并发请求只真正刷一次，刷完把新 token 存本地。 */
    private static boolean tryRefresh(String failedToken) {
        synchronized (REFRESH_LOCK) {
            String cur = Prefs.token();
            if (cur != null && !cur.isEmpty() && !cur.equals(failedToken)) return true;
            if (cur == null || cur.isEmpty()) return false;
            long now = System.currentTimeMillis();
            if (now - lastRefreshAt < 4000) return false;
            lastRefreshAt = now;
            try {
                JSONObject r = reqWith("POST", "/api/auth/refresh",
                        new JSONObject().toString().getBytes(StandardCharsets.UTF_8),
                        "application/json; charset=utf-8", 20000, cur);
                String t = r.optString("token", "");
                if (t.isEmpty()) return false;
                Prefs.setToken(t);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    public static JSONObject req(String method, String path, JSONObject body, int readTimeout) throws Err {
        String tk = Prefs.token();
        byte[] raw = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            return reqWith(method, path, raw, "application/json; charset=utf-8", readTimeout, tk);
        } catch (Err e) {
            if (e.status != 401) throw e;
            if (tryRefresh(tk)) {
                return reqWith(method, path, raw, "application/json; charset=utf-8", readTimeout, Prefs.token());
            }
            Prefs.clearToken();
            throw e;
        }
    }

    public static JSONObject json(String path) throws Err {
        return req("GET", path, null, 30000);
    }

    public static JSONObject post(String path, JSONObject body) throws Err {
        return req("POST", path, body, 30000);
    }

    public static JSONObject put(String path, JSONObject body) throws Err {
        return req("PUT", path, body, 30000);
    }

    public static JSONObject delete(String path) throws Err {
        return req("DELETE", path, null, 30000);
    }

    /** 原始 JSON 体（书源导入这种直接收 body 的接口）。 */
    public static JSONObject postRaw(String path, String raw) throws Err {
        String tk = Prefs.token();
        byte[] b = raw == null ? null : raw.getBytes(StandardCharsets.UTF_8);
        String ct = "application/json; charset=utf-8";
        try {
            return reqWith("POST", path, b, ct, 60000, tk);
        } catch (Err e) {
            if (e.status != 401) throw e;
            if (tryRefresh(tk)) return reqWith("POST", path, b, ct, 60000, Prefs.token());
            Prefs.clearToken();
            throw e;
        }
    }

    /** POST 拿二进制（TTS 返回 audio/mpeg）。 */
    public static byte[] bytes(String path, JSONObject body, int readTimeout) throws Err {
        String tk = Prefs.token();
        byte[] raw = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        String ct = "application/json; charset=utf-8";
        try {
            return bytesWith(path, raw, ct, readTimeout, tk);
        } catch (Err e) {
            if (e.status != 401) throw e;
            if (tryRefresh(tk)) return bytesWith(path, raw, ct, readTimeout, Prefs.token());
            Prefs.clearToken();
            throw e;
        }
    }

    /** 401 统一处理：清 token 回登录页。 */
    public static void goLogin(Context ctx) {
        Prefs.clearToken();
        Intent i = new Intent(ctx, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        ctx.startActivity(i);
    }

    public static String errText(Exception e) {
        if (e instanceof Err) {
            Err x = (Err) e;
            if (x.status == 0) return x.getMessage();
            if (x.status == 401) return "登录已过期，请重新登录";
            if (x.status == 429) return "请求太频繁，稍等几秒再试";
            return x.getMessage();
        }
        return String.valueOf(e.getMessage());
    }
}
