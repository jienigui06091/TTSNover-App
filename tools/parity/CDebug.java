import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class CDebug {
    public static void main(String[] a) throws Exception {
        JSONArray srcs = new JSONArray(new String(Files.readAllBytes(Paths.get("/tmp/enginetest/srcs.json")), "UTF-8"));
        String name = a[0];
        JSONObject s = null;
        for (int i = 0; i < srcs.length(); i++) {
            if (srcs.getJSONObject(i).optString("bookSourceName").contains(name)) { s = srcs.getJSONObject(i); break; }
        }
        if (s == null) { System.out.println("no src"); return; }
        System.out.println("源 " + s.optString("bookSourceName") + "  " + s.optString("bookSourceUrl"));
        System.out.println("ruleToc " + s.optJSONObject("ruleToc"));
        System.out.println("ruleContent " + s.optJSONObject("ruleContent"));
        List<JSONObject> books = com.ttsnover.reader.RuleEngine.search(s, "剑来", 1);
        if (books.isEmpty()) { System.out.println("搜索无结果"); return; }
        JSONObject b = books.get(0);
        System.out.println("book " + b.optString("name") + " " + b.optString("bookUrl"));
        JSONObject info = com.ttsnover.reader.RuleEngine.info(s, b.optString("bookUrl"));
        System.out.println("info " + info);
        List<JSONObject> toc = com.ttsnover.reader.RuleEngine.toc(s, info.optString("tocUrl", b.optString("bookUrl")));
        System.out.println("toc " + toc.size() + " 前3: " + (toc.size() > 2 ? toc.subList(0, 3) : toc));
        if (toc.isEmpty()) return;
        String url = toc.get(0).optString("url");
        com.ttsnover.reader.RuleEngine.Ctx c = new com.ttsnover.reader.RuleEngine.Ctx();
        c.baseUrl = s.optString("bookSourceUrl");
        c.source = s;
        com.ttsnover.reader.Net.Resp r = com.ttsnover.reader.RuleEngine.fetchRule(url, c, s, null);
        System.out.println("chapter HTTP " + r.code + " err=" + r.error + " len=" + r.body.length() + " ctype=" + r.ctype);
        System.out.println("body head: " + r.body.substring(0, Math.min(300, r.body.length())).replace("\n", " "));
        JSONObject ct = com.ttsnover.reader.RuleEngine.content(s, url);
        System.out.println("content chars=" + ct.optString("content").length() + " title=" + ct.optString("title"));
        String t = ct.optString("content");
        System.out.println("head: " + t.substring(0, Math.min(120, t.length())));
    }
}
