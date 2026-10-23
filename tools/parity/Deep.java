import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** 对每个搜索有结果的源，继续走 详情 → 目录 → 正文，用来和 node 引擎逐项对比 */
public class Deep {
    public static void main(String[] a) throws Exception {
        JSONArray srcs = new JSONArray(new String(Files.readAllBytes(Paths.get("/tmp/enginetest/srcs.json")), "UTF-8"));
        JSONArray out = new JSONArray();
        for (int i = 0; i < srcs.length(); i++) {
            JSONObject s = srcs.getJSONObject(i);
            JSONObject rec = new JSONObject();
            rec.put("name", s.optString("bookSourceName"));
            try {
                List<JSONObject> books = com.ttsnover.reader.RuleEngine.search(s, "剑来", 1);
                if (books.isEmpty()) continue;
                JSONObject b = books.get(0);
                rec.put("bookUrl", b.optString("bookUrl"));
                JSONObject info = com.ttsnover.reader.RuleEngine.info(s, b.optString("bookUrl"));
                rec.put("infoName", info.optString("name"));
                rec.put("infoAuthor", info.optString("author"));
                rec.put("tocUrl", info.optString("tocUrl"));
                List<JSONObject> toc = com.ttsnover.reader.RuleEngine.toc(s, info.optString("tocUrl", b.optString("bookUrl")));
                rec.put("toc", toc.size());
                if (!toc.isEmpty()) {
                    JSONObject c = toc.get(0);
                    rec.put("chName", c.optString("name"));
                    JSONObject ct = com.ttsnover.reader.RuleEngine.content(s, c.optString("url"));
                    String txt = ct.optString("content", "");
                    rec.put("chars", txt.length());
                    rec.put("head", txt.length() > 60 ? txt.substring(0, 60) : txt);
                    rec.put("title", ct.optString("title"));
                }
                rec.put("ok", true);
            } catch (Throwable t) {
                String m = t.getMessage();
                if (m == null) m = t.getClass().getSimpleName();
                rec.put("ok", false).put("error", m.length() > 160 ? m.substring(0, 160) : m);
            }
            out.put(rec);
        }
        Files.write(Paths.get("/tmp/enginetest/java_deep.json"), out.toString(1).getBytes("UTF-8"));
        System.out.println("done " + out.length());
    }
}
