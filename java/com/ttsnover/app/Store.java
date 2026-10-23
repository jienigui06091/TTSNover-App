package com.ttsnover.reader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 本地单机库：书架 / 目录 / 正文缓存 / 书源 / 设置，全部在手机上，不依赖服务器。 */
public class Store extends SQLiteOpenHelper {

    static final String NAME = "ttsnover.db";
    static final int VER = 1;
    private static Store I;

    public static synchronized Store get(Context c) {
        if (I == null) I = new Store(c.getApplicationContext());
        return I;
    }

    private Store(Context c) {
        super(c, NAME, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE books(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " title TEXT, author TEXT, kind TEXT, intro TEXT, cover TEXT,"
                + " book_url TEXT, toc_url TEXT, source_url TEXT, source_name TEXT,"
                + " last_chapter TEXT, word_count TEXT, added INTEGER, opened INTEGER,"
                + " progress_ch INTEGER DEFAULT 0, progress_off REAL DEFAULT 0)");
        db.execSQL("CREATE TABLE chapters(book_id INTEGER, idx INTEGER, name TEXT, url TEXT,"
                + " content TEXT, PRIMARY KEY(book_id, idx))");
        db.execSQL("CREATE TABLE sources(url TEXT PRIMARY KEY, name TEXT, grp TEXT,"
                + " enabled INTEGER DEFAULT 1, raw TEXT, added INTEGER)");
        db.execSQL("CREATE TABLE settings(k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL("CREATE INDEX ix_chapters_book ON chapters(book_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    }

    // ---------------- 书架 ----------------

    public JSONArray books() {
        JSONArray out = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT b.id,b.title,b.author,b.kind,b.cover,b.source_name,b.last_chapter,"
                        + "b.progress_ch,b.opened,b.added,"
                        + "(SELECT COUNT(*) FROM chapters WHERE book_id=b.id) ch"
                        + " FROM books b ORDER BY b.opened DESC, b.added DESC", null);
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("id", c.getInt(0));
                    o.put("title", nz(c.getString(1)));
                    o.put("author", nz(c.getString(2)));
                    o.put("kind", nz(c.getString(3)));
                    o.put("cover", nz(c.getString(4)));
                    o.put("src_name", nz(c.getString(5)));
                    o.put("last_chapter", nz(c.getString(6)));
                    o.put("progress_ch", c.getInt(7));
                    o.put("ch_count", c.getInt(10));
                    o.put("opened", c.getLong(8));
                } catch (Exception ignored) {
                }
                out.put(o);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public JSONObject book(int id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT id,title,author,kind,intro,cover,"
                + "book_url,toc_url,source_url,source_name,last_chapter,word_count,progress_ch,"
                + "progress_off FROM books WHERE id=?", new String[]{String.valueOf(id)});
        try {
            if (!c.moveToNext()) return null;
            JSONObject o = new JSONObject();
            try {
                o.put("id", c.getInt(0));
                o.put("title", nz(c.getString(1)));
                o.put("author", nz(c.getString(2)));
                o.put("kind", nz(c.getString(3)));
                o.put("intro", nz(c.getString(4)));
                o.put("cover", nz(c.getString(5)));
                o.put("book_url", nz(c.getString(6)));
                o.put("toc_url", nz(c.getString(7)));
                o.put("source_url", nz(c.getString(8)));
                o.put("src_name", nz(c.getString(9)));
                o.put("last_chapter", nz(c.getString(10)));
                o.put("word_count", nz(c.getString(11)));
                o.put("progress_ch", c.getInt(12));
                o.put("progress_off", c.getDouble(13));
            } catch (Exception ignored) {
            }
            return o;
        } finally {
            c.close();
        }
    }

    /** 已在书架的同源同链接返回其 id，避免重复添加 */
    public int findBook(String sourceUrl, String bookUrl) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM books WHERE source_url=? AND book_url=? LIMIT 1",
                new String[]{nz(sourceUrl), nz(bookUrl)});
        try {
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public int addBook(JSONObject b, JSONArray chs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("title", b.optString("name"));
            v.put("author", b.optString("author"));
            v.put("kind", b.optString("kind"));
            v.put("intro", b.optString("intro"));
            v.put("cover", b.optString("coverUrl"));
            v.put("book_url", b.optString("bookUrl"));
            v.put("toc_url", b.optString("tocUrl"));
            v.put("source_url", b.optString("_sourceUrl"));
            v.put("source_name", b.optString("_source"));
            v.put("last_chapter", b.optString("lastChapter"));
            v.put("word_count", b.optString("wordCount"));
            v.put("added", System.currentTimeMillis());
            v.put("opened", System.currentTimeMillis());
            long id = db.insert("books", null, v);
            if (chs != null) {
                for (int i = 0; i < chs.length(); i++) {
                    JSONObject ch = chs.optJSONObject(i);
                    if (ch == null) continue;
                    ContentValues cv = new ContentValues();
                    cv.put("book_id", id);
                    cv.put("idx", i);
                    cv.put("name", ch.optString("name"));
                    cv.put("url", ch.optString("url"));
                    db.insertWithOnConflict("chapters", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
            return (int) id;
        } finally {
            db.endTransaction();
        }
    }

    public void deleteBook(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("chapters", "book_id=?", new String[]{String.valueOf(id)});
        db.delete("books", "id=?", new String[]{String.valueOf(id)});
    }

    public void touch(int id) {
        ContentValues v = new ContentValues();
        v.put("opened", System.currentTimeMillis());
        getWritableDatabase().update("books", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void setProgress(int id, int ch, double off) {
        ContentValues v = new ContentValues();
        v.put("progress_ch", ch);
        v.put("progress_off", off);
        v.put("opened", System.currentTimeMillis());
        getWritableDatabase().update("books", v, "id=?", new String[]{String.valueOf(id)});
    }

    // ---------------- 目录 / 正文 ----------------

    public JSONArray chapters(int bid) {
        JSONArray out = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT idx,name,url,content IS NOT NULL FROM chapters WHERE book_id=? ORDER BY idx",
                new String[]{String.valueOf(bid)});
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("idx", c.getInt(0));
                    o.put("name", nz(c.getString(1)));
                    o.put("title", nz(c.getString(1)));   // 目录页读的是 title
                    o.put("url", nz(c.getString(2)));
                    o.put("cached", c.getInt(3) == 1);
                } catch (Exception ignored) {
                }
                out.put(o);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int chapterCount(int bid) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM chapters WHERE book_id=?",
                new String[]{String.valueOf(bid)});
        try {
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void saveChapters(int bid, JSONArray chs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("chapters", "book_id=?", new String[]{String.valueOf(bid)});
            for (int i = 0; i < chs.length(); i++) {
                JSONObject ch = chs.optJSONObject(i);
                if (ch == null) continue;
                ContentValues cv = new ContentValues();
                cv.put("book_id", bid);
                cv.put("idx", i);
                cv.put("name", ch.optString("name"));
                cv.put("url", ch.optString("url"));
                db.insertWithOnConflict("chapters", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public String cachedContent(int bid, int idx) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT content FROM chapters WHERE book_id=? AND idx=?",
                new String[]{String.valueOf(bid), String.valueOf(idx)});
        try {
            return c.moveToNext() ? c.getString(0) : null;
        } finally {
            c.close();
        }
    }

    public void cacheContent(int bid, int idx, String content) {
        ContentValues v = new ContentValues();
        v.put("content", content);
        getWritableDatabase().update("chapters", v, "book_id=? AND idx=?",
                new String[]{String.valueOf(bid), String.valueOf(idx)});
    }

    public JSONObject chapterMeta(int bid, int idx) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT name,url FROM chapters WHERE book_id=? AND idx=?",
                new String[]{String.valueOf(bid), String.valueOf(idx)});
        try {
            if (!c.moveToNext()) return null;
            JSONObject o = new JSONObject();
            try {
                o.put("name", nz(c.getString(0)));
                o.put("url", nz(c.getString(1)));
            } catch (Exception ignored) {
            }
            return o;
        } finally {
            c.close();
        }
    }

    // ---------------- 书源 ----------------

    public JSONArray sources() {
        JSONArray out = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT url,name,grp,enabled FROM sources ORDER BY name", null);
        try {
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                try {
                    o.put("url", nz(c.getString(0)));
                    o.put("name", nz(c.getString(1)));
                    o.put("group", nz(c.getString(2)));
                    o.put("enabled", c.getInt(3) == 1);
                } catch (Exception ignored) {
                }
                out.put(o);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public int sourceCount(boolean onlyEnabled) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sources"
                + (onlyEnabled ? " WHERE enabled=1" : ""), null);
        try {
            return c.moveToNext() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public List<JSONObject> enabledSources() {
        List<JSONObject> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT raw FROM sources WHERE enabled=1", null);
        try {
            while (c.moveToNext()) {
                try {
                    out.add(new JSONObject(c.getString(0)));
                } catch (Exception ignored) {
                }
            }
        } finally {
            c.close();
        }
        return out;
    }

    public JSONObject sourceByUrl(String url) {
        Cursor c = getReadableDatabase().rawQuery("SELECT raw FROM sources WHERE url=?",
                new String[]{nz(url)});
        try {
            if (c.moveToNext()) {
                try {
                    return new JSONObject(c.getString(0));
                } catch (Exception ignored) {
                }
            }
        } finally {
            c.close();
        }
        return null;
    }

    /** 导入书源；兼容单个对象、数组、以及 {"sources":[...]} 包装 */
    public JSONObject importSources(String text) throws Exception {
        JSONArray arr = new JSONArray();
        String t = text == null ? "" : text.trim();
        if (t.startsWith("[")) {
            arr = new JSONArray(t);
        } else if (t.startsWith("{")) {
            JSONObject o = new JSONObject(t);
            JSONArray inner = o.optJSONArray("sources");
            if (inner != null) arr = inner;
            else arr.put(o);
        } else {
            throw new Exception("不是合法的书源 JSON");
        }
        int added = 0, updated = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String url = s.optString("bookSourceUrl", "").trim();
                String name = s.optString("bookSourceName", "").trim();
                if (url.isEmpty() || name.isEmpty()) continue;
                ContentValues v = new ContentValues();
                v.put("url", url);
                v.put("name", name);
                v.put("grp", s.optString("bookSourceGroup", ""));
                v.put("enabled", 1);
                v.put("raw", s.toString());
                v.put("added", System.currentTimeMillis());
                long r = db.insertWithOnConflict("sources", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                if (r == -1) {
                    ContentValues u = new ContentValues();
                    u.put("name", name);
                    u.put("grp", s.optString("bookSourceGroup", ""));
                    u.put("raw", s.toString());
                    db.update("sources", u, "url=?", new String[]{url});
                    updated++;
                } else {
                    added++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        JSONObject out = new JSONObject();
        out.put("added", added);
        out.put("updated", updated);
        out.put("total", sourceCount(false));
        return out;
    }

    public void setSourceEnabled(String url, boolean on) {
        ContentValues v = new ContentValues();
        v.put("enabled", on ? 1 : 0);
        getWritableDatabase().update("sources", v, "url=?", new String[]{nz(url)});
    }

    public void deleteSource(String url) {
        getWritableDatabase().delete("sources", "url=?", new String[]{nz(url)});
    }

    public void deleteAllSources() {
        getWritableDatabase().delete("sources", null, null);
    }

    public String exportSources() {
        JSONArray arr = new JSONArray();
        Cursor c = getReadableDatabase().rawQuery("SELECT raw FROM sources ORDER BY name", null);
        try {
            while (c.moveToNext()) {
                try {
                    arr.put(new JSONObject(c.getString(0)));
                } catch (Exception ignored) {
                }
            }
        } finally {
            c.close();
        }
        return arr.toString();
    }

    // ---------------- 设置 ----------------

    public String setting(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM settings WHERE k=?",
                new String[]{k});
        try {
            return c.moveToNext() ? c.getString(0) : def;
        } finally {
            c.close();
        }
    }

    public void putSetting(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k);
        cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("settings", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
