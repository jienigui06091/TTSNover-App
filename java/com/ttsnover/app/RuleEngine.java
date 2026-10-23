package com.ttsnover.reader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.Wrapper;

import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Legado 书源规则引擎（离线版，等价移植 srce/engine.js）。
 * 支持：JSONPath 子集 / jsoup CSS 简写 / 正则 ## ## / && || %% / {{JS}} / @js: 与 &lt;js&gt;。
 * JS 由 Rhino 解释执行，java.* 兼容层用 HttpURLConnection 实现。
 */
public class RuleEngine {

    /* ------------------------------------------------------------------ *
     *  上下文与值模型
     * ------------------------------------------------------------------ */
    public static class Ctx {
        public Document doc;
        public Object result;
        public String baseUrl = "";
        public String key = "";
        public int page = 1;
        public JSONObject source;
        public List<Element> scope;
        public Map<String, Object> vars = new HashMap<String, Object>();

        Ctx dup() {
            Ctx c = new Ctx();
            c.doc = doc;
            c.result = result;
            c.baseUrl = baseUrl;
            c.key = key;
            c.page = page;
            c.source = source;
            c.scope = scope;
            c.vars = new HashMap<String, Object>(vars);
            return c;
        }
    }

    static final Set<String> TEXT_STEPS = new HashSet<String>(Arrays.asList(
            "text", "textNodes", "ownText", "html", "outerHtml", "all", "innerHtml"));

    static final Set<String> TAG_NAMES = new HashSet<String>(Arrays.asList((
            "a abbr address article aside audio b bdi bdo blockquote body br button canvas caption "
                    + "center cite code col colgroup dd del details dfn dialog div dl dt em fieldset figcaption "
                    + "figure font footer form h1 h2 h3 h4 h5 h6 head header hr html i iframe img input ins kbd "
                    + "label legend li link main map mark marquee meta nav noscript object ol optgroup option p "
                    + "picture pre q rp rt ruby s samp script section select slot small source span strong style "
                    + "sub summary sup table tbody td template textarea tfoot th thead time title tr track u ul "
                    + "var video wbr").split("\\s+")));

