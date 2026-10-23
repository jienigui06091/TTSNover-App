import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class Harness {
    public static void main(String[] a) throws Exception {
        JSONArray srcs = new JSONArray(new String(Files.readAllBytes(Paths.get("/tmp/enginetest/srcs.json")), "UTF-8"));
        String key = a.length > 0 ? a[0] : "剑来";
        int only = a.length > 1 ? Integer.parseInt(a[1]) : -1;
        JSONArray out = new JSONArray();
        int ok = 0, zero = 0, err = 0;
        for (int i = 0; i < srcs.length(); i++) {
            if (only >= 0 && i != only) continue;
            JSONObject s = srcs.getJSONObject(i);
            JSONObject rec = new JSONObject();
            rec.put("name", s.optString("bookSourceName"));
            long t = System.currentTimeMillis();
            try {
                List<JSONObject> books = com.ttsnover.reader.RuleEngine.search(s, key, 1);
                long ms = System.currentTimeMillis() - t;
                rec.put("ms", ms).put("ok", !books.isEmpty()).put("count", books.size());
                if (!books.isEmpty()) {
                    JSONObject b = books.get(0);
                    rec.put("sample", b.optString("name") + "/" + b.optString("author"));
                    rec.put("url", b.optString("bookUrl"));
                    ok++;
                } else {
                    zero++;
                }
            } catch (Throwable e) {
                String m = e.getMessage();
                if (m == null) m = e.getClass().getSimpleName();
                if (m.length() > 200) m = m.substring(0, 200);
                rec.put("ok", false).put("error", m).put("ms", System.currentTimeMillis() - t);
                err++;
            }
            out.put(rec);
        }
        Files.write(Paths.get("/tmp/enginetest/java_results.json"), out.toString(1).getBytes("UTF-8"));
        System.out.println("=== ok=" + ok + " zero=" + zero + " err=" + err);
    }
}
