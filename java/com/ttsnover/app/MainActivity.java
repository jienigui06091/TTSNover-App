package com.ttsnover.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private View panelShelf, panelSearch, panelMe;
    private TextView tabShelf, tabSearch, tabMe;
    private ListView lvShelf, lvSearch, lvSessionsHost;
    private TextView tvShelfInfo, tvShelfEmpty, tvStat, tvAccount, tvServer, tvKeyState, tvVer;
    private EditText etQ, etKey, etVoice, etSpeed;
    private Button btnRefresh, btnSearch, btnSaveKey, btnSaveTts, btnTtsTest, btnSessions, btnLogout, btnImportSrc;
    private Spinner spProvider;
    private TextView tvKeyLabel;
    /** 和 tts.py 里的 PRESETS 对齐：顺序不能乱 */
    static final String[] PROV = {"mimo", "fish", "siliconflow", ""};
    static final String[] PROV_LABEL = {"小米 MiMo（预置/设计/克隆）", "Fish Audio（克隆音色）",
            "硅基流动 CosyVoice2", "跟随网页版设置（自定义接口）"};
    private JSONObject keysCache = new JSONObject();

    private final List<JSONObject> shelf = new ArrayList<JSONObject>();
    private final List<JSONObject> found = new ArrayList<JSONObject>();
    private BookAdapter shelfAdapter, foundAdapter;
    private boolean searching = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Prefs.loggedIn()) {
            Api.goLogin(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        bindViews();
        shelfAdapter = new BookAdapter(false);
        foundAdapter = new BookAdapter(true);
        lvShelf.setAdapter(shelfAdapter);
        lvSearch.setAdapter(foundAdapter);

        tabShelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanel(0);
            }
        });
        tabSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanel(1);
            }
        });
        tabMe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanel(2);
            }
        });

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadShelf();
            }
        });

        lvShelf.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                openBook(shelf.get(pos));
            }
        });
        lvShelf.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                confirmDelete(shelf.get(pos));
                return true;
            }
        });

        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        });
        lvSearch.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                addBook(found.get(pos));
            }
        });

        btnSaveKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveKey();
            }
        });
        btnSaveTts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveTts();
            }
        });
        btnTtsTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testTts();
            }
        });
        btnSessions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSessions();
            }
        });
        btnImportSrc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSourceFile();
            }
        });
        spProvider.setAdapter(new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, PROV_LABEL));
        spProvider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Prefs.setTtsProvider(PROV[position]);
                showKeyState();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logout();
            }
        });

        tvAccount.setText(Prefs.user());
        tvServer.setText(Prefs.base());
        loadShelf();
        loadMe();
    }

    private void bindViews() {
        panelShelf = findViewById(R.id.panelShelf);
        panelSearch = findViewById(R.id.panelSearch);
        panelMe = findViewById(R.id.panelMe);
        tabShelf = findViewById(R.id.tabShelf);
        tabSearch = findViewById(R.id.tabSearch);
        tabMe = findViewById(R.id.tabMe);
        lvShelf = findViewById(R.id.lvShelf);
        lvSearch = findViewById(R.id.lvSearch);
        tvShelfInfo = findViewById(R.id.tvShelfInfo);
        tvShelfEmpty = findViewById(R.id.tvShelfEmpty);
        tvStat = findViewById(R.id.tvStat);
        tvAccount = findViewById(R.id.tvAccount);
        tvServer = findViewById(R.id.tvServer);
        tvKeyState = findViewById(R.id.tvKeyState);
        tvVer = findViewById(R.id.tvVer);
        etQ = findViewById(R.id.etQ);
        etKey = findViewById(R.id.etKey);
        etVoice = findViewById(R.id.etVoice);
        etSpeed = findViewById(R.id.etSpeed);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnSearch = findViewById(R.id.btnSearch);
        btnSaveKey = findViewById(R.id.btnSaveKey);
        btnSaveTts = findViewById(R.id.btnSaveTts);
        btnTtsTest = findViewById(R.id.btnTtsTest);
        btnSessions = findViewById(R.id.btnSessions);
        btnLogout = findViewById(R.id.btnLogout);
        btnImportSrc = findViewById(R.id.btnImportSrc);
        spProvider = findViewById(R.id.spProvider);
        tvKeyLabel = findViewById(R.id.tvKeyLabel);
    }

    private void showPanel(int i) {
        panelShelf.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        panelSearch.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        panelMe.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        int on = getResources().getColor(R.color.accent);
        int off = getResources().getColor(R.color.fg2);
        tabShelf.setTextColor(i == 0 ? on : off);
        tabSearch.setTextColor(i == 1 ? on : off);
        tabMe.setTextColor(i == 2 ? on : off);
        if (i == 1) loadSearchMeta();
        if (i == 2) loadMe();
    }

    /** 搜索页顶部提示：这个账号有没有可用书源。 */
    private void loadSearchMeta() {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject s = Api.json("/api/sources");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            int total = s.optInt("total");
                            int en = s.optInt("enabled");
                            if (total == 0) {
                                tvStat.setText("这个账号还没有书源 → 去「我的 → 导入书源 JSON」，搜索才有结果");
                            } else if (en == 0) {
                                tvStat.setText("有 " + total + " 个书源但都被禁用了，去网页版开启后再搜");
                            } else if (TextUtils.isEmpty(tvStat.getText())) {
                                tvStat.setText(en + " 个书源可用，输入书名或作者开始搜");
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    // ---------------- 书架 ----------------

    private void loadShelf() {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject r = Api.json("/api/books?limit=200&offset=0");
                    final JSONArray arr = r.optJSONArray("books");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            shelf.clear();
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject o = arr.optJSONObject(i);
                                    if (o != null) shelf.add(o);
                                }
                            }
                            shelfAdapter.notifyDataSetChanged();
                            tvShelfInfo.setText(shelf.size() + " 本");
                            tvShelfEmpty.setVisibility(shelf.isEmpty() ? View.VISIBLE : View.GONE);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (e instanceof Api.Err && ((Api.Err) e).isAuth()) {
                                Api.goLogin(MainActivity.this);
                                return;
                            }
                            tvShelfInfo.setText("加载失败：" + Api.errText(e));
                        }
                    });
                }
            }
        });
    }

    private void openBook(JSONObject book) {
        Intent i = new Intent(this, TocActivity.class);
        i.putExtra("bid", book.optInt("id"));
        i.putExtra("title", book.optString("title"));
        i.putExtra("count", book.optInt("ch_count"));
        i.putExtra("cur", book.optInt("progress_ch"));
        startActivity(i);
    }

    private void confirmDelete(final JSONObject book) {
        new AlertDialog.Builder(this)
                .setTitle("删除《" + book.optString("title") + "》？")
                .setMessage("会连同章节缓存一起删掉，书源不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Api.delete("/api/books/" + book.optInt("id"));
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                                            loadShelf();
                                        }
                                    });
                                } catch (final Exception e) {
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(MainActivity.this, Api.errText(e), Toast.LENGTH_LONG).show();
                                        }
                                    });
                                }
                            }
                        });
                    }
                }).show();
    }

    // ---------------- 搜索 ----------------

    private void doSearch() {
        final String q = etQ.getText().toString().trim();
        if (q.isEmpty()) {
            tvStat.setText("先输入书名或作者");
            return;
        }
        if (searching) return;
        searching = true;
        btnSearch.setEnabled(false);
        btnSearch.setText("搜索中");
        tvStat.setText("正在搜索各书源…（最多约 12 秒）");
        found.clear();
        foundAdapter.notifyDataSetChanged();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject r = Api.json("/api/src/search?q=" + URLEncoder.encode(q, "UTF-8"));
                    final JSONArray res = r.optJSONArray("results");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            searching = false;
                            btnSearch.setEnabled(true);
                            btnSearch.setText("搜索");
                            found.clear();
                            int srcOk = 0, srcAll = 0;
                            if (res != null) {
                                for (int i = 0; i < res.length(); i++) {
                                    JSONObject g = res.optJSONObject(i);
                                    if (g == null) continue;
                                    srcAll++;
                                    JSONArray books = g.optJSONArray("books");
                                    if (books != null && books.length() > 0) srcOk++;
                                    if (books == null) continue;
                                    for (int k = 0; k < books.length(); k++) {
                                        JSONObject bk = books.optJSONObject(k);
                                        if (bk == null) continue;
                                        try {
                                            bk.put("_sid", g.optString("sid"));
                                            bk.put("_source", g.optString("source"));
                                        } catch (Exception ignored) {
                                        }
                                        found.add(bk);
                                    }
                                }
                            }
                            foundAdapter.notifyDataSetChanged();
                            JSONObject st = r.optJSONObject("stats");
                            String ms = st != null ? String.valueOf(st.optInt("ms")) : "?";
                            tvStat.setText("命中 " + found.size() + " 本 / " + srcOk + "/" + srcAll
                                    + " 个源有结果 · " + ms + "ms · 点一本加入书架");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            searching = false;
                            btnSearch.setEnabled(true);
                            btnSearch.setText("搜索");
                            tvStat.setText(Api.errText(e));
                        }
                    });
                }
            }
        });
    }

    private void addBook(final JSONObject bk) {
        final String name = bk.optString("name");
        tvStat.setText("正在抓目录：《" + name + "》…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject book = new JSONObject();
                    book.put("name", name);
                    book.put("author", bk.optString("author"));
                    book.put("url", bk.optString("url"));
                    JSONObject body = new JSONObject();
                    body.put("sid", bk.optString("_sid"));
                    if (bk.optString("_sid").isEmpty()) body.put("sid", bk.optString("sid"));
                    body.put("book", book);
                    final JSONObject r = Api.post("/api/src/add", body);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            int bid = r.optInt("id");
                            int n = r.optInt("chapters");
                            tvStat.setText("已加入书架：" + n + " 章");
                            loadShelf();
                            if (bid > 0) {
                                Intent i = new Intent(MainActivity.this, TocActivity.class);
                                i.putExtra("bid", bid);
                                i.putExtra("title", name);
                                i.putExtra("count", n);
                                i.putExtra("cur", 0);
                                startActivity(i);
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            tvStat.setText("加入失败：" + Api.errText(e));
                        }
                    });
                }
            }
        });
    }

    // ---------------- 我的 ----------------

    private void loadMe() {
        // 先用本地偏好回填（离线也能看到自己选的引擎/音色）
        spProvider.setSelection(providerIndex(Prefs.ttsProvider()));
        if (TextUtils.isEmpty(etVoice.getText())) etVoice.setText(Prefs.ttsVoice());
        if (TextUtils.isEmpty(etSpeed.getText())) etSpeed.setText(String.valueOf(Prefs.ttsSpeed()));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject keys = Api.json("/api/keys");
                    final JSONObject set = Api.json("/api/settings");
                    final JSONObject ver = Api.json("/api/version");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            keysCache = keys.optJSONObject("keys") == null
                                    ? new JSONObject() : keys.optJSONObject("keys");
                            showKeyState();
                            JSONObject tts = set.optJSONObject("tts");
                            if (tts != null) {
                                if (TextUtils.isEmpty(etVoice.getText())) {
                                    etVoice.setText(tts.optString("voice", ""));
                                }
                                if (TextUtils.isEmpty(etSpeed.getText())) {
                                    etSpeed.setText(String.valueOf(tts.optDouble("speed", 1.18)));
                                }
                            }
                            tvVer.setText("TTSNover " + ver.optString("version")
                                    + " · 服务端最低 " + ver.optString("min_version"));
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    static int providerIndex(String p) {
        for (int i = 0; i < PROV.length; i++) if (PROV[i].equals(p)) return i;
        return 0;
    }

    /** 当前选中引擎的 key 状态（只显示掩码）。 */
    private void showKeyState() {
        int i = spProvider.getSelectedItemPosition();
        if (i < 0 || i >= PROV.length) i = 0;
        JSONObject o = keysCache.optJSONObject(PROV[i]);
        boolean has = o != null && o.optBoolean("has");
        if (PROV[i].isEmpty()) {
            tvKeyLabel.setText("自定义接口（用网页版里那套 url/headers/body）");
            tvKeyState.setText("Key 在网页版里配好就行，这里不用填");
            tvKeyState.setTextColor(getResources().getColor(R.color.fg2));
            return;
        }
        tvKeyLabel.setText(PROV_LABEL[i] + " 的 API Key（服务端加密保存，只回掩码）");
        tvKeyState.setText(has ? "已配置：" + o.optString("masked") : "还没配置这个引擎的 Key，朗读会失败");
        tvKeyState.setTextColor(getResources().getColor(has ? R.color.accent2 : R.color.warn));
    }

    private static final int REQ_SRC = 21;

    /** 导入书源 JSON（Legado 导出文件也能直接喂）。 */
    private void pickSourceFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "选择书源 JSON"), REQ_SRC);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SRC || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    String raw = new String(bo.toByteArray(), "UTF-8");
                    final JSONObject r = Api.postRaw("/api/sources/import", raw);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "导入完成：新增 " + r.optInt("added") + "，更新 " + r.optInt("updated")
                                            + "，现在共 " + r.optInt("total") + " 个书源",
                                    Toast.LENGTH_LONG).show();
                            loadSearchMeta();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "导入失败：" + Api.errText(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void saveKey() {
        final String key = etKey.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Key 不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        btnSaveKey.setEnabled(false);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("provider", PROV[Math.max(0, spProvider.getSelectedItemPosition())]);
                    body.put("key", key);
                    final JSONObject r = Api.post("/api/keys", body);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btnSaveKey.setEnabled(true);
                            etKey.setText("");
                            tvKeyState.setText("已保存：" + r.optString("masked"));
                            tvKeyState.setTextColor(getResources().getColor(R.color.accent2));
                            Toast.makeText(MainActivity.this, "已加密保存到服务端", Toast.LENGTH_SHORT).show();
                            loadMe();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btnSaveKey.setEnabled(true);
                            Toast.makeText(MainActivity.this, Api.errText(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void saveTts() {
        final String voice = etVoice.getText().toString().trim();
        double speed;
        try {
            speed = Double.parseDouble(etSpeed.getText().toString().trim());
        } catch (Exception e) {
            speed = 1.18;
        }
        final double sp = Math.max(0.5, Math.min(2.0, speed));
        Prefs.setTtsVoice(voice);
        Prefs.setTtsSpeed((float) sp);
        btnSaveTts.setEnabled(false);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // 服务端 PUT 是整对象替换：必须先取回来再合并，否则会抹掉网页版其它设置
                    JSONObject set = Api.json("/api/settings");
                    JSONObject tts = set.optJSONObject("tts");
                    if (tts == null) tts = new JSONObject();
                    tts.put("voice", voice);
                    tts.put("speed", sp);
                    set.put("tts", tts);
                    Api.put("/api/settings", set);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btnSaveTts.setEnabled(true);
                            Toast.makeText(MainActivity.this, "朗读设置已保存（音色 "
                                    + (voice.isEmpty() ? "默认" : voice) + "，语速 " + sp + "）",
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btnSaveTts.setEnabled(true);
                            Toast.makeText(MainActivity.this, Api.errText(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void testTts() {
        btnTtsTest.setEnabled(false);
        btnTtsTest.setText("合成中…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("text", "朗读测试：这是一段试听，音色和语速满意就可以开始听书了。");
                    if (!Prefs.ttsProvider().isEmpty()) body.put("provider", Prefs.ttsProvider());
                    if (!Prefs.ttsVoice().isEmpty()) body.put("voice", Prefs.ttsVoice());
                    body.put("speed", Prefs.ttsSpeed());
                    final byte[] audio = Api.bytes("/api/tts", body, 300000);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btnTtsTest.setEnabled(true);
                            btnTtsTest.setText("试听一句");
                            TtsService.playOnce(MainActivity.this, audio);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btnTtsTest.setEnabled(true);
                            btnTtsTest.setText("试听一句");
                            Toast.makeText(MainActivity.this, Api.errText(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void showSessions() {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject r = Api.json("/api/auth/sessions");
                    final JSONArray arr = r.optJSONArray("sessions");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            final List<JSONObject> list = new ArrayList<JSONObject>();
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject o = arr.optJSONObject(i);
                                    if (o != null) list.add(o);
                                }
                            }
                            String[] items = new String[list.size()];
                            for (int i = 0; i < list.size(); i++) {
                                JSONObject o = list.get(i);
                                items[i] = o.optString("device", "未知设备")
                                        + (o.optBoolean("current") ? "（当前）" : "")
                                        + "\n" + o.optString("ip", "") + " · " + o.optString("last_seen", "");
                            }
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("登录设备（点一条踢下线）")
                                    .setItems(items, new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface d, final int which) {
                                            JSONObject o = list.get(which);
                                            if (o.optBoolean("current")) {
                                                Toast.makeText(MainActivity.this, "这是当前设备", Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                            pool.execute(new Runnable() {
                                                @Override
                                                public void run() {
                                                    try {
                                                        Api.delete("/api/auth/sessions/" + list.get(which).optString("sid"));
                                                        ui.post(new Runnable() {
                                                            @Override
                                                            public void run() {
                                                                Toast.makeText(MainActivity.this, "已踢下线", Toast.LENGTH_SHORT).show();
                                                            }
                                                        });
                                                    } catch (Exception ignored) {
                                                    }
                                                }
                                            });
                                        }
                                    })
                                    .setNegativeButton("关闭", null)
                                    .show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, Api.errText(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void logout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录？")
                .setMessage("本地 token 会被清掉，书架数据还在服务端。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Api.post("/api/auth/logout", new JSONObject());
                                } catch (Exception ignored) {
                                }
                                ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        TtsService.stopAll(MainActivity.this);
                                        Prefs.clearToken();
                                        Api.goLogin(MainActivity.this);
                                        finish();
                                    }
                                });
                            }
                        });
                    }
                }).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.loggedIn()) loadShelf();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    // ---------------- 适配器 ----------------

    private class BookAdapter extends BaseAdapter {
        private final boolean searchMode;

        BookAdapter(boolean searchMode) {
            this.searchMode = searchMode;
        }

        @Override
        public int getCount() {
            return searchMode ? found.size() : shelf.size();
        }

        @Override
        public Object getItem(int i) {
            return searchMode ? found.get(i) : shelf.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            View v = cv;
            if (v == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(
                        searchMode ? R.layout.item_search : R.layout.item_book, parent, false);
            }
            JSONObject o = searchMode ? found.get(i) : shelf.get(i);
            if (searchMode) {
                ((TextView) v.findViewById(R.id.tvSTitle)).setText(o.optString("name"));
                String author = o.optString("author");
                ((TextView) v.findViewById(R.id.tvSMeta)).setText(
                        (TextUtils.isEmpty(author) ? "佚名" : author) + " · " + o.optString("_source"));
                TextView hit = v.findViewById(R.id.tvSHit);
                String m = o.optString("matched", "");
                if (m.isEmpty() || "name".equals(m)) {
                    hit.setText("");
                } else {
                    hit.setText("author".equals(m) ? "作者命中" : m);
                }
            } else {
                ((TextView) v.findViewById(R.id.tvBTitle)).setText(o.optString("title"));
                StringBuilder sb = new StringBuilder();
                String author = o.optString("author");
                sb.append(TextUtils.isEmpty(author) ? "佚名" : author);
                int n = o.optInt("ch_count");
                sb.append(" · ").append(n).append(" 章");
                int pg = o.optInt("progress_ch");
                if (pg > 0) sb.append(" · 读到第 ").append(pg + 1).append(" 章");
                String src = o.optString("src_name", "");
                if (!TextUtils.isEmpty(src)) sb.append(" · ").append(src);
                ((TextView) v.findViewById(R.id.tvBMeta)).setText(sb.toString());
            }
            return v;
        }
    }
}