    /* ------------------------------------------------------------------ *
     *  工具
     * ------------------------------------------------------------------ */
    /** 切分：引号 / 括号内的分隔符不算 */
    public static List<String> splitTop(String str, String sep) {
        List<String> out = new ArrayList<String>();
        int depth = 0;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char ch = str.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == '\\') {
                    if (i + 1 < str.length()) cur.append(str.charAt(++i));
                    continue;
                }
                if (ch == quote) quote = 0;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                quote = ch;
                cur.append(ch);
                continue;
            }
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
                cur.append(ch);
                continue;
            }
            if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
                cur.append(ch);
                continue;
            }
            if (depth == 0 && str.startsWith(sep, i)) {
                out.add(cur.toString());
                cur.setLength(0);
                i += sep.length() - 1;
                continue;
            }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    static List<Object> asList(Object v) {
        List<Object> out = new ArrayList<Object>();
        if (v == null) return out;
        if (v instanceof List) {
            out.addAll((List<?>) v);
        } else if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) out.add(a.opt(i));
        } else if (v instanceof Elements) {
            out.addAll((Elements) v);
        } else {
            out.add(v);
        }
        return out;
    }

    static boolean isBlank(Object v) {
        if (v == null || v == Undefined.instance) return true;
        if (v instanceof List) return ((List<?>) v).isEmpty();
        return String.valueOf(v).trim().isEmpty();
    }

    /** 与服务端 str() 对齐：数组用逗号拼、对象转 JSON、字符串去标签压空白 */
    public static String str(Object v) {
        if (v == null || v == Undefined.instance) return "";
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (List<?>) v) {
                String s = str(o);
                if (s.isEmpty()) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(s);
            }
            return sb.toString();
        }
        if (v instanceof Element) return cleanInline(((Element) v).text());
        if (v instanceof JSONObject || v instanceof JSONArray || v instanceof Map) return jsonStr(v);
        if (v instanceof Number) return numStr(v);
        return cleanInline(String.valueOf(v));
    }

    /** JS 的数字都是 double：整数不能带 ".0"（{{1+1}} → "2"，和 Node 引擎一致）。 */
    static String numStr(Object v) {
        if (!(v instanceof Number)) return String.valueOf(v);
        double d = ((Number) v).doubleValue();
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(v);
    }

    static String cleanInline(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }

    static String jsonStr(Object v) {
        if (v instanceof JSONObject || v instanceof JSONArray) return v.toString();
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                try {
                    o.put(String.valueOf(e.getKey()), e.getValue());
                } catch (Exception ignored) {
                }
            }
            return o.toString();
        }
        return String.valueOf(v);
    }

    static boolean isObjNode(Object n) {
        return n instanceof JSONObject || n instanceof Map;
    }

    static Object field(Object node, String name) {
        if (node instanceof JSONObject) return ((JSONObject) node).opt(name);
        if (node instanceof Map) return ((Map<?, ?>) node).get(name);
        return null;
    }

    /* ------------------------------------------------------------------ *
     *  JSONPath 子集
     * ------------------------------------------------------------------ */
    public static Object jsonPath(Object root, String path) {
        if (path == null) return null;
        String p = path.trim();
        if (p.startsWith("@json:")) p = p.substring(6).trim();
        if (!p.startsWith("$")) return null;
        p = p.substring(1);
        List<Object> cur = new ArrayList<Object>();
        cur.add(root);
        int i = 0;
        while (i < p.length()) {
            if (p.startsWith("..", i)) {
                i += 2;
                String name;
                if (i < p.length() && p.charAt(i) == '*') {
                    name = "*";
                    i++;
                } else if (i < p.length() && p.charAt(i) == '[') {
                    int end = p.indexOf(']', i);
                    if (end < 0) break;
                    name = p.substring(i + 1, end);
                    i = end + 1;
                } else {
                    Matcher m = Pattern.compile("^[^.\\[]+").matcher(p.substring(i));
                    name = m.find() ? m.group() : "";
                    i += name.length();
                }
                List<Object> next = new ArrayList<Object>();
                for (Object node : cur) walkDesc(node, name, next);
                cur = next;
                continue;
            }
            char ch = p.charAt(i);
            if (ch == '.') {
                i++;
                Matcher m = Pattern.compile("^[^.\\[]+").matcher(p.substring(i));
                if (!m.find()) break;
                String name = m.group();
                i += name.length();
                List<Object> next = new ArrayList<Object>();
                for (Object node : cur) {
                    if (node == null) continue;
                    if (name.equals("*")) {
                        if (node instanceof JSONArray) {
                            JSONArray a = (JSONArray) node;
                            for (int k = 0; k < a.length(); k++) next.add(a.opt(k));
                        } else if (node instanceof List) {
                            next.addAll((List<?>) node);
                        } else if (isObjNode(node)) {
                            next.addAll(objValues(node));
                        }
                    } else if (isObjNode(node)) {
                        Object v = field(node, name);
                        if (v != null) next.add(v);
                    }
                }
                cur = next;
                continue;
            }
            if (ch == '[') {
                int end = p.indexOf(']', i);
                if (end < 0) break;
                String inner = p.substring(i + 1, end).trim();
                i = end + 1;
                List<Object> next = new ArrayList<Object>();
                for (Object node : cur) {
                    if (inner.equals("*")) {
                        next.addAll(asList(node));
                        continue;
                    }
                    if (inner.matches("^-?\\d+$")) {
                        int n = Integer.parseInt(inner);
                        if (node instanceof JSONArray) {
                            JSONArray a = (JSONArray) node;
                            int idx = n < 0 ? a.length() + n : n;
                            if (idx >= 0 && idx < a.length()) next.add(a.opt(idx));
                        } else if (node instanceof List) {
                            List<?> l = (List<?>) node;
                            int idx = n < 0 ? l.size() + n : n;
                            if (idx >= 0 && idx < l.size()) next.add(l.get(idx));
                        }
                        continue;
                    }
                    if (inner.contains(",")) {
                        for (String part : inner.split(",")) {
                            try {
                                int n = Integer.parseInt(part.trim());
                                if (node instanceof JSONArray) {
                                    JSONArray a = (JSONArray) node;
                                    if (n >= 0 && n < a.length()) next.add(a.opt(n));
                                } else if (node instanceof List) {
                                    List<?> l = (List<?>) node;
                                    if (n >= 0 && n < l.size()) next.add(l.get(n));
                                }
                            } catch (Exception ignored) {
                            }
                        }
                        continue;
                    }
                    Matcher q = Pattern.compile("^\\?\\(@\\.([\\w.]+)\\s*(==|!=|=~|>|<)\\s*(.+?)\\)$").matcher(inner);
                    if (q.find()) {
                        String f = q.group(1), op = q.group(2), rawVal = q.group(3);
                        String want = rawVal.replaceAll("^['\"]|['\"]$", "");
                        for (Object item : asList(node)) {
                            Object got = f.split("\\.").length > 1 ? deepField(item, f) : field(item, f);
                            boolean ok = false;
                            if (op.equals("==")) ok = String.valueOf(got).equals(want);
                            else if (op.equals("!=")) ok = !String.valueOf(got).equals(want);
                            else if (op.equals("=~")) {
                                try {
                                    ok = Pattern.compile(want).matcher(String.valueOf(got)).find();
                                } catch (Exception ignored) {
                                }
                            } else if (op.equals(">")) {
                                try {
                                    ok = Double.parseDouble(String.valueOf(got)) > Double.parseDouble(want);
                                } catch (Exception ignored) {
                                }
                            } else if (op.equals("<")) {
                                try {
                                    ok = Double.parseDouble(String.valueOf(got)) < Double.parseDouble(want);
                                } catch (Exception ignored) {
                                }
                            }
                            if (ok) next.add(item);
                        }
                    }
                }
                cur = next;
                continue;
            }
            i++;
        }
        if (cur.isEmpty()) return null;
        if (cur.size() == 1) return cur.get(0);
        return cur;
    }

    static Object deepField(Object item, String path) {
        Object o = item;
        for (String k : path.split("\\.")) {
            if (o == null) return null;
            o = field(o, k);
        }
        return o;
    }

    static List<Object> objValues(Object node) {
        List<Object> out = new ArrayList<Object>();
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            while (it.hasNext()) out.add(o.opt(it.next()));
        } else if (node instanceof Map) {
            out.addAll(((Map<?, ?>) node).values());
        }
        return out;
    }

    static void walkDesc(Object node, String name, List<Object> next) {
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walkDesc(a.opt(i), name, next);
            return;
        }
        if (node instanceof List) {
            for (Object o : (List<?>) node) walkDesc(o, name, next);
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = o.opt(k);
                if (name.equals("*") || k.equals(name)) next.add(v);
                walkDesc(v, name, next);
            }
            return;
        }
        if (node instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) node).entrySet()) {
                if (name.equals("*") || name.equals(String.valueOf(e.getKey()))) next.add(e.getValue());
                walkDesc(e.getValue(), name, next);
            }
        }
    }

    /* ------------------------------------------------------------------ *
     *  HTML 规则
     * ------------------------------------------------------------------ */
    static String[] splitIndexSuffix(String step) {
        Matcher m = Pattern.compile("^(.*?)\\[([^\\]]*)\\]$").matcher(step);
        if (m.find() && !step.startsWith("@")) {
            String base = m.group(1), spec = m.group(2).trim();
            if (spec.matches("^!?[-\\d:,\\s]+$")) return new String[]{base, spec};
        }
        m = Pattern.compile("^(.*?)([.!:])(-?\\d+(?::-?\\d+)*)$").matcher(step);
        if (m.find()) return new String[]{m.group(1), ("!".equals(m.group(2)) ? "!" : "") + m.group(3)};
        return new String[]{step, null};
    }

    static List<Element> applyIndex(List<Element> nodes, String spec) {
        if (spec == null) return nodes;
        boolean exclude = spec.startsWith("!");
        String body = exclude ? spec.substring(1) : spec;
        List<Integer> picked = new ArrayList<Integer>();
        int len = nodes.size();
        if (body.contains(":")) {
            String[] parts = body.split(":", -1);
            int a = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0].trim()) : 0;
            Integer b = parts.length > 1 && !parts[1].isEmpty() ? Integer.parseInt(parts[1].trim()) : null;
            int c = parts.length > 2 && !parts[2].isEmpty() ? Integer.parseInt(parts[2].trim()) : 1;
            int start = a < 0 ? len + a : a;
            int end = b == null ? len : (b < 0 ? len + b : b);
            for (int i = start; i < end && i < len; i += c) if (i >= 0) picked.add(i);
        } else if (body.contains(",")) {
            for (String x : body.split(",")) {
                try {
                    int n = Integer.parseInt(x.trim());
                    int i = n < 0 ? len + n : n;
                    if (i >= 0 && i < len) picked.add(i);
                } catch (Exception ignored) {
                }
            }
        } else {
            try {
                int n = Integer.parseInt(body.trim());
                int i = n < 0 ? len + n : n;
                if (i >= 0 && i < len) picked.add(i);
            } catch (Exception ignored) {
            }
        }
        List<Element> out = new ArrayList<Element>();
        if (exclude) {
            for (int i = 0; i < len; i++) if (!picked.contains(i)) out.add(nodes.get(i));
        } else {
            for (int i : picked) out.add(nodes.get(i));
        }
        return out;
    }

    static String toSelector(String step) {
        String s = step.trim();
        if (s.startsWith("@css:")) s = s.substring(5).trim();
        if (s.startsWith("class.") || s.startsWith("class=")) return "." + s.substring(6);
        if (s.startsWith("id.") || s.startsWith("id=")) return "#" + s.substring(3);
        if (s.startsWith("tag.") || s.startsWith("tag=")) return s.substring(4);
        return s;
    }

    static boolean isAttrName(String tok) {
        String t = tok == null ? "" : tok.trim();
        if (t.isEmpty()) return false;
        if (TEXT_STEPS.contains(t)) return true;
        if (t.matches("(?i)^(href|src|onclick|value|title|alt|content|datetime|data-[\\w-]+|action|poster)$")) return true;
        return t.matches("^[a-zA-Z][\\w:-]*$") && !TAG_NAMES.contains(t.toLowerCase());
    }

    static List<Element> scopedFind(Ctx ctx, String sel, List<Element> from) {
        List<Element> found = new ArrayList<Element>();
        List<Element> bases = from != null ? from : (ctx.scope != null && !ctx.scope.isEmpty() ? ctx.scope : null);
        try {
            if (bases == null) {
                Elements es = ctx.doc.select(sel);
                for (Element e : es) found.add(e);
                return found;
            }
            for (Element el : bases) {
                try {
                    if (el.is(sel)) found.add(el);
                } catch (Exception ignored) {
                }
                try {
                    for (Element e : el.select(sel)) found.add(e);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return found;
    }

    static Object nodesToValue(List<Element> nodes, String step, String baseUrl) {
        String name = step.startsWith("@") ? step.substring(1) : step;
        name = name.trim();
        List<Object> out = new ArrayList<Object>();
        for (Element el : nodes) {
            try {
                if (name.equals("text") || name.equals("ownText") || name.equals("all")) {
                    out.add(el.text().trim());
                } else if (name.equals("textNodes")) {
                    out.add(el.text());
                } else if (name.equals("html") || name.equals("innerHtml")) {
                    out.add(el.html());
                } else if (name.equals("outerHtml")) {
                    out.add(el.outerHtml());
                } else {
                    String v = el.hasAttr(name) ? el.attr(name) : null;
                    if (v == null && name.equals("src")) {
                        for (String a : new String[]{"data-src", "data-original", "data-lazy-src"}) {
                            if (el.hasAttr(a)) {
                                v = el.attr(a);
                                break;
                            }
                        }
                    }
                    if (v == null) continue;
                    if ((name.equals("href") || name.equals("src")) && !v.matches("^[a-zA-Z][\\w+.-]*:.*")) {
                        v = Net.abs(v, baseUrl == null || baseUrl.isEmpty() ? "http://localhost" : baseUrl);
                    }
                    out.add(v);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static Object currentValue(Ctx ctx, Object val, List<Element> nodes) {
        if (val != null) return val;
        if (nodes != null && !nodes.isEmpty()) return nodesToValue(nodes, "text", ctx.baseUrl);
        if (ctx.scope != null && !ctx.scope.isEmpty()) return nodesToValue(ctx.scope, "text", ctx.baseUrl);
        return ctx.result;
    }

    static Object evalHtmlRule(String rule, Ctx ctx) {
        if (ctx.doc == null) return "";
        List<String> steps = splitTop(rule, "@");
        List<Element> nodes = null;
        Object val = null;
        for (int i = 0; i < steps.size(); i++) {
            String raw = steps.get(i).trim();
            if (raw.isEmpty()) continue;
            String regexSpec = null;
            int hashAt = raw.indexOf("##");
            if (hashAt >= 0) {
                regexSpec = raw.substring(hashAt + 2);
                raw = raw.substring(0, hashAt).trim();
            }
            boolean isLast = i == steps.size() - 1;
            boolean fromAt = i > 0 && steps.get(i - 1).trim().isEmpty();

            if (raw.startsWith("js:")) {
                Object cur = currentValue(ctx, val, nodes);
                if (cur instanceof List && ((List<?>) cur).size() == 1) cur = ((List<?>) cur).get(0);
                Ctx sub = ctx.dup();
                sub.result = cur;
                try {
                    val = evalJS(raw.substring(3), sub);
                } catch (Throwable t) {
                    return "";
                }
                if (regexSpec != null) val = applyRegexSpec(val, regexSpec);
                nodes = null;
                continue;
            }

            if (raw.startsWith("css:")) {
                List<Element> next = scopedFind(ctx, raw.substring(4).trim(), nodes);
                if (!next.isEmpty()) nodes = next;
                else if (nodes == null && ctx.scope != null && !ctx.scope.isEmpty()) nodes = ctx.scope;
                if (nodes == null) return "";
                continue;
            }

            if (fromAt || TEXT_STEPS.contains(raw) || (isLast && isAttrName(raw))) {
                List<Element> list = nodes != null ? nodes : (ctx.scope != null && !ctx.scope.isEmpty() ? ctx.scope : null);
                if (list == null) return "";
                Object v = nodesToValue(list, raw, ctx.baseUrl);
                if (regexSpec != null) v = applyRegexSpec(v, regexSpec);
                if (isLast) return v;
                val = v;
                nodes = null;
                continue;
            }

            String[] si = splitIndexSuffix(raw);
            List<Element> found = scopedFind(ctx, toSelector(si[0]), nodes);
            if (si[1] != null) found = applyIndex(found, si[1]);
            nodes = found;
            val = null;
            if (nodes.isEmpty()) return "";
        }
        if (val != null) return val;
        if (nodes == null) return "";
        if (nodes.size() == 1) return nodes.get(0);
        return nodes;
    }

    /* ------------------------------------------------------------------ *
     *  正则
     * ------------------------------------------------------------------ */
    /** 书源里的替换串用 JS 语义（$1 反向引用、$& 整体匹配），不能当字面量处理。
     *  Java 的 $& 是 $0，非法引用会抛 IllegalArgumentException，这时才退回字面量。 */
    static String replaceAll(Pattern p, String in, String repl) {
        String r = repl.replace("$&", "$0").replace("\\n", "\n");
        try {
            return p.matcher(in).replaceAll(r);
        } catch (IllegalArgumentException e) {
            return p.matcher(in).replaceAll(Matcher.quoteReplacement(r));
        }
    }

    static Object applyRegex(Object val, String rule) {
        List<String> parts = splitTop(rule, "##");
        if (parts.size() < 2) return val;
        String pat = parts.get(1);
        String repl = parts.size() > 2 ? parts.get(2) : "";
        if (pat.isEmpty()) return val;
        Pattern p;
        try {
            p = Pattern.compile(pat);
        } catch (Exception e) {
            return val;
        }
        if (val instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object o : (List<?>) val) out.add(replaceAll(p, String.valueOf(o), repl));
            return out;
        }
        return replaceAll(p, val == null ? "" : String.valueOf(val), repl);
    }

    static Object applyRegexSpec(Object val, String spec) {
        String[] seg = spec.split("##", -1);
        String pat = seg.length > 0 ? seg[0] : "";
        String rep = seg.length > 1 ? seg[1] : "";
        if (pat.isEmpty()) return val;
        Pattern p;
        try {
            p = Pattern.compile(pat);
        } catch (Exception e) {
            return val;
        }
        if (val instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Object o : (List<?>) val) out.add(replaceAll(p, o == null ? "" : String.valueOf(o), rep));
            return out;
        }
        return replaceAll(p, val == null ? "" : String.valueOf(val), rep);
    }

    /* ------------------------------------------------------------------ *
     *  主入口：对某段内容执行一条规则
     * ------------------------------------------------------------------ */
    public static Object evalRule(String ruleRaw, Object input, Ctx ctx) {
        if (ruleRaw == null) return "";
        String rule = ruleRaw.trim();
        if (rule.isEmpty()) return "";

        List<String> ors = splitTop(rule, "||");
        if (ors.size() > 1) {
            for (String part : ors) {
                Object v = evalRule(part, input, ctx);
                if (!isBlank(v)) return v;
            }
            return "";
        }
        List<String> ands = splitTop(rule, "&&");
        if (ands.size() > 1) {
            List<Object> all = new ArrayList<Object>();
            for (String part : ands) all.addAll(asList(evalRule(part, input, ctx)));
            return all;
        }
        List<String> inters = splitTop(rule, "%%");
        if (inters.size() > 1) {
            List<List<Object>> lists = new ArrayList<List<Object>>();
            int max = 0;
            for (String p : inters) {
                List<Object> l = asList(evalRule(p, input, ctx));
                lists.add(l);
                max = Math.max(max, l.size());
            }
            List<Object> out = new ArrayList<Object>();
            for (int i = 0; i < max; i++) for (List<Object> l : lists) if (i < l.size()) out.add(l.get(i));
            return out;
        }

        rule = rule.replaceAll("@put:\\{[\\s\\S]*?\\}", "").trim();

        String jsCode = null;
        Matcher jsTag = Pattern.compile("<js>([\\s\\S]*?)</js>").matcher(rule);
        if (jsTag.find()) {
            jsCode = jsTag.group(1);
            rule = rule.replace(jsTag.group(0), "").trim();
        } else if (rule.startsWith("@js:")) {
            jsCode = rule.substring(4);
            rule = "";
        }

        boolean htmlRule = ctx.doc != null && !rule.startsWith("$") && !rule.startsWith("@json:") && !rule.contains("{{");
        String regexPart = "";
        if (!htmlRule && rule.contains("##")) {
            int idx = rule.indexOf("##");
            regexPart = rule.substring(idx);
            rule = rule.substring(0, idx).trim();
        }

        Object val;
        boolean jsonMode = ctx.result instanceof JSONObject || ctx.result instanceof JSONArray
                || ctx.result instanceof Map || ctx.result instanceof List;
        if (rule.contains("{{") && !rule.contains("@")) {
            val = renderTemplate(rule, ctx);
        } else if (rule.isEmpty()) {
            val = ctx.result;
        } else if (rule.startsWith("$") || rule.startsWith("@json:")) {
            val = jsonPath(ctx.result, rule);
        } else if (jsonMode && rule.matches("^[\\w$.*\\[\\]\\-]+$") && !TEXT_STEPS.contains(rule)) {
            val = jsonPath(ctx.result, "$." + (rule.startsWith(".") ? rule.substring(1) : rule));
        } else if (ctx.doc != null) {
            val = evalHtmlRule(rule, ctx);
        } else {
            val = "";
        }

        if (!regexPart.isEmpty()) val = applyRegex(val, regexPart);

        if (jsCode != null) {
            Ctx sub = ctx.dup();
            sub.result = val;
            try {
                val = evalJS(jsCode, sub);
            } catch (Throwable t) {
                val = "";
            }
        }
        return val;
    }

    /* ------------------------------------------------------------------ *
     *  {{ }} 模板
     * ------------------------------------------------------------------ */
    public static String renderTemplate(String s, Ctx ctx) {
        if (s == null || !s.contains("{{")) return s;
        Matcher m = Pattern.compile("\\{\\{([\\s\\S]*?)\\}\\}").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String code = m.group(1).trim();
            Object val = "";
            try {
                val = code.startsWith("$") ? jsonPath(ctx.result, code) : evalJS(code, ctx);
            } catch (Throwable ignored) {
            }
            String rep;
            if (val == null || val == Undefined.instance) rep = "";
            else if (val instanceof Map || val instanceof List || val instanceof JSONObject || val instanceof JSONArray) rep = jsonStr(val);
            else rep = numStr(val);   // JS 数字是 double，{{1+1}} 必须是 "2" 不是 "2.0"
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /* ------------------------------------------------------------------ *
     *  Rhino JS
     * ------------------------------------------------------------------ */
    interface Fn {
        Object call(Object[] args);
    }

    static BaseFunction fn(final Fn f) {
        return new BaseFunction() {
            @Override
            public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                return f.call(args);
            }
        };
    }

    static String arg(Object[] a, int i) {
        if (a == null || i >= a.length || a[i] == null || a[i] == Undefined.instance) return "";
        return String.valueOf(a[i]);
    }

    static Object jsNull() {
        return null;
    }

    static Scriptable newScope(Context cx) {
        Scriptable scope = cx.initStandardObjects();
        cx.setOptimizationLevel(-1);
        cx.setLanguageVersion(Context.VERSION_ES6);
        return scope;
    }

    static Object toJs(Context cx, Scriptable scope, Object v) {
        if (v == null) return null;
        if (v instanceof JSONObject) {
            NativeObject o = new NativeObject();
            o.setParentScope(scope);
            o.setPrototype(ScriptableObject.getObjectPrototype(scope));
            JSONObject j = (JSONObject) v;
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                o.defineProperty(k, toJs(cx, scope, j.opt(k)), ScriptableObject.READONLY);
            }
            return o;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            NativeArray arr = new NativeArray(a.length());
            arr.setParentScope(scope);
            arr.setPrototype(ScriptableObject.getArrayPrototype(scope));
            for (int i = 0; i < a.length(); i++) arr.put(i, arr, toJs(cx, scope, a.opt(i)));
            return arr;
        }
        if (v instanceof Map) {
            NativeObject o = new NativeObject();
            o.setParentScope(scope);
            o.setPrototype(ScriptableObject.getObjectPrototype(scope));
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                o.defineProperty(String.valueOf(e.getKey()), toJs(cx, scope, e.getValue()), ScriptableObject.READONLY);
            }
            return o;
        }
        if (v instanceof List) {
            List<?> l = (List<?>) v;
            NativeArray arr = new NativeArray(l.size());
            arr.setParentScope(scope);
            arr.setPrototype(ScriptableObject.getArrayPrototype(scope));
            for (int i = 0; i < l.size(); i++) arr.put(i, arr, toJs(cx, scope, l.get(i)));
            return arr;
        }
        if (v instanceof Element) return ((Element) v).outerHtml();
        return v;
    }

    static Object fromJs(Object r) {
        if (r == null || r == Undefined.instance || r == Scriptable.NOT_FOUND) return "";
        if (r instanceof Wrapper) r = ((Wrapper) r).unwrap();
        if (r instanceof NativeArray) {
            NativeArray a = (NativeArray) r;
            List<Object> out = new ArrayList<Object>();
            for (int i = 0; i < a.size(); i++) out.add(fromJs(a.get(i, a)));
            return out;
        }
        if (r instanceof NativeObject) {
            NativeObject o = (NativeObject) r;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            for (Object id : o.getIds()) {
                if (id instanceof String) m.put((String) id, fromJs(o.get((String) id, o)));
            }
            return m;
        }
        if (r instanceof CharSequence) return r.toString();
        if (r instanceof Number || r instanceof Boolean) return r;
        return String.valueOf(r);
    }

    static Scriptable javaObject(final Context cx, final Scriptable scope, final Ctx ctx) {
        NativeObject o = new NativeObject();
        o.setParentScope(scope);
        o.setPrototype(ScriptableObject.getObjectPrototype(scope));
        final String base = ctx.baseUrl == null ? "" : ctx.baseUrl;
        o.defineProperty("ajax", fn(new Fn() {
            public Object call(Object[] a) {
                return httpSync(Net.abs(arg(a, 0), base));
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("get", fn(new Fn() {
            public Object call(Object[] a) {
                return httpSync(Net.abs(arg(a, 0), base));
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("post", fn(new Fn() {
            public Object call(Object[] a) {
                Map<String, String> h = new HashMap<String, String>();
                h.put("Content-Type", "application/x-www-form-urlencoded");
                Net.Resp r = Net.request(Net.abs(arg(a, 0), base), "POST", arg(a, 1), h, 15000);
                return r.body;
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("base64Encode", fn(new Fn() {
            public Object call(Object[] a) {
                return b64(arg(a, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("base64EncodeToString", fn(new Fn() {
            public Object call(Object[] a) {
                return b64(arg(a, 0).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("base64Decode", fn(new Fn() {
            public Object call(Object[] a) {
                return unb64(arg(a, 0));
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("base64DecodeToString", fn(new Fn() {
            public Object call(Object[] a) {
                return unb64(arg(a, 0));
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("md5Encode", fn(new Fn() {
            public Object call(Object[] a) {
                return md5(arg(a, 0));
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("md5Encode16", fn(new Fn() {
            public Object call(Object[] a) {
                String h = md5(arg(a, 0));
                return h.length() >= 24 ? h.substring(8, 24) : h;
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("timeFormat", fn(new Fn() {
            public Object call(Object[] a) {
                try {
                    long t = (long) Double.parseDouble(arg(a, 0));
                    if (t < 100000000000L) t *= 1000;
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
                    return f.format(new java.util.Date(t));
                } catch (Exception e) {
                    return "";
                }
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("put", fn(new Fn() {
            public Object call(Object[] a) {
                ctx.vars.put(arg(a, 0), fromJs(a.length > 1 ? a[1] : ""));
                return fromJs(a.length > 1 ? a[1] : "");
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("getString", fn(new Fn() {
            public Object call(Object[] a) {
                Object v = ctx.vars.get(arg(a, 0));
                return v == null ? "" : v;
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("getObject", o.get("getString", o), ScriptableObject.READONLY);
        o.defineProperty("log", fn(new Fn() {
            public Object call(Object[] a) {
                return "";
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("logType", o.get("log", o), ScriptableObject.READONLY);
        o.defineProperty("toast", o.get("log", o), ScriptableObject.READONLY);
        o.defineProperty("longToast", o.get("log", o), ScriptableObject.READONLY);
        o.defineProperty("setContent", o.get("log", o), ScriptableObject.READONLY);
        o.defineProperty("htmlFormat", fn(new Fn() {
            public Object call(Object[] a) {
                return arg(a, 0);
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("encodeURI", fn(new Fn() {
            public Object call(Object[] a) {
                try {
                    return URLEncoder.encode(arg(a, 0), "UTF-8").replace("+", "%20");
                } catch (Exception e) {
                    return arg(a, 0);
                }
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("getCookie", fn(new Fn() {
            public Object call(Object[] a) {
                return "";
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("randomUUID", fn(new Fn() {
            public Object call(Object[] a) {
                return java.util.UUID.randomUUID().toString();
            }
        }), ScriptableObject.READONLY);
        o.defineProperty("hexDecodeToString", fn(new Fn() {
            public Object call(Object[] a) {
                try {
                    String s = arg(a, 0);
                    byte[] b = new byte[s.length() / 2];
                    for (int i = 0; i < b.length; i++) {
                        b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
                    }
                    return new String(b, "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }), ScriptableObject.READONLY);
        return o;
    }

    static String httpSync(String url) {
        if (url == null || url.isEmpty()) return "";
        Net.Resp r = Net.get(url, null, 15000);
        return r.body == null ? "" : r.body;
    }

    static String b64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    static String unb64(String s) {
        try {
            return new String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** $ 代理：JS 里写 $.data.list 等价于 jsonPath */
    static Scriptable dollarProxy(final Context cx, final Scriptable scope, final Object result) {
        NativeObject p = new NativeObject() {
            @Override
            public Object get(String name, Scriptable start) {
                if ("toString".equals(name) || "valueOf".equals(name)) return "";
                return toJs(cx, scope, jsonPath(result, "$." + name));
            }

            @Override
            public boolean has(String name, Scriptable start) {
                return true;
            }
        };
        p.setParentScope(scope);
        p.setPrototype(ScriptableObject.getObjectPrototype(scope));
        return p;
    }

    public static Object evalJS(String code, Ctx ctx) {
        Context cx = Context.enter();
        try {
            Scriptable scope = newScope(cx);
            ScriptableObject.putProperty(scope, "java", javaObject(cx, scope, ctx));
            ScriptableObject.putProperty(scope, "result", toJs(cx, scope, ctx.result));
            ScriptableObject.putProperty(scope, "baseUrl", ctx.baseUrl == null ? "" : ctx.baseUrl);
            ScriptableObject.putProperty(scope, "baseURL", ctx.baseUrl == null ? "" : ctx.baseUrl);
            ScriptableObject.putProperty(scope, "key", ctx.key == null ? "" : ctx.key);
            ScriptableObject.putProperty(scope, "page", ctx.page);
            ScriptableObject.putProperty(scope, "source", toJs(cx, scope, ctx.source));
            ScriptableObject.putProperty(scope, "book", null);
            ScriptableObject.putProperty(scope, "title", "");
            ScriptableObject.putProperty(scope, "nextChapterUrl", "");
            ScriptableObject.putProperty(scope, "$", dollarProxy(cx, scope, ctx.result));
            ScriptableObject.putProperty(scope, "cookie", new NativeObject() {
                {
                    setParentScope(scope);
                    setPrototype(ScriptableObject.getObjectPrototype(scope));
                }
            });
            for (Map.Entry<String, Object> e : ctx.vars.entrySet()) {
                ScriptableObject.putProperty(scope, e.getKey(), toJs(cx, scope, e.getValue()));
            }
            Object r;
            try {
                r = cx.evaluateString(scope, "(function(){\"use strict\";return (" + code + ");})()", "rule", 1, null);
            } catch (Throwable t) {
                r = cx.evaluateString(scope, "(function(){\"use strict\";\n" + code + "\n})()", "rule", 1, null);
            }
            return fromJs(r);
        } catch (Throwable t) {
            return "";
        } finally {
            Context.exit();
        }
    }

    /* ------------------------------------------------------------------ *
     *  源/URL 解析与抓取
     * ------------------------------------------------------------------ */
    public static Map<String, String> parseHeader(JSONObject src) {
        Map<String, String> out = new HashMap<String, String>();
        if (src == null) return out;
        Object h = src.opt("header");
        if (h == null) return out;
        JSONObject obj = null;
        if (h instanceof JSONObject) obj = (JSONObject) h;
        else {
            String s = String.valueOf(h).trim();
            if (s.isEmpty()) return out;
            try {
                obj = new JSONObject(s);
            } catch (Throwable t) {
                try {
                    obj = new JSONObject(Lenient.json(s));
                } catch (Throwable t2) {
                    return out;
                }
            }
        }
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equalsIgnoreCase("cookie")) continue;
            out.put(k, obj.optString(k, ""));
        }
        String ck = obj.optString("Cookie", obj.optString("cookie", ""));
        if (!ck.isEmpty()) out.put("Cookie", ck);
        return out;
    }

    static class UrlRule {
        String url = "";
        JSONObject opts = new JSONObject();
    }

    static UrlRule parseUrlRule(String raw, Ctx ctx) {
        UrlRule out = new UrlRule();
        String s = raw == null ? "" : raw.trim();
        Matcher m = Pattern.compile(",\\s*(\\{[\\s\\S]*\\})\\s*$").matcher(s);
        if (m.find()) {
            String json = m.group(1);
            JSONObject parsed = null;
            try {
                parsed = new JSONObject(json);
            } catch (Throwable t) {
                Object o = evalJS("return (" + json + ")", ctx);
                if (o instanceof Map) parsed = Lenient.fromMap((Map<?, ?>) o);
            }
            if (parsed != null) {
                out.opts = parsed;
                s = s.substring(0, m.start()).trim();
            }
        }
        List<String> lines = new ArrayList<String>();
        for (String x : s.split("\n")) {
            String t = x.trim();
            if (!t.isEmpty()) lines.add(t);
        }
        while (lines.size() > 1 && lines.get(0).startsWith("{{") && lines.get(0).endsWith("}}")) {
            try {
                renderTemplate(lines.get(0), ctx);
            } catch (Throwable ignored) {
            }
            lines.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (String t : lines) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(t);
        }
        s = sb.toString().trim();

        if (s.startsWith("@js:") || s.startsWith("<js>")) {
            String code = s.startsWith("@js:") ? s.substring(4) : s.replaceAll("</?js>", "");
            Object v = evalJS(code, ctx);
            if (v instanceof Map) {
                Map<?, ?> mm = (Map<?, ?>) v;
                Object u = mm.get("url") != null ? mm.get("url") : mm.get("URL");
                out.url = u == null ? "" : String.valueOf(u);
                out.opts = Lenient.fromMap(mm);
            } else {
                out.url = v == null ? "" : String.valueOf(v);
            }
            return out;
        }

        if (ctx.key != null && !ctx.key.isEmpty()) {
            s = s.replaceAll("\\{\\{\\s*key\\s*\\}\\}", Matcher.quoteReplacement(urlEnc(ctx.key)));
        }
        out.url = renderTemplate(s, ctx).trim();
        return out;
    }

    static String urlEnc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    static boolean hasHeader(Map<String, String> h, String k) {
        for (String x : h.keySet()) if (x.equalsIgnoreCase(k)) return true;
        return false;
    }

    public static Net.Resp fetchRule(String rawRule, Ctx ctx, JSONObject src, Map<String, String> extra) {
        UrlRule ur = parseUrlRule(rawRule, ctx);
        String baseUrl = ctx.baseUrl == null || ctx.baseUrl.isEmpty() ? src.optString("bookSourceUrl") : ctx.baseUrl;
        String url = Net.abs(ur.url, baseUrl);
        Net.Resp r = new Net.Resp();
        if (url.isEmpty()) {
            r.error = "空URL";
            return r;
        }
        Map<String, String> headers = parseHeader(src);
        if (extra != null) headers.putAll(extra);
        JSONObject oh = ur.opts.optJSONObject("headers");
        if (oh != null) {
            Iterator<String> it = oh.keys();
            while (it.hasNext()) {
                String k = it.next();
                headers.put(k, oh.optString(k, ""));
            }
        }
        String method = ur.opts.optString("method", "GET").toUpperCase();
        int timeout = ur.opts.optInt("timeout", 0) > 0 ? ur.opts.optInt("timeout") * 1000 : 15000;
        String charset = ur.opts.optString("charset", "");
        String body = null;
        if (method.equals("POST")) {
            body = ur.opts.has("body") ? renderTemplate(ur.opts.optString("body", ""), ctx) : "";
            if (!hasHeader(headers, "Content-Type")) headers.put("Content-Type", "application/x-www-form-urlencoded");
        }
        r = Net.request(url, method, body, headers, timeout);
        if (charset.matches("(?i)gbk|gb2312|gb18030")) r.body = Net.decode(r.raw, "charset=" + charset);

        for (int hop = 0; hop < 2; hop++) {
            String b = r.body == null ? "" : r.body;
            if (b.length() > 4000) break;
            String target = "";
            Matcher jm = Pattern.compile("window\\.location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']").matcher(b);
            Matcher mm = Pattern.compile("http-equiv=[\"']?refresh[\"']?[^>]*content=[\"'][^\"']*url=([^\"';]+)",
                    Pattern.CASE_INSENSITIVE).matcher(b);
            if (jm.find()) target = jm.group(1);
            else if (mm.find()) target = mm.group(1);
            if (target.isEmpty()) break;
            String next = Net.abs(target, r.url);
            if (next.isEmpty() || next.equals(r.url)) break;
            r = Net.request(next, "GET", null, headers, timeout);
            if (charset.matches("(?i)gbk|gb2312|gb18030")) r.body = Net.decode(r.raw, "charset=" + charset);
        }
        return r;
    }

    /* ------------------------------------------------------------------ *
     *  页面载入 / 正文清洗
     * ------------------------------------------------------------------ */
    static void loadInto(Ctx ctx, String body, String ctype, String url) {
        String txt = body == null ? "" : body;
        boolean isJson = txt.trim().matches("^[\\[{][\\s\\S]*") || (ctype != null && ctype.toLowerCase().contains("json"));
        if (isJson) {
            try {
                String t = txt.trim();
                if (t.startsWith("[")) ctx.result = new JSONArray(t);
                else ctx.result = new JSONObject(t);
                ctx.doc = null;
                ctx.baseUrl = url;
                return;
            } catch (Throwable ignored) {
            }
        }
        ctx.doc = Jsoup.parse(txt, url == null ? "" : url);
        ctx.result = txt;
        ctx.baseUrl = url;
    }

    public static String cleanText(String html) {
        if (html == null || html.isEmpty()) return "";
        String s = html;
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n\n");
        s = s.replaceAll("(?is)<script[\\s\\S]*?</script>", "").replaceAll("(?is)<style[\\s\\S]*?</style>", "");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&ldquo;", "“").replace("&rdquo;", "”");
        s = s.replaceAll("[ \\t\\u00a0]+", " ").replaceAll("\\s*\\n\\s*", "\n");
        return s.trim();
    }

    /* ------------------------------------------------------------------ *
     *  搜索
     * ------------------------------------------------------------------ */
    public static List<JSONObject> search(JSONObject src, String key, int page) throws Exception {
        Ctx c0 = new Ctx();
        c0.baseUrl = src.optString("bookSourceUrl");
        c0.key = key;
        c0.page = page <= 0 ? 1 : page;
        c0.source = src;
        Net.Resp res = fetchRule(src.optString("searchUrl"), c0, src, null);
        if (!res.error.isEmpty()) throw new Exception(res.error);
        if (res.code >= 400) throw new Exception("HTTP " + res.code);

        Ctx ctx = new Ctx();
        loadInto(ctx, res.body, res.ctype, res.url);
        ctx.key = key;
        ctx.page = c0.page;
        ctx.source = src;

        JSONObject rs = src.optJSONObject("ruleSearch");
        if (rs == null) rs = new JSONObject();
        String listRule = rs.optString("bookList", "").trim();
        List<Object> items = new ArrayList<Object>();
        if (!listRule.isEmpty()) {
            Object raw = evalRule(listRule, res.body, ctx);
            if (raw instanceof String && ctx.doc != null) {
                try {
                    for (Element e : ctx.doc.select((String) raw)) items.add(e);
                } catch (Exception ignored) {
                }
            } else {
                items.addAll(asList(raw));
            }
        } else {
            items.add(ctx.result);
        }

        List<JSONObject> books = new ArrayList<JSONObject>();
        int lim = Math.min(items.size(), 40);
        for (int n = 0; n < lim; n++) {
            Object item = items.get(n);
            if (item == null) continue;
            boolean isEl = item instanceof Element;
            Ctx ictx = new Ctx();
            ictx.doc = isEl ? ctx.doc : null;
            if (isEl) {
                ictx.scope = new ArrayList<Element>();
                ictx.scope.add((Element) item);
                ictx.result = ((Element) item).outerHtml();
            } else {
                ictx.result = item;
            }
            ictx.baseUrl = ctx.baseUrl;
            ictx.key = key;
            ictx.page = c0.page;
            ictx.source = src;
            try {
                String name = str(get(rs, "name", ictx));
                if (name.isEmpty()) continue;
                JSONObject bk = new JSONObject();
                bk.put("name", name);
                bk.put("author", str(get(rs, "author", ictx)));
                bk.put("kind", str(get(rs, "kind", ictx)));
                bk.put("intro", str(get(rs, "intro", ictx)));
                bk.put("coverUrl", Net.abs(str(get(rs, "coverUrl", ictx)), ictx.baseUrl));
                String burl = Net.abs(str(get(rs, "bookUrl", ictx)), ictx.baseUrl);
                bk.put("bookUrl", burl);
                bk.put("url", burl);
                bk.put("lastChapter", str(get(rs, "lastChapter", ictx)));
                bk.put("wordCount", str(get(rs, "wordCount", ictx)));
                bk.put("origin", src.optString("bookSourceUrl"));
                bk.put("originName", src.optString("bookSourceName"));
                books.add(bk);
            } catch (Throwable ignored) {
            }
        }
        return books;
    }

    static Object get(JSONObject rs, String k, Ctx ctx) {
        String r = rs.optString(k, "").trim();
        if (r.isEmpty()) return "";
        try {
            return evalRule(r, ctx.result, ctx);
        } catch (Throwable t) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ *
     *  详情 / 目录 / 正文
     * ------------------------------------------------------------------ */
    public static JSONObject info(JSONObject src, String bookUrl) throws Exception {
        JSONObject rs = src.optJSONObject("ruleBookInfo");
        if (rs == null) rs = new JSONObject();
        Ctx ctx = new Ctx();
        ctx.source = src;
        ctx.baseUrl = src.optString("bookSourceUrl");
        Net.Resp res = fetchRule(bookUrl, ctx, src, null);
        if (!res.error.isEmpty()) throw new Exception(res.error);
        if (res.code >= 400) throw new Exception("HTTP " + res.code);
        Ctx c = new Ctx();
        loadInto(c, res.body, res.ctype, res.url);
        c.source = src;

        String initRule = rs.optString("init", "").trim();
        if (!initRule.isEmpty()) {
            Object v = evalRule(initRule, c.result, c);
            if (v instanceof String && !((String) v).trim().isEmpty()) {
                String frag = (String) v;
                c.doc = Jsoup.parse(frag, c.baseUrl);
                c.result = frag;
                Elements kids = c.doc.body().children();
                if (!kids.isEmpty()) {
                    c.scope = new ArrayList<Element>();
                    c.scope.add(kids.first());
                }
            } else if (v instanceof List && !((List<?>) v).isEmpty()) {
                Object first = ((List<?>) v).get(0);
                if (first instanceof Element) {
                    c.scope = new ArrayList<Element>();
                    c.scope.add((Element) first);
                } else {
                    c.result = first;
                    c.doc = null;
                }
            } else if (v instanceof Element) {
                c.scope = new ArrayList<Element>();
                c.scope.add((Element) v);
            } else if (v instanceof Map) {
                c.result = v;
                c.doc = null;
            }
        }
        JSONObject out = new JSONObject();
        out.put("name", str(get(rs, "name", c)));
        out.put("author", str(get(rs, "author", c)));
        out.put("kind", str(get(rs, "kind", c)));
        out.put("intro", str(get(rs, "intro", c)));
        out.put("coverUrl", Net.abs(str(get(rs, "coverUrl", c)), c.baseUrl));
        out.put("lastChapter", str(get(rs, "lastChapter", c)));
        out.put("wordCount", str(get(rs, "wordCount", c)));
        String toc = str(get(rs, "tocUrl", c));
        out.put("tocUrl", toc.isEmpty() ? Net.abs(bookUrl, c.baseUrl) : Net.abs(toc, c.baseUrl));
        out.put("finalUrl", c.baseUrl);
        return out;
    }

    public static List<JSONObject> toc(JSONObject src, String tocUrl) throws Exception {
        JSONObject rt = src.optJSONObject("ruleToc");
        if (rt == null) rt = new JSONObject();
        Ctx c0 = new Ctx();
        c0.source = src;
        c0.baseUrl = src.optString("bookSourceUrl");
        Net.Resp res = fetchRule(tocUrl, c0, src, null);
        if (!res.error.isEmpty()) throw new Exception(res.error);
        if (res.code >= 400) throw new Exception("HTTP " + res.code);
        Ctx ctx = new Ctx();
        loadInto(ctx, res.body, res.ctype, res.url);
        ctx.source = src;

        String listRule = rt.optString("chapterList", "").trim();
        List<Object> items = new ArrayList<Object>();
        if (!listRule.isEmpty()) {
            Object raw = evalRule(listRule, ctx.result, ctx);
            if (raw instanceof String && ctx.doc != null) {
                try {
                    for (Element e : ctx.doc.select((String) raw)) items.add(e);
                } catch (Exception ignored) {
                }
            } else {
                items.addAll(asList(raw));
            }
        }

        List<JSONObject> chapters = new ArrayList<JSONObject>();
        for (Object item : items) {
            if (item == null) continue;
            boolean isEl = item instanceof Element;
            Ctx ictx = new Ctx();
            ictx.doc = isEl ? ctx.doc : null;
            if (isEl) {
                ictx.scope = new ArrayList<Element>();
                ictx.scope.add((Element) item);
                ictx.result = ((Element) item).outerHtml();
            } else {
                ictx.result = item;
            }
            ictx.baseUrl = ctx.baseUrl;
            ictx.source = src;
            String name = str(get(rt, "chapterName", ictx));
            if (name.isEmpty() && isEl) name = str(((Element) item).text());
            if (name.isEmpty()) continue;
            String url = Net.abs(str(get(rt, "chapterUrl", ictx)), ctx.baseUrl);
            JSONObject ch = new JSONObject();
            ch.put("name", name);
            ch.put("url", url);
            chapters.add(ch);
        }

        // 去重（保留最后一次出现，丢掉顶部「最新章节」块）
        Set<String> seen = new HashSet<String>();
        List<JSONObject> uniq = new ArrayList<JSONObject>();
        for (int i = chapters.size() - 1; i >= 0; i--) {
            JSONObject c = chapters.get(i);
            String k = c.optString("url", "");
            if (k.isEmpty()) k = c.optString("name");
            if (seen.contains(k)) continue;
            seen.add(k);
            uniq.add(c);
        }
        java.util.Collections.reverse(uniq);

        // 判断是否倒序
        List<Integer> nums = new ArrayList<Integer>();
        for (int i = 0; i < Math.min(30, uniq.size()); i++) {
            Matcher m = Pattern.compile("([0-9]{1,5})").matcher(uniq.get(i).optString("name"));
            if (m.find()) nums.add(Integer.parseInt(m.group(1)));
        }
        if (nums.size() >= 3) {
            int desc = 0;
            for (int i = 1; i < nums.size(); i++) if (nums.get(i) < nums.get(i - 1)) desc++;
            if (desc >= (nums.size() - 1) * 0.85) java.util.Collections.reverse(uniq);
        }
        return uniq;
    }

    public static JSONObject content(JSONObject src, String chapterUrl) throws Exception {
        JSONObject rc = src.optJSONObject("ruleContent");
        if (rc == null) rc = new JSONObject();
        String url = chapterUrl;
        List<String> parts = new ArrayList<String>();
        String title = "";
        for (int page = 0; page < 4 && url != null && !url.isEmpty(); page++) {
            Ctx c0 = new Ctx();
            c0.source = src;
            c0.baseUrl = src.optString("bookSourceUrl");
            c0.page = page + 1;
            Net.Resp res = fetchRule(url, c0, src, null);
            if (!res.error.isEmpty()) throw new Exception(res.error);
            if (res.code >= 400) throw new Exception("HTTP " + res.code);
            Ctx ctx = new Ctx();
            loadInto(ctx, res.body, res.ctype, res.url);
            ctx.source = src;
            ctx.page = page + 1;

            Object raw = get(rc, "content", ctx);
            String text = "";
            if (raw instanceof List) {
                StringBuilder sb = new StringBuilder();
                for (Object o : (List<?>) raw) {
                    String one = o instanceof Element ? cleanText(((Element) o).html()) : cleanText(String.valueOf(o));
                    if (one.isEmpty()) continue;
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(one);
                }
                text = sb.toString();
            } else if (raw instanceof Element) {
                text = cleanText(((Element) raw).html());
            } else {
                text = cleanText(str(raw));
            }

            String rr = rc.optString("replaceRegex", "").trim();
            if (rr.contains("##")) {
                String[] seg = rr.split("##", -1);
                if (seg.length > 1 && !seg[1].isEmpty()) {
                    try {
                        text = text.replaceAll(seg[1], seg.length > 2 ? seg[2] : "");
                    } catch (Exception ignored) {
                    }
                }
            }
            if (title.isEmpty()) {
                JSONObject bi = src.optJSONObject("ruleBookInfo");
                if (bi != null) title = str(get(bi, "name", ctx));
            }
            if (!text.trim().isEmpty()) parts.add(text.trim());

            String next = "";
            String nr = rc.optString("nextContentUrl", "").trim();
            if (!nr.isEmpty()) next = Net.abs(str(evalRule(nr, ctx.result, ctx)), ctx.baseUrl);
            if (next.isEmpty() || next.equals(url)) break;
            url = next;
        }
        StringBuilder body = new StringBuilder();
        for (String p : parts) {
            if (body.length() > 0) body.append("\n\n");
            body.append(p);
        }
        JSONObject out = new JSONObject();
        out.put("title", title);
        out.put("content", body.toString().trim());
        out.put("chars", body.length());
        return out;
    }
}
