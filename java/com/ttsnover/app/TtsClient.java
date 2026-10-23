package com.ttsnover.reader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 手机直连 TTS。小米 MiMo 是国内接口，直连可用；Key 只存在本机 SharedPreferences。
 * 也可以填任意「自定义 HTTP TTS」（自己搭的 Fish 代理等），走 {{text}}/{{voice}}/{{speed}} 模板。
 */
public class TtsClient {

    public static final String MIMO_URL = "https://api.xiaomimimo.com/v1/chat/completions";
    public static final String STYLE_DEFAULT = "自然、平稳的朗读语气，语速适中，适合长篇小说朗读";
    public static final String DESIGN_DEFAULT =
            "五十多岁的男性评书先生，北方口音，嗓音醇厚略带沙哑，语速舒缓、抑扬顿挫分明，像在茶馆里讲长篇故事。";

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("tts", Context.MODE_PRIVATE);
    }

    public static String engine(Context c) {
        return sp(c).getString("engine", "mimo");
    }

    public static String mimoKey(Context c) {
        return sp(c).getString("mimo_key", "");
    }

    public static String mode(Context c) {
        String m = sp(c).getString("mode", "preset");
        return m == null || m.isEmpty() ? "preset" : m;
    }

    public static String voice(Context c) {
        String v = sp(c).getString("voice", "冰糖");
        return v == null || v.trim().isEmpty() ? "冰糖" : v.trim();
    }

    public static String style(Context c) {
        String s = sp(c).getString("style", "");
        return s == null || s.trim().isEmpty() ? STYLE_DEFAULT : s.trim();
    }

    public static String desc(Context c) {
        String s = sp(c).getString("desc", "");
        return s == null || s.trim().isEmpty() ? DESIGN_DEFAULT : s.trim();
    }

    public static float speed(Context c) {
        return sp(c).getFloat("speed", 1.0f);
    }

    /** 有没有可用的 Key（自定义引擎不看 key） */
    public static boolean configured(Context c) {
        if ("custom".equals(engine(c))) return !sp(c).getString("url", "").trim().isEmpty();
        return !mimoKey(c).trim().isEmpty();
    }

    public static String keyMask(Context c) {
        String k = mimoKey(c).trim();
        if (k.isEmpty()) return "";
        if (k.length() <= 8) return "已配置";
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    public static byte[] synth(Context c, String text) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new Exception("空文本");
        String eng = engine(c);
        File cached = cacheFile(c, eng + "|" + mode(c) + "|" + voice(c) + "|" + style(c) + "|" + t);
        if (cached.isFile() && cached.length() > 1200) {
            return readAll(cached);
        }
        byte[] audio = "custom".equals(eng) ? synthCustom(c, t) : synthMimo(c, t);
        if (audio == null || audio.length < 1200) {
            throw new Exception("音频只有 " + (audio == null ? 0 : audio.length) + " 字节，几乎肯定是错误响应");
        }
        try {
            FileOutputStream fo = new FileOutputStream(cached);
            fo.write(audio);
            fo.close();
        } catch (Exception ignored) {
        }
        return audio;
    }

    static byte[] synthMimo(Context c, String text) throws Exception {
        String key = mimoKey(c).trim();
        if (key.isEmpty()) {
            throw new Exception("还没填小米 API Key：我的 → 朗读设置 → 小米 Key");
        }
        String m = mode(c);
        String model = "preset".equals(m) ? "mimo-v2.5-tts"
                : ("design".equals(m) ? "mimo-v2.5-tts-voicedesign" : "mimo-v2.5-tts-voiceclone");
        String st = style(c);
        String userMsg;
        JSONObject audio = new JSONObject();
        if ("design".equals(m)) {
            String d = desc(c);
            userMsg = st.equals(STYLE_DEFAULT) ? d : d + "\n" + st;
            audio.put("format", "mp3");
        } else if ("clone".equals(m)) {
            throw new Exception("音色克隆要上传样本，手机版暂未支持；先用预置音色或音色设计");
        } else {
            userMsg = st;
            audio.put("format", "mp3");
            audio.put("voice", voice(c));
        }
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "user").put("content", userMsg));
        msgs.put(new JSONObject().put("role", "assistant").put("content", text));
        payload.put("messages", msgs);
        payload.put("audio", audio);

        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Authorization", "Bearer " + key);
        Net.Resp r = Net.request(MIMO_URL, "POST", payload.toString(), h, 120000);
        if (r.error != null && !r.error.isEmpty()) {
            throw new Exception("连不上小米接口：" + r.error + "（手机能打开 xiaomimimo.com 再试）");
        }
        if (r.code >= 400) {
            throw new Exception("小米接口 HTTP " + r.code + "：" + brief(r.body));
        }
        JSONObject j;
        try {
            j = new JSONObject(r.body);
        } catch (Exception e) {
            throw new Exception("小米接口返回的不是 JSON：" + brief(r.body));
        }
        if (j.has("error")) {
            throw new Exception("小米接口报错：" + brief(j.opt("error").toString()));
        }
        String b64;
        try {
            b64 = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                    .getJSONObject("audio").getString("data");
        } catch (Exception e) {
            throw new Exception("小米接口返回里没有音频字段：" + brief(r.body));
        }
        return Base64.decode(b64, Base64.DEFAULT);
    }

    static byte[] synthCustom(Context c, String text) throws Exception {
        SharedPreferences p = sp(c);
        String url = p.getString("url", "").trim();
        if (url.isEmpty()) throw new Exception("自定义 TTS 地址没填");
        String voice = p.getString("cvoice", voice(c));
        String method = p.getString("method", "GET");
        float speed = speed(c);
        url = fill(url, text, voice, speed);
        Map<String, String> h = new HashMap<>();
        String hraw = p.getString("headers", "").trim();
        if (!hraw.isEmpty()) {
            try {
                JSONObject ho = Lenient.obj(fill(hraw, text, voice, speed));
                JSONArray ks = ho.names();
                if (ks != null) for (int i = 0; i < ks.length(); i++) {
                    String k = ks.getString(i);
                    h.put(k, ho.optString(k));
                }
            } catch (Exception ignored) {
            }
        }
        String body = null;
        if ("POST".equalsIgnoreCase(method)) {
            body = fill(p.getString("body", ""), text, voice, speed);
            if (!h.containsKey("Content-Type")) h.put("Content-Type", "application/json");
        }
        Net.Resp r = Net.request(url, method, body, h, 120000);
        if (r.error != null && !r.error.isEmpty()) throw new Exception("连不上 TTS：" + r.error);
        if (r.code >= 400) throw new Exception("TTS 接口 HTTP " + r.code + "：" + brief(r.body));
        String ct = r.ctype == null ? "" : r.ctype.toLowerCase();
        if (ct.startsWith("audio")) return r.raw;
        // 有些接口返回 JSON 里带音频地址
        try {
            JSONObject j = new JSONObject(r.body);
            String u = j.optString("url", j.optString("audio", ""));
            if (u.startsWith("http")) {
                Net.Resp a = Net.request(u, "GET", null, null, 60000);
                if (a.code < 400 && a.raw != null) return a.raw;
            }
            String b64 = j.optString("data", j.optString("audio_base64", ""));
            if (!b64.isEmpty()) return Base64.decode(b64, Base64.DEFAULT);
        } catch (Exception ignored) {
        }
        throw new Exception("TTS 返回的不是音频（Content-Type: " + ct + "）：" + brief(r.body));
    }

    static String fill(String tpl, String text, String voice, float speed) {
        if (tpl == null) return "";
        return tpl.replace("{{text}}", enc(text))
                .replace("{{text_raw}}", text)
                .replace("{{voice}}", enc(voice == null ? "" : voice))
                .replace("{{voice_raw}}", voice == null ? "" : voice)
                .replace("{{speed}}", String.valueOf(speed));
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    static File cacheFile(Context c, String seed) throws Exception {
        File dir = new File(c.getCacheDir(), "tts");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, sha1(seed) + ".mp3");
    }

    static String sha1(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static byte[] readAll(File f) throws Exception {
        byte[] buf = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n <= 0) break;
            off += n;
        }
        in.close();
        return buf;
    }

    public static long cacheSize(Context c) {
        File dir = new File(c.getCacheDir(), "tts");
        long n = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    public static void clearCache(Context c) {
        File dir = new File(c.getCacheDir(), "tts");
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    static String brief(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }
}
