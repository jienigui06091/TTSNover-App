package com.ttsnover.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

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
    private View btnRefresh;
    private Button btnSearch, btnSaveKey, btnSaveTts, btnTtsTest, btnSessions, btnLogout, btnImportSrc, btnSrcManage;
    private Spinner spProvider;
    private TextView tvKeyLabel;
    private View layoutShelfEmpty;
    private ImageView ivTabShelf, ivTabSearch, ivTabMe;
    private View llTabShelf, llTabSearch, llTabMe;
    private HorizontalScrollView hsvSources;
    private LinearLayout llSourceChips;
    private final List<JSONObject> rawSourceGroups = new ArrayList<JSONObject>();
    private String selectedSourceId = "ALL";
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
        try {
            initApp(b);
        } catch (final Throwable t) {
            Log.e("MainActivity", "Failed to start", t);
            showCrashUi(t);
        }
    }

    private void showCrashUi(final Throwable t) {
        try {
            final String trace = Log.getStackTraceString(t);
            try {
                File dir = getFilesDir();
                if (dir != null) {
                    File f = new File(dir, "crash.log");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(trace.getBytes("UTF-8"));
                    fos.close();
                }
            } catch (Exception ignored) {
            }

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xFF0B0F19);
            root.setPadding(40, 60, 40, 40);

            TextView tvTitle = new TextView(this);
            tvTitle.setText("TTSNover 启动异常");
            tvTitle.setTextSize(20);
            tvTitle.setTextColor(0xFFEF4444);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);

            TextView tvHint = new TextView(this);
            tvHint.setText("应用初始化时发生以下错误，请长按复制或截图反馈：");
            tvHint.setTextSize(13);
            tvHint.setTextColor(0xFF94A3B8);
            tvHint.setPadding(0, 16, 0, 16);
            root.addView(tvHint);

            ScrollView sv = new ScrollView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            sv.setLayoutParams(lp);

            final TextView tvTrace = new TextView(this);
            tvTrace.setText(trace);
            tvTrace.setTextSize(12);
            tvTrace.setTextColor(0xFFF8FAFC);
            tvTrace.setBackgroundColor(0xFF131B2E);
            tvTrace.setPadding(24, 24, 24, 24);
            tvTrace.setTextIsSelectable(true);
            sv.addView(tvTrace);
            root.addView(sv);

            Button btnCopy = new Button(this);
            btnCopy.setText("复制错误日志");
            btnCopy.setTextColor(Color.WHITE);
            btnCopy.setBackgroundResource(R.drawable.bg_btn_primary);
            btnCopy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setText(trace);
                            Toast.makeText(MainActivity.this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 120);
            btnLp.topMargin = 24;
            root.addView(btnCopy, btnLp);

            setContentView(root);
        } catch (Throwable ignored) {
        }
    }

    private void initApp(Bundle b) {
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

        View.OnClickListener clickShelf = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanel(0);
            }
        };
        View.OnClickListener clickSearch = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanel(1);
            }
        };
        View.OnClickListener clickMe = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPanel(2);
            }
        };

        tabShelf.setOnClickListener(clickShelf);
        if (llTabShelf != null) llTabShelf.setOnClickListener(clickShelf);
        tabSearch.setOnClickListener(clickSearch);
        if (llTabSearch != null) llTabSearch.setOnClickListener(clickSearch);
        tabMe.setOnClickListener(clickMe);
        if (llTabMe != null) llTabMe.setOnClickListener(clickMe);

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
                showBookMenu(shelf.get(pos));
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
                if (pos < 0 || pos >= found.size()) return;
                JSONObject bk = found.get(pos);
                if (bk.optBoolean("_is_header", false)) return;
                showBookDetailsDialog(bk);
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
        btnSrcManage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSourceManager();
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

        tvAccount.setText("本机模式 · 不连服务器");
        tvServer.setText("书源、书架、阅读进度、API Key 全部存在这台手机上");
        btnSessions.setVisibility(View.GONE);
        btnLogout.setVisibility(View.GONE);
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
        layoutShelfEmpty = findViewById(R.id.layoutShelfEmpty);
        View btnEmptySearch = findViewById(R.id.btnEmptySearch);
        if (btnEmptySearch != null) {
            btnEmptySearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPanel(1);
                }
            });
        }
        ivTabShelf = findViewById(R.id.ivTabShelf);
        ivTabSearch = findViewById(R.id.ivTabSearch);
        ivTabMe = findViewById(R.id.ivTabMe);
        llTabShelf = findViewById(R.id.llTabShelf);
        llTabSearch = findViewById(R.id.llTabSearch);
        llTabMe = findViewById(R.id.llTabMe);
        hsvSources = findViewById(R.id.hsvSources);
        llSourceChips = findViewById(R.id.llSourceChips);
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
        btnSrcManage = findViewById(R.id.btnSrcManage);
        spProvider = findViewById(R.id.spProvider);
        tvKeyLabel = findViewById(R.id.tvKeyLabel);
    }

    private void showPanel(int i) {
        panelShelf.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        panelSearch.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        panelMe.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        int on = getResources().getColor(R.color.accent);
        int off = getResources().getColor(R.color.fg3);
        tabShelf.setTextColor(i == 0 ? on : off);
        tabSearch.setTextColor(i == 1 ? on : off);
        tabMe.setTextColor(i == 2 ? on : off);
        if (ivTabShelf != null) ivTabShelf.setColorFilter(i == 0 ? on : off);
        if (ivTabSearch != null) ivTabSearch.setColorFilter(i == 1 ? on : off);
        if (ivTabMe != null) ivTabMe.setColorFilter(i == 2 ? on : off);
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
                            // loadShelf() may finish before initApp() has created the adapter.
                            // This happens on a fast local SQLite read during cold start.
                            if (shelfAdapter != null) shelfAdapter.notifyDataSetChanged();
                            if (tvShelfInfo == null || tvShelfEmpty == null) return;
                            tvShelfInfo.setText(shelf.size() + " 本");
                            boolean empty = shelf.isEmpty();
                            tvShelfEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                            if (layoutShelfEmpty != null) {
                                layoutShelfEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                            }
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
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra("bid", book.optInt("id"));
        i.putExtra("idx", book.optInt("progress_ch"));
        i.putExtra("off", (float) book.optDouble("progress_off", -1.0));
        i.putExtra("title", book.optString("title"));
        i.putExtra("count", book.optInt("ch_count"));
        startActivity(i);
    }

    private void openBookToc(JSONObject book) {
        Intent i = new Intent(this, TocActivity.class);
        i.putExtra("bid", book.optInt("id"));
        i.putExtra("title", book.optString("title"));
        i.putExtra("count", book.optInt("ch_count"));
        i.putExtra("cur", book.optInt("progress_ch"));
        startActivity(i);
    }

    private void showBookMenu(final JSONObject book) {
        final String[] options = {"继续阅读", "查看目录", "换源搜索", "移出书架"};
        new AlertDialog.Builder(this)
                .setTitle("《" + book.optString("title") + "》")
                .setItems(options, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            openBook(book);
                        } else if (which == 1) {
                            openBookToc(book);
                        } else if (which == 2) {
                            showPanel(1);
                            etQ.setText(book.optString("title"));
                            doSearch();
                        } else if (which == 3) {
                            confirmDelete(book);
                        }
                    }
                })
                .show();
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
        rawSourceGroups.clear();
        selectedSourceId = "ALL";
        if (hsvSources != null) hsvSources.setVisibility(View.GONE);
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
                            rawSourceGroups.clear();
                            selectedSourceId = "ALL";

                            int totalBooks = 0;
                            int srcOk = 0, srcAll = 0;
                            if (res != null) {
                                for (int i = 0; i < res.length(); i++) {
                                    JSONObject g = res.optJSONObject(i);
                                    if (g == null) continue;
                                    srcAll++;
                                    JSONArray books = g.optJSONArray("books");
                                    int bCount = books != null ? books.length() : 0;
                                    if (bCount > 0) {
                                        srcOk++;
                                        totalBooks += bCount;
                                        for (int k = 0; k < bCount; k++) {
                                            JSONObject bk = books.optJSONObject(k);
                                            if (bk != null) {
                                                try {
                                                    bk.put("_sid", g.optString("sid"));
                                                    bk.put("_source", g.optString("source"));
                                                } catch (Exception ignored) {
                                                }
                                            }
                                        }
                                        rawSourceGroups.add(g);
                                    }
                                }
                            }

                            JSONObject st = r.optJSONObject("stats");
                            String ms = st != null ? String.valueOf(st.optInt("ms")) : "?";
                            int disabled = st == null ? 0 : st.optInt("disabled");
                            String autoDisabled = disabled > 0 ? " · 已自动停用 " + disabled + " 个超时源" : "";
                            if (totalBooks == 0) {
                                tvStat.setText("共搜索 " + srcAll + " 个书源，未找到匹配书籍 · " + ms + "ms" + autoDisabled);
                                if (hsvSources != null) hsvSources.setVisibility(View.GONE);
                            } else {
                                tvStat.setText("在 " + srcOk + " 个书源中找到 " + totalBooks + " 本书 · " + ms + "ms · 点卡片详情试读，点右侧+加书架" + autoDisabled);
                                buildSourceChips(totalBooks);
                            }
                            refreshSearchDisplayList();
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

    private void buildSourceChips(int totalBooks) {
        if (llSourceChips == null || hsvSources == null) return;
        llSourceChips.removeAllViews();
        if (rawSourceGroups.isEmpty()) {
            hsvSources.setVisibility(View.GONE);
            return;
        }
        hsvSources.setVisibility(View.VISIBLE);

        final TextView chipAll = createSourceChip("全部 (" + totalBooks + ")", "ALL".equals(selectedSourceId));
        chipAll.setTag("ALL");
        chipAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectedSourceId = "ALL";
                updateChipStyles();
                refreshSearchDisplayList();
            }
        });
        llSourceChips.addView(chipAll);

        for (int i = 0; i < rawSourceGroups.size(); i++) {
            final JSONObject g = rawSourceGroups.get(i);
            final String sid = g.optString("sid");
            final String sName = g.optString("source", "未知源");
            JSONArray books = g.optJSONArray("books");
            int count = books != null ? books.length() : 0;

            final TextView chip = createSourceChip(sName + " (" + count + ")", sid.equals(selectedSourceId));
            chip.setTag(sid);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectedSourceId = sid;
                    updateChipStyles();
                    refreshSearchDisplayList();
                }
            });
            llSourceChips.addView(chip);
        }
    }

    private TextView createSourceChip(String text, boolean selected) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setPadding(28, 12, 28, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 16;
        tv.setLayoutParams(lp);

        if (selected) {
            tv.setBackgroundResource(R.drawable.bg_btn_pill);
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(null, Typeface.BOLD);
        } else {
            tv.setBackgroundResource(R.drawable.bg_tag);
            tv.setTextColor(getResources().getColor(R.color.fg2));
            tv.setTypeface(null, Typeface.NORMAL);
        }
        return tv;
    }

    private void updateChipStyles() {
        if (llSourceChips == null) return;
        for (int i = 0; i < llSourceChips.getChildCount(); i++) {
            View child = llSourceChips.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                Object tag = tv.getTag();
                boolean active = (tag != null && tag.equals(selectedSourceId));
                if (active) {
                    tv.setBackgroundResource(R.drawable.bg_btn_pill);
                    tv.setTextColor(Color.WHITE);
                    tv.setTypeface(null, Typeface.BOLD);
                } else {
                    tv.setBackgroundResource(R.drawable.bg_tag);
                    tv.setTextColor(getResources().getColor(R.color.fg2));
                    tv.setTypeface(null, Typeface.NORMAL);
                }
            }
        }
    }

    private void refreshSearchDisplayList() {
        found.clear();
        for (JSONObject g : rawSourceGroups) {
            String sid = g.optString("sid");
            if (!"ALL".equals(selectedSourceId) && !selectedSourceId.equals(sid)) {
                continue;
            }
            JSONArray books = g.optJSONArray("books");
            if (books == null || books.length() == 0) continue;

            try {
                JSONObject header = new JSONObject();
                header.put("_is_header", true);
                header.put("source", g.optString("source", "未知书源"));
                header.put("sid", sid);
                header.put("count", books.length());
                found.add(header);
            } catch (Exception ignored) {
            }

            for (int k = 0; k < books.length(); k++) {
                JSONObject bk = books.optJSONObject(k);
                if (bk != null) {
                    found.add(bk);
                }
            }
        }
        foundAdapter.notifyDataSetChanged();
    }

    private boolean isBookOnShelf(String url, String name, String author) {
        if (shelf == null || shelf.isEmpty()) return false;
        for (JSONObject b : shelf) {
            String bUrl = b.optString("book_url", "");
            if (!bUrl.isEmpty() && !url.isEmpty() && bUrl.equals(url)) return true;
            String bTitle = b.optString("title", "");
            if (!bTitle.isEmpty() && bTitle.equals(name)) {
                String bAuthor = b.optString("author", "");
                if (TextUtils.isEmpty(author) || TextUtils.isEmpty(bAuthor) || author.equals(bAuthor)) {
                    return true;
                }
            }
        }
        return false;
    }

    private JSONObject getShelfBook(String url, String name) {
        if (shelf == null) return null;
        for (JSONObject b : shelf) {
            if (!url.isEmpty() && url.equals(b.optString("book_url", ""))) return b;
            if (name.equals(b.optString("title", ""))) return b;
        }
        return null;
    }

    private void manualAddBook(final JSONObject bk, final TextView btnView) {
        manualAddBook(bk, btnView, false);
    }

    private void manualAddBook(final JSONObject bk, final TextView btnView, final boolean openAfter) {
        if (btnView != null) {
            btnView.setText("添加中…");
            btnView.setEnabled(false);
        }
        final String name = bk.optString("name");
        Toast.makeText(this, "正在抓取《" + name + "》并加入书架…", Toast.LENGTH_SHORT).show();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject book = new JSONObject();
                    book.put("name", name);
                    book.put("author", bk.optString("author"));
                    book.put("url", bk.optString("url"));
                    JSONObject body = new JSONObject();
                    String sid = bk.optString("_sid");
                    if (sid.isEmpty()) sid = bk.optString("sid");
                    body.put("sid", sid);
                    body.put("book", book);

                    final JSONObject r = Api.post("/api/src/add", body);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            int bid = r.optInt("id");
                            int n = r.optInt("chapters");
                            Toast.makeText(MainActivity.this, "《" + name + "》已成功加入书架！共 " + n + " 章", Toast.LENGTH_SHORT).show();
                            loadShelf();
                            if (btnView != null) {
                                btnView.setText("已在书架");
                                btnView.setBackgroundResource(R.drawable.bg_tag);
                                btnView.setTextColor(getResources().getColor(R.color.fg3));
                                btnView.setEnabled(false);
                            }
                            foundAdapter.notifyDataSetChanged();
                            if (openAfter && bid > 0) {
                                Intent i = new Intent(MainActivity.this, ReaderActivity.class);
                                i.putExtra("bid", bid);
                                i.putExtra("idx", 0);
                                i.putExtra("title", name);
                                i.putExtra("count", n);
                                startActivity(i);
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (btnView != null) {
                                btnView.setText("+ 书架");
                                btnView.setEnabled(true);
                            }
                            Toast.makeText(MainActivity.this, "加入失败：" + Api.errText(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void previewBook(final JSONObject bk) {
        final String name = bk.optString("name", "这本书");
        Toast.makeText(this, "正在加载《" + name + "》第一章…", Toast.LENGTH_SHORT).show();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    String sid = bk.optString("_sid", bk.optString("sid", ""));
                    body.put("sid", sid).put("book", bk);
                    final JSONObject r = Api.post("/api/src/preview", body);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Intent i = new Intent(MainActivity.this, ReaderActivity.class);
                            i.putExtra("preview", true);
                            i.putExtra("previewSid", sid);
                            i.putExtra("previewBookUrl", bk.optString("url"));
                            i.putExtra("previewToc", r.optJSONArray("toc").toString());
                            i.putExtra("title", name);
                            i.putExtra("count", r.optInt("chapters"));
                            i.putExtra("idx", 0);
                            startActivity(i);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "试读失败：" + Api.errText(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void showPreviewDialog(String book, String chapter, String text, int chapters) {
        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText((TextUtils.isEmpty(chapter) ? "第一章" : chapter) + "\n\n" + text);
        body.setTextSize(16);
        body.setTextColor(getResources().getColor(R.color.fg));
        body.setLineSpacing(8, 1.25f);
        body.setPadding(36, 24, 36, 24);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("《" + book + "》 · 试读" + (chapters > 0 ? "（共 " + chapters + " 章）" : ""))
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showBookDetailsDialog(final JSONObject bk) {
        final String name = bk.optString("name");
        final String author = bk.optString("author");
        final String source = bk.optString("_source");
        final String latest = bk.optString("latest", "");
        final String url = bk.optString("url", "");
        final boolean onShelf = isBookOnShelf(url, name, author);

        StringBuilder sb = new StringBuilder();
        sb.append("作者：").append(TextUtils.isEmpty(author) ? "佚名" : author).append("\n");
        sb.append("书源：").append(source).append("\n");
        if (!latest.isEmpty()) {
            sb.append("最新章节：").append(latest).append("\n");
        }
        sb.append("书籍链接：").append(url);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("《" + name + "》")
                .setMessage(sb.toString())
                .setNegativeButton("关闭", null);

        if (onShelf) {
            b.setPositiveButton("已在书架（前往阅读）", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    JSONObject exist = getShelfBook(url, name);
                    if (exist != null) {
                        openBook(exist);
                    } else {
                        Toast.makeText(MainActivity.this, "已在书架中", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } else {
            b.setPositiveButton("+ 手动加入书架", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    manualAddBook(bk, null, false);
                }
            });
            b.setNeutralButton("试读第一章", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    previewBook(bk);
                }
            });
        }
        b.show();
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
            tvKeyLabel.setText("自定义接口（url / headers / body，填你自己的 TTS 服务）");
            tvKeyState.setText("自定义接口不需要 Key，url 里可以用 {{text}} / {{voice}} / {{speed}}");
            tvKeyState.setTextColor(getResources().getColor(R.color.fg2));
            return;
        }
        tvKeyLabel.setText(PROV_LABEL[i] + " 的 API Key（只存本机，界面只显示掩码）");
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

    /** 书源管理：多选启用状态 + 导出到本机文件。 */
    private void openSourceManager() {
        try {
            final JSONArray arr = Api.json("/api/sources/list").optJSONArray("sources");
            if (arr == null || arr.length() == 0) {
                Toast.makeText(this, "还没有书源，先导入 JSON", Toast.LENGTH_SHORT).show();
                return;
            }
            final String[] labels = new String[arr.length()];
            final boolean[] checked = new boolean[arr.length()];
            final String[] urls = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                urls[i] = s.optString("url");
                labels[i] = s.optString("name") + (s.optBoolean("enabled") ? "" : "（已停用）");
                checked[i] = s.optBoolean("enabled");
            }
            new AlertDialog.Builder(this)
                    .setTitle("书源管理 · " + arr.length() + " 个")
                    .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which, boolean isChecked) {
                            checked[which] = isChecked;
                        }
                    })
                    .setPositiveButton("保存启用状态", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            pool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    Store db = Store.get(MainActivity.this);
                                    for (int i = 0; i < urls.length; i++) db.setSourceEnabled(urls[i], checked[i]);
                                    ui.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            Toast.makeText(MainActivity.this, "已保存启用状态", Toast.LENGTH_SHORT).show();
                                            loadSearchMeta();
                                        }
                                    });
                                }
                            });
                        }
                    })
                    .setNeutralButton("删除书源", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            chooseSourceToDelete(arr);
                        }
                    })
                    .setNegativeButton("关闭", null)
                    .show();
        } catch (final Exception e) {
            Toast.makeText(this, "读取书源失败：" + Api.errText(e), Toast.LENGTH_LONG).show();
        }
    }

    /** 明确选择并二次确认，避免误删导入的书源。 */
    private void chooseSourceToDelete(final JSONArray arr) {
        final String[] labels = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.optJSONObject(i);
            labels[i] = s == null ? "未知书源" : s.optString("name", "未知书源");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择要删除的书源")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, final int which) {
                        final JSONObject s = arr.optJSONObject(which);
                        if (s == null) return;
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("删除书源？")
                                .setMessage("将删除「" + s.optString("name") + "」。已加入书架的书不受影响。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface x, int y) {
                                        deleteSource(s.optString("url"), s.optString("name"));
                                    }
                                }).show();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void deleteSource(final String url, final String name) {
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject body = new JSONObject().put("url", url);
                    Api.post("/api/sources/delete", body);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "已删除书源：「" + name + "」", Toast.LENGTH_SHORT).show();
                            loadSearchMeta();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(MainActivity.this, "删除失败：" + Api.errText(e), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    /** 把书源导出成 JSON，放在 App 外部目录，USB 能直接拷走。 */
    private void exportSources() {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String raw = Store.get(MainActivity.this).exportSources();
                    File dir = getExternalFilesDir(null);
                    final File f = new File(dir, "sources.json");
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(raw.getBytes("UTF-8"));
                    fo.close();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "已导出到 " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "导出失败：" + Api.errText(e), Toast.LENGTH_LONG).show();
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
        // onResume is invoked during onCreate, before initApp() has installed adapters.
        // initApp() performs the first load only after its UI setup completes.
        if (Prefs.loggedIn() && shelfAdapter != null) loadShelf();
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
        public int getItemViewType(int position) {
            if (searchMode && position >= 0 && position < found.size()) {
                return found.get(position).optBoolean("_is_header", false) ? 1 : 0;
            }
            return 0;
        }

        @Override
        public int getViewTypeCount() {
            return searchMode ? 2 : 1;
        }

        @Override
        public boolean isEnabled(int position) {
            if (searchMode && position >= 0 && position < found.size()) {
                return !found.get(position).optBoolean("_is_header", false);
            }
            return true;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            View v = cv;
            JSONObject o = searchMode ? found.get(i) : shelf.get(i);
            int[] covers = {
                R.drawable.bg_book_cover_1,
                R.drawable.bg_book_cover_2,
                R.drawable.bg_book_cover_3,
                R.drawable.bg_book_cover_4
            };
            if (searchMode) {
                boolean isHeader = o.optBoolean("_is_header", false);
                if (isHeader) {
                    if (v == null || v.findViewById(R.id.tvHeaderSourceName) == null) {
                        v = LayoutInflater.from(MainActivity.this).inflate(
                                R.layout.item_search_header, parent, false);
                    }
                    TextView tvName = v.findViewById(R.id.tvHeaderSourceName);
                    TextView tvCount = v.findViewById(R.id.tvHeaderCount);
                    if (tvName != null) tvName.setText(o.optString("source", "未知书源"));
                    if (tvCount != null) tvCount.setText("找到 " + o.optInt("count", 0) + " 本");
                    return v;
                }

                if (v == null || v.findViewById(R.id.tvSTitle) == null) {
                    v = LayoutInflater.from(MainActivity.this).inflate(
                            R.layout.item_search, parent, false);
                }

                final JSONObject bk = o;
                final String name = bk.optString("name", "");
                final String author = bk.optString("author", "");
                final String src = bk.optString("_source", "");
                final String m = bk.optString("matched", "");
                final String url = bk.optString("url", "");

                View flCover = v.findViewById(R.id.flSCover);
                if (flCover != null) {
                    flCover.setBackgroundResource(covers[Math.abs(name.hashCode()) % covers.length]);
                }
                TextView tvCoverTitle = v.findViewById(R.id.tvSCoverTitle);
                if (tvCoverTitle != null) {
                    tvCoverTitle.setText(name);
                }

                TextView tvSTitle = v.findViewById(R.id.tvSTitle);
                if (tvSTitle != null) tvSTitle.setText(name);

                TextView tvSMeta = v.findViewById(R.id.tvSMeta);
                if (tvSMeta != null) {
                    StringBuilder meta = new StringBuilder();
                    meta.append(TextUtils.isEmpty(author) ? "佚名" : author);
                    String latest = bk.optString("latest", "");
                    if (!TextUtils.isEmpty(latest)) {
                        meta.append("\n最新: ").append(latest);
                    }
                    tvSMeta.setText(meta.toString());
                }

                TextView tvSSourceTag = v.findViewById(R.id.tvSSourceTag);
                if (tvSSourceTag != null) {
                    tvSSourceTag.setText(src);
                }

                TextView hit = v.findViewById(R.id.tvSHit);
                if (hit != null) {
                    if (m.isEmpty() || "name".equals(m)) {
                        hit.setText("精准匹配");
                    } else if ("author".equals(m)) {
                        hit.setText("作者命中");
                    } else {
                        hit.setText(m);
                    }
                }

                final TextView btnAddToShelf = v.findViewById(R.id.btnAddToShelf);
                if (btnAddToShelf != null) {
                    final boolean onShelf = isBookOnShelf(url, name, author);
                    if (onShelf) {
                        btnAddToShelf.setText("已在书架");
                        btnAddToShelf.setBackgroundResource(R.drawable.bg_tag);
                        btnAddToShelf.setTextColor(getResources().getColor(R.color.fg3));
                        btnAddToShelf.setEnabled(false);
                        btnAddToShelf.setOnClickListener(null);
                    } else {
                        btnAddToShelf.setText("+ 加书架");
                        btnAddToShelf.setBackgroundResource(R.drawable.bg_btn_pill);
                        btnAddToShelf.setTextColor(Color.WHITE);
                        btnAddToShelf.setEnabled(true);
                        btnAddToShelf.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                manualAddBook(bk, btnAddToShelf, false);
                            }
                        });
                    }
                }
                return v;
            }

            if (v == null || v.findViewById(R.id.tvBTitle) == null) {
                v = LayoutInflater.from(MainActivity.this).inflate(
                        R.layout.item_book, parent, false);
            }
                String title = o.optString("title", "");
                String author = o.optString("author", "");
                int n = o.optInt("ch_count", 0);
                int pg = o.optInt("progress_ch", 0);
                String src = o.optString("src_name", "");

                View flCover = v.findViewById(R.id.flCover);
                if (flCover != null) {
                    flCover.setBackgroundResource(covers[Math.abs(title.hashCode()) % covers.length]);
                }
                TextView tvCoverTitle = v.findViewById(R.id.tvCoverTitle);
                if (tvCoverTitle != null) {
                    tvCoverTitle.setText(title);
                }

                TextView tvBTitle = v.findViewById(R.id.tvBTitle);
                if (tvBTitle != null) tvBTitle.setText(title);

                TextView tvBMeta = v.findViewById(R.id.tvBMeta);
                if (tvBMeta != null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(TextUtils.isEmpty(author) ? "佚名" : author);
                    if (!TextUtils.isEmpty(src)) sb.append(" · ").append(src);
                    sb.append(" · ").append(n).append(" 章");
                    tvBMeta.setText(sb.toString());
                }

                int percent = (n > 0) ? Math.min(100, Math.max(0, (pg + 1) * 100 / n)) : 0;

                TextView tvBTag = v.findViewById(R.id.tvBTag);
                if (tvBTag != null) {
                    if (n > 0 && pg > 0) {
                        tvBTag.setText("已读 " + percent + "%");
                        tvBTag.setTextColor(getResources().getColor(R.color.accent));
                        tvBTag.setBackgroundResource(R.drawable.bg_tag_accent);
                    } else {
                        tvBTag.setText("未读");
                        tvBTag.setTextColor(getResources().getColor(R.color.fg3));
                        tvBTag.setBackgroundResource(R.drawable.bg_tag);
                    }
                }

                TextView tvBProgress = v.findViewById(R.id.tvBProgress);
                if (tvBProgress != null) {
                    if (pg > 0) {
                        String lastCh = o.optString("last_chapter", "");
                        tvBProgress.setText("读到第 " + (pg + 1) + " 章" + (lastCh.isEmpty() ? "" : " · " + lastCh));
                    } else {
                        tvBProgress.setText("尚未开始阅读");
                    }
                }

                ProgressBar pbBook = v.findViewById(R.id.pbBook);
                if (pbBook != null) {
                    pbBook.setProgress(percent);
                }
            return v;
        }
    }
}
