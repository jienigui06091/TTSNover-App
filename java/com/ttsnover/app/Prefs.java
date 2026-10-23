package com.ttsnover.reader;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static SharedPreferences sp() {
        return App.ctx.getSharedPreferences("ttsnover", Context.MODE_PRIVATE);
    }

    public static String base() { return sp().getString("base", ""); }

    public static void setBase(String v) { sp().edit().putString("base", v == null ? "" : v.trim()).apply(); }

    public static String token() { return sp().getString("token", ""); }

    public static void setToken(String v) { sp().edit().putString("token", v == null ? "" : v).apply(); }

    public static String user() { return sp().getString("user", ""); }

    public static void setUser(String v) { sp().edit().putString("user", v == null ? "" : v).apply(); }

    public static boolean loggedIn() { return true; }   // 本地模式：没有登录，永远可用

    // ---- 朗读偏好：App 本地保存，每次合成时随请求发过去，避免和网页设置互相覆盖 ----
    public static String ttsProvider() { return sp().getString("ttsProvider", "mimo"); }

    public static void setTtsProvider(String v) { sp().edit().putString("ttsProvider", v).apply(); }

    public static String ttsVoice() { return sp().getString("ttsVoice", ""); }

    public static void setTtsVoice(String v) { sp().edit().putString("ttsVoice", v == null ? "" : v.trim()).apply(); }

    public static float ttsSpeed() { return sp().getFloat("ttsSpeed", 1.18f); }

    public static void setTtsSpeed(float v) { sp().edit().putFloat("ttsSpeed", v).apply(); }

    public static void clearToken() {
        sp().edit().remove("token").remove("user").apply();
    }

    public static void clearAll() {
        sp().edit().clear().apply();
    }
}
