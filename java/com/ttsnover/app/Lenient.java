package com.ttsnover.reader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Legado 书源里的 JSON 常带单引号、裸键名、尾逗号、行注释，标准解析器会直接失败。 */
public class Lenient {

    /** 把宽容 JSON 规整成标准 JSON 文本；引号内的内容不动 */
    public static String json(String s) {
        if (s == null) return "{}";
        StringBuilder out = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                char q = ch;
                StringBuilder sb = new StringBuilder();
                sb.append('"');
                i++;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '\\' && i + 1 < n) {
                        char nx = s.charAt(i + 1);
                        if (nx == '\'' || nx == '"') {
                            sb.append(nx == '"' ? "\\\"" : "'");
                        } else {
                            sb.append(c).append(nx);
                        }
                        i += 2;
                        continue;
                    }
                    if (c == q) {
                        i++;
                        break;
                    }
                    if (c == '"') sb.append("\\\"");
                    else if (c == '\n') sb.append("\\n");
                    else if (c == '\r') sb.append("\\r");
                    else if (c == '\t') sb.append("\\t");
                    else sb.append(c);
                    i++;
                }
                sb.append('"');
                out.append(sb);
                continue;
            }
            if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (ch == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i = Math.min(n, i + 2);
                continue;
            }
            if (ch == ',') {
                // 尾逗号：后面第一个非空白是 } 或 ] 就丢掉
                int j = i + 1;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                if (j < n && (s.charAt(j) == '}' || s.charAt(j) == ']')) {
                    i++;
                    continue;
                }
            }
            if (Character.isLetter(ch) || ch == '_' || ch == '$') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_'
                        || s.charAt(j) == '-' || s.charAt(j) == '$')) j++;
                String word = s.substring(i, j);
                int k = j;
                while (k < n && Character.isWhitespace(s.charAt(k))) k++;
                boolean prevIsValue = out.length() > 0 && (out.charAt(out.length() - 1) == '{'
                        || out.charAt(out.length() - 1) == ',');
                if (k < n && s.charAt(k) == ':' && prevIsValue) {
                    out.append('"').append(word).append('"');
                } else {
                    out.append(word);
                }
                i = j;
                continue;
            }
            out.append(ch);
            i++;
        }
        return out.toString();
    }

    public static JSONObject obj(String s) throws Exception {
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return new JSONObject(json(s));
        }
    }

    public static JSONObject fromMap(Map<?, ?> m) {
        JSONObject o = new JSONObject();
        if (m == null) return o;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            Object v = e.getValue();
            try {
                if (v instanceof Map) o.put(String.valueOf(e.getKey()), fromMap((Map<?, ?>) v));
                else if (v instanceof List) o.put(String.valueOf(e.getKey()), fromList((List<?>) v));
                else o.put(String.valueOf(e.getKey()), v);
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    static JSONArray fromList(List<?> l) {
        JSONArray a = new JSONArray();
        for (Object v : l) {
            if (v instanceof Map) a.put(fromMap((Map<?, ?>) v));
            else if (v instanceof List) a.put(fromList((List<?>) v));
            else a.put(v);
        }
        return a;
    }

    /** 源对象里的 header 可能是字符串、对象，也可能是 @js:（本地模式不支持 JS header） */
    public static JSONObject headerOf(JSONObject src) {
        Object h = src == null ? null : src.opt("header");
        if (h instanceof JSONObject) return (JSONObject) h;
        if (h == null) return new JSONObject();
        String s = String.valueOf(h).trim();
        if (s.isEmpty() || s.startsWith("@js:") || s.startsWith("<js>")) return new JSONObject();
        try {
            return obj(s);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    public static String str(JSONObject o, String k, String def) {
        if (o == null) return def;
        String v = o.optString(k, def);
        return v == null ? def : v;
    }

    public static String keys(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        if (o == null) return sb.toString();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(it.next());
        }
        return sb.toString();
    }
}
