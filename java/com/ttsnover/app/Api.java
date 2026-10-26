package com.ttsnover.reader;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 本地模式的「接口层」：方法签名跟服务器版一致，但全部落到手机本地
 * （Store 存书架/书源，RuleEngine 抓书，TtsClient 直连 TTS）。
 * 这样 MainActivity / TocActivity / ReaderActivity / TtsService 都不用改调用方式。
 */
public class Api {

    public static class Err extends Exception {
        public final int status;

        public Err(int status, String msg) {
            super(msg);
            this.status = status;
        }

        public boolean isAuth() {
            return false;
        }
    }

    static Store db() {
        return Store.get(App.ctx());
    }

    static final ExecutorService SEARCH_POOL = Executors.newFixedThreadPool(8);

    // ---------------- 对外方法（保持服务器版签名） ----------------

    public static JSONObject json(String path) throws Err {
        return route("GET", path, null);
    }

    public static JSONObject post(String path, JSONObject body) throws Err {
        return route("POST", path, body);
    }

    public static JSONObject put(String path, JSONObject body) throws Err {
        return route("PUT", path, body);
    }

    public static JSONObject delete(String path) throws Err {
        return route("DELETE", path, null);
    }

    public static JSONObject postRaw(String path, String raw) throws Err {
        try {
            if (path.startsWith("/api/sources/import")) return db().importSources(raw);
            throw new Err(404, "本地模式没有这个接口：" + path);
        } catch (Err e) {
            throw e;
        } catch (Exception e) {
            throw new Err(0, errText(e));
        }
    }

    public static byte[] bytes(String path, JSONObject body, int readTimeout) throws Err {
        if (path.startsWith("/api/tts")) {
            String text = body == null ? "" : body.optString("text", "");
            try {
                return TtsClient.synth(App.ctx(), text);
            } catch (Exception e) {
                throw new Err(0, errText(e));
            }
        }
        throw new Err(404, "本地模式没有这个接口：" + path);
    }

    public static void goLogin(Context ctx) {
        // 本地模式没有登录
    }

