package com.ttsnover.reader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 书源抓取用的轻量 HTTP 层：跟随跳转、Cookie、gbk/gb18030 解码、gzip。 */
public class Net {

    public static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    static {
        // 书源大多需要保持 Cookie（搜索后取目录）
        CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
    }

    public static class Resp {
        public int code;
        public String url = "";
        public String body = "";
        public String ctype = "";
        public String error = "";
        public String location = "";
        public byte[] raw = new byte[0];

        public boolean ok() {
            return error.isEmpty() && code > 0 && code < 400;
        }
    }

    public static Resp get(String url, Map<String, String> headers, int timeoutMs) {
        return request(url, "GET", null, headers, timeoutMs);
    }

    /** 手动跟跳转：HttpURLConnection 自动跟随在 streaming/POST 下会抛
     *  "cannot retry due to redirection, in streaming mode"，书源站点大量 302，必须自己走。 */
    public static Resp request(String url, String method, String body,
                               Map<String, String> headers, int timeoutMs) {
        String m = method == null ? "GET" : method.toUpperCase();
        String cur = safeUrl(url);
        String b = body;
        Resp last = new Resp();
        last.url = cur;
        for (int hop = 0; hop < 7; hop++) {
            Resp r = once(cur, m, b, headers, timeoutMs);
            if (!r.error.isEmpty()) return r;
            boolean redirect = r.code == 301 || r.code == 302 || r.code == 303
                    || r.code == 307 || r.code == 308;
            if (!redirect || r.location == null || r.location.isEmpty()) return r;
            String next = abs(r.location, cur);
            if (next.isEmpty() || next.equals(cur)) return r;
            if (r.code == 303 || (r.code != 307 && r.code != 308 && !"GET".equals(m))) {
                m = "GET";
                b = null;
            }
            cur = next;
            last = r;
        }
        return last;
    }

    private static Resp once(String url, String method, String body,
                             Map<String, String> headers, int timeoutMs) {
        Resp out = new Resp();
        out.url = url;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method == null ? "GET" : method.toUpperCase());
            c.setConnectTimeout(timeoutMs <= 0 ? 12000 : timeoutMs);
            c.setReadTimeout(timeoutMs <= 0 ? 12000 : timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/json,*/*");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() == null || e.getValue() == null) continue;
                    try {
                        c.setRequestProperty(e.getKey(), e.getValue());
                    } catch (Exception ignored) {
                    }
                }
            }
            if (body != null) {
                c.setDoOutput(true);
                byte[] bs = body.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(bs.length);
                c.getOutputStream().write(bs);
                c.getOutputStream().flush();
                c.getOutputStream().close();
            }
            out.code = c.getResponseCode();
            out.url = c.getURL() == null ? url : c.getURL().toString();
            out.location = c.getHeaderField("Location");
            if (out.location == null) out.location = "";
            String ct = c.getContentType();
            out.ctype = ct == null ? "" : ct;
            InputStream in = out.code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] raw = readAll(in, c.getContentEncoding());
            out.raw = raw == null ? new byte[0] : raw;
            out.body = decode(out.raw, out.ctype);
        } catch (Throwable t) {
            out.code = 0;
            String m = t.getMessage();
            out.error = "网络失败: " + (m == null ? t.getClass().getSimpleName() : m);
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    /** URL 里带中文/空格/竖线时 new URL 会抛 Illegal character，先转成 ASCII 形式 */
    public static String safeUrl(String u) {
        if (u == null) return "";
        String s = u.trim();
        try {
            return new java.net.URI(s).toASCIIString();
        } catch (Exception e) {
            try {
                String t = s.replace(" ", "%20").replace("|", "%7C").replace("\"", "%22")
                        .replace("<", "%3C").replace(">", "%3E").replace("{", "%7B")
                        .replace("}", "%7D").replace("^", "%5E").replace("`", "%60")
                        .replace("[", "%5B").replace("]", "%5D");
                return new java.net.URI(t).toASCIIString();
            } catch (Exception e2) {
                return s;
            }
        }
    }

    private static byte[] readAll(InputStream in, String encoding) throws Exception {
        if (in == null) return new byte[0];
        InputStream src = in;
        if (encoding != null && encoding.toLowerCase().contains("gzip")) {
            src = new GZIPInputStream(in);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = src.read(buf)) > 0) bo.write(buf, 0, n);
        try {
            src.close();
        } catch (Exception ignored) {
        }
        return bo.toByteArray();
    }

    /** 先看响应头 charset，再看正文 meta，gbk/gb2312 统一按 gb18030 解 */
    public static String decode(byte[] raw, String ctype) {
        if (raw == null || raw.length == 0) return "";
        String cs = matchCharset(ctype == null ? "" : ctype);
        if (cs.isEmpty()) {
            String head = new String(raw, 0, Math.min(raw.length, 2048), java.nio.charset.StandardCharsets.ISO_8859_1);
            cs = matchCharset(head);
        }
        cs = cs.toLowerCase();
        if (cs.equals("gb2312") || cs.equals("gbk") || cs.equals("gb18030")) {
            try {
                return new String(raw, "GB18030");
            } catch (Exception ignored) {
            }
        }
        if (!cs.isEmpty()) {
            try {
                return new String(raw, cs);
            } catch (Exception ignored) {
            }
        }
        try {
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return new String(raw);
        }
    }

    private static String matchCharset(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("charset=[\"']?([\\w-]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return m.group(1);
        return "";
    }

    public static Map<String, String> headers(String... kv) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static String abs(String u, String base) {
        if (u == null) return "";
        String s = u.trim();
        if (s.isEmpty()) return "";
        if (s.matches("^[a-zA-Z][\\w+.-]*:.*")) return s;
        try {
            return new URL(new URL(base), s).toString();
        } catch (Exception e) {
            return s;
        }
    }
}