    public static String errText(Exception e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) m = e.getClass().getSimpleName();
        if (e instanceof Err) {
            Err x = (Err) e;
            if (x.status == 429) return "请求太频繁，稍等几秒再试";
        }
        return m;
    }

    // ---------------- 路由 ----------------

    static JSONObject route(String method, String path, JSONObject body) throws Err {
        try {
            String p = path == null ? "" : path;
            if (p.startsWith("/api/books")) return booksRoute(method, p, body);
            if (p.startsWith("/api/src/search")) return search(qs(p, "q"));
            // Preview is deliberately read-only: search / detail / preview never writes a book.
            if (p.startsWith("/api/src/preview")) return previewBook(body);
            if (p.startsWith("/api/src/add")) return addBook(body);
            if (p.startsWith("/api/sources/export")) {
                return new JSONObject().put("sources", new JSONArray(db().exportSources()))
                        .put("raw", db().exportSources());
            }
            if (p.startsWith("/api/sources/list")) {
                return new JSONObject().put("sources", db().sources());
            }
            if (p.startsWith("/api/sources/import")) {
                return db().importSources(body == null ? "" : body.optString("raw", ""));
            }
            if (p.startsWith("/api/sources/delete")) {
                String url = body == null ? qs(p, "url") : body.optString("url", "");
                if (url.trim().isEmpty()) throw new Err(400, "缺书源地址");
                db().deleteSource(url);
                return new JSONObject().put("ok", true).put("total", db().sourceCount(false));
            }
            if (p.startsWith("/api/sources")) {
                return new JSONObject().put("total", db().sourceCount(false))
                        .put("enabled", db().sourceCount(true));
            }
            if (p.startsWith("/api/keys")) return keysRoute(method, body);
            if (p.startsWith("/api/settings")) return settingsRoute(method, body);
            if (p.startsWith("/api/version")) {
                return new JSONObject().put("version", "1.0.0-local")
                        .put("min_version", "1.0.0");
            }
            if (p.startsWith("/api/auth/sessions")) return new JSONObject().put("sessions", new JSONArray());
            if (p.startsWith("/api/auth/logout")) return new JSONObject().put("ok", true);
            throw new Err(404, "本地模式没有这个接口：" + p);
        } catch (Err e) {
            throw e;
        } catch (Exception e) {
            throw new Err(0, errText(e));
        }
    }

    static JSONObject booksRoute(String method, String path, JSONObject body) throws Exception {
        // /api/books
        if (path.equals("/api/books") || path.startsWith("/api/books?")) {
            return new JSONObject().put("books", db().books());
        }
        String rest = path.substring("/api/books/".length());
        if (rest.isEmpty()) return new JSONObject().put("books", db().books());
        // /api/books/{id}/ch/{idx}
        int chAt = rest.indexOf("/ch/");
        if (chAt > 0) {
            int bid = Integer.parseInt(rest.substring(0, chAt));
            int idx = Integer.parseInt(rest.substring(chAt + 4).replaceAll("[^0-9].*$", ""));
            return chapter(bid, idx);
        }
        // /api/books/{id}/progress
        int prAt = rest.indexOf("/progress");
        if (prAt > 0) {
            int bid = Integer.parseInt(rest.substring(0, prAt));
            int ch = body == null ? 0 : body.optInt("ch", 0);
            double off = body == null ? 0 : body.optDouble("off", 0);
            db().setProgress(bid, ch, off);
            return new JSONObject().put("ok", true);
        }
        // /api/books/{id}
        int bid = Integer.parseInt(rest.replaceAll("[^0-9].*$", ""));
        if ("DELETE".equals(method)) {
            db().deleteBook(bid);
            return new JSONObject().put("ok", true);
        }
        JSONObject b = db().book(bid);
        if (b == null) throw new Err(404, "这本书不在书架里");
        db().touch(bid);
        return new JSONObject().put("book", b).put("chapters", db().chapters(bid));
    }

    static JSONObject chapter(int bid, int idx) throws Exception {
        JSONObject meta = db().chapterMeta(bid, idx);
        if (meta == null) throw new Err(404, "没有第 " + (idx + 1) + " 章");
        String name = meta.optString("name");
        String url = meta.optString("url");
        String content = db().cachedContent(bid, idx);
        if (content == null || content.trim().isEmpty()) {
            JSONObject book = db().book(bid);
            if (book == null) throw new Err(404, "这本书不在书架里");
            JSONObject src = db().sourceByUrl(book.optString("source_url"));
            if (src == null) throw new Err(404, "这本书的书源已被删除，重新搜索加一次");
            JSONObject r = RuleEngine.content(src, url);
            content = r.optString("content", "");
            String t = r.optString("title", "");
            if (!t.isEmpty() && (name.isEmpty() || name.length() < 2)) {
                name = t;
            }
            if (content.trim().isEmpty()) throw new Err(0, "这一章没抓到正文（源可能改版）");
            db().cacheContent(bid, idx, content);
        }
        JSONArray paras = new JSONArray();
        String[] lines = content.split("\n");
        for (String ln : lines) {
            String s = ln.trim();
            if (!s.isEmpty()) paras.put(s);
        }
        return new JSONObject().put("title", name).put("paras", paras)
                .put("count", paras.length()).put("idx", idx);
    }

    /** 搜索详情的试读：只解析第一章正文，绝不写入 books/chapters 表。 */
    static JSONObject previewBook(JSONObject body) throws Exception {
        if (body == null) throw new Err(400, "缺参数");
        String sid = body.optString("sid", "");
        JSONObject bk = body.optJSONObject("book");
        if (bk == null) bk = new JSONObject();
        JSONObject src = db().sourceByUrl(sid);
        if (src == null) throw new Err(404, "书源不存在（可能刚被删了）");
        String bookUrl = bk.optString("url", bk.optString("bookUrl", "")).trim();
        if (bookUrl.isEmpty()) throw new Err(400, "缺书籍链接");

        JSONObject info = new JSONObject();
        try { info = RuleEngine.info(src, bookUrl); } catch (Throwable ignored) { }
        String tocUrl = info.optString("tocUrl", bookUrl);
        List<JSONObject> toc = RuleEngine.toc(src, tocUrl);
        if (toc.isEmpty() && !bookUrl.equals(tocUrl)) toc = RuleEngine.toc(src, bookUrl);
        if (toc.isEmpty()) throw new Err(0, "目录是空的：源可能改版或需要 JS 规则");
        JSONObject first = toc.get(0);
        JSONObject chapter = RuleEngine.content(src, first.optString("url", ""));
        String content = chapter.optString("content", "").trim();
        if (content.isEmpty()) throw new Err(0, "第一章没有抓到正文（源可能改版）");
        JSONArray paras = new JSONArray();
        for (String line : content.split("\\n")) {
            String s = line.trim();
            if (!s.isEmpty()) paras.put(s);
        }
        return new JSONObject()
                .put("title", chapter.optString("title", first.optString("name", bk.optString("name", ""))))
                .put("paras", paras).put("count", paras.length())
                .put("chapters", toc.size()).put("preview", true);
    }

    static JSONObject addBook(JSONObject body) throws Exception {
        if (body == null) throw new Err(400, "缺参数");
        JSONObject bk = body.optJSONObject("book");
        if (bk == null) bk = new JSONObject();
        String sid = body.optString("sid", "");
        JSONObject src = db().sourceByUrl(sid);
        if (src == null) throw new Err(404, "书源不存在（可能刚被删了）");
        String bookUrl = bk.optString("url", "").trim();
        if (bookUrl.isEmpty()) throw new Err(400, "缺书籍链接");
        int exist = db().findBook(sid, bookUrl);
        if (exist > 0) {
            return new JSONObject().put("id", exist).put("chapters", db().chapterCount(exist))
                    .put("existing", true);
        }
        JSONObject info = new JSONObject();
        try {
            info = RuleEngine.info(src, bookUrl);
        } catch (Throwable ignored) {
            // 详情页取不到（不少源是 JSON 接口 / 需要 POST）不影响加书架，直接拿目录
        }
        String tocUrl = info.optString("tocUrl", "");
        List<JSONObject> tocList;
        try {
            tocList = RuleEngine.toc(src, tocUrl.isEmpty() ? bookUrl : tocUrl);
        } catch (Throwable t) {
            tocList = new ArrayList<JSONObject>();
        }
        if (tocList.isEmpty() && !tocUrl.isEmpty() && !tocUrl.equals(bookUrl)) {
            try {
                tocList = RuleEngine.toc(src, bookUrl);
            } catch (Throwable ignored) {
            }
        }
        JSONArray chs = new JSONArray();
        for (JSONObject c : tocList) chs.put(c);
        if (chs.length() == 0) throw new Err(0, "目录是空的：源可能改版或需要 JS 规则");
        JSONObject book = new JSONObject();
        book.put("name", nz(info.optString("name", bk.optString("name"))));
        book.put("author", nz(info.optString("author", bk.optString("author"))));
        book.put("kind", nz(info.optString("kind", "")));
        book.put("intro", nz(info.optString("intro", "")));
        book.put("coverUrl", nz(info.optString("coverUrl", "")));
        book.put("bookUrl", bookUrl);
        book.put("tocUrl", nz(info.optString("tocUrl", bookUrl)));
        book.put("lastChapter", nz(info.optString("lastChapter", "")));
        book.put("wordCount", nz(info.optString("wordCount", "")));
        book.put("_sourceUrl", sid);
        book.put("_source", src.optString("bookSourceName", ""));
        int id = db().addBook(book, chs);
        return new JSONObject().put("id", id).put("chapters", chs.length());
    }

    /** 并发搜索所有启用的书源，12 秒总预算，单源 8 秒超时。 */
    static JSONObject search(final String key) throws Exception {
        long t0 = System.currentTimeMillis();
        final List<JSONObject> srcs = db().enabledSources();
        if (srcs.isEmpty()) throw new Err(0, "还没有书源：我的 → 导入书源");
        List<Future<JSONObject>> fs = new ArrayList<Future<JSONObject>>();
        CompletionService<JSONObject> completed = new ExecutorCompletionService<JSONObject>(SEARCH_POOL);
        // Keep a group for every finished source, including its error. The UI can then
        // show whether imported sources were unreachable, unsupported, or simply empty.
        for (final JSONObject s : srcs) {
            fs.add(completed.submit(new Callable<JSONObject>() {
                @Override
                public JSONObject call() {
                    JSONObject g = new JSONObject();
                    try {
                        g.put("sid", s.optString("bookSourceUrl"));
                        g.put("source", s.optString("bookSourceName"));
                        List<JSONObject> books = RuleEngine.search(s, key, 1);
                        JSONArray arr = new JSONArray();
                        for (JSONObject b : books) {
                            String nm = b.optString("name", "");
                            String au = b.optString("author", "");
                            String matched = nm.contains(key) ? "name" : (au.contains(key) ? "author" : "");
                            if (matched.isEmpty()) continue;
                            b.put("matched", matched);
                            arr.put(b);
                        }
                        g.put("books", arr);
                    } catch (Throwable t) {
                        try {
                            g.put("books", new JSONArray());
                            g.put("error", String.valueOf(t.getMessage()));
                        } catch (Exception ignored) {
                        }
                    }
                    return g;
                }
            }));
        }
        JSONArray results = new JSONArray();
        int errors = 0;
        long deadline = t0 + 12000;
        // Do not wait for the first slow source: collect whichever sources finish first.
        for (int done = 0; done < fs.size(); done++) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                Future<JSONObject> f = completed.poll(left, TimeUnit.MILLISECONDS);
                if (f == null) break;
                JSONObject g = f.get();
                results.put(g);
                if (!g.optString("error", "").trim().isEmpty()) errors++;
            } catch (Exception e) {
                errors++;
            }
        }
        for (Future<JSONObject> f : fs) f.cancel(true);
        JSONObject out = new JSONObject();
        out.put("results", results);
        out.put("stats", new JSONObject().put("ms", System.currentTimeMillis() - t0)
                .put("sources", srcs.size()).put("ok", results.length()).put("errors", errors));
        return out;
    }

    // ---------------- 设置 / Key ----------------

    static JSONObject keysRoute(String method, JSONObject body) throws Exception {
        if ("POST".equals(method) && body != null) {
            String provider = body.optString("provider", "mimo");
            String key = body.optString("key", "").trim();
            if (key.isEmpty()) throw new Err(400, "Key 不能为空");
            if ("fish".equals(provider)) {
                TtsClient.sp(App.ctx()).edit().putString("fish_key", key).apply();
            } else {
                TtsClient.sp(App.ctx()).edit().putString("mimo_key", key).apply();
            }
            return new JSONObject().put("masked", mask(key)).put("provider", provider);
        }
        JSONObject keys = new JSONObject();
        String mk = TtsClient.mimoKey(App.ctx());
        keys.put("mimo", new JSONObject().put("has", !mk.isEmpty()).put("masked", mask(mk)));
        String fk = TtsClient.sp(App.ctx()).getString("fish_key", "");
        keys.put("fish", new JSONObject().put("has", !fk.isEmpty()).put("masked", mask(fk)));
        keys.put("", new JSONObject().put("has", true).put("masked", ""));
        return new JSONObject().put("keys", keys);
    }

    static JSONObject settingsRoute(String method, JSONObject body) throws Exception {
        if ("PUT".equals(method) || "POST".equals(method)) {
            JSONObject tts = body == null ? null : body.optJSONObject("tts");
            if (tts != null) {
                android.content.SharedPreferences.Editor e = TtsClient.sp(App.ctx()).edit();
                if (tts.has("engine")) e.putString("engine", tts.optString("engine", "mimo"));
                if (tts.has("voice")) e.putString("voice", tts.optString("voice", ""));
                if (tts.has("speed")) e.putFloat("speed", (float) tts.optDouble("speed", 1.18));
                if (tts.has("style")) e.putString("style", tts.optString("style", ""));
                if (tts.has("mode")) e.putString("mode", tts.optString("mode", "preset"));
                if (tts.has("desc")) e.putString("desc", tts.optString("desc", ""));
                if (tts.has("url")) e.putString("url", tts.optString("url", ""));
                if (tts.has("headers")) e.putString("headers", tts.optString("headers", ""));
                if (tts.has("body")) e.putString("body", tts.optString("body", ""));
                if (tts.has("method")) e.putString("method", tts.optString("method", "GET"));
                if (tts.has("cvoice")) e.putString("cvoice", tts.optString("cvoice", ""));
                e.apply();
            }
            return new JSONObject().put("ok", true);
        }
        JSONObject tts = new JSONObject();
        tts.put("engine", TtsClient.engine(App.ctx()));
        tts.put("voice", TtsClient.voice(App.ctx()));
        tts.put("speed", TtsClient.speed(App.ctx()));
        tts.put("style", TtsClient.style(App.ctx()));
        tts.put("mode", TtsClient.mode(App.ctx()));
        tts.put("desc", TtsClient.desc(App.ctx()));
        tts.put("url", TtsClient.sp(App.ctx()).getString("url", ""));
        tts.put("headers", TtsClient.sp(App.ctx()).getString("headers", ""));
        tts.put("body", TtsClient.sp(App.ctx()).getString("body", ""));
        tts.put("method", TtsClient.sp(App.ctx()).getString("method", "GET"));
        return new JSONObject().put("tts", tts);
    }

    // ---------------- 小工具 ----------------

    static String mask(String k) {
        if (k == null || k.isEmpty()) return "";
        if (k.length() <= 8) return "已配置";
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    static String qs(String path, String k) {
        int i = path.indexOf(k + "=");
        if (i < 0) return "";
        String s = path.substring(i + k.length() + 1);
        int amp = s.indexOf('&');
        if (amp >= 0) s = s.substring(0, amp);
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
