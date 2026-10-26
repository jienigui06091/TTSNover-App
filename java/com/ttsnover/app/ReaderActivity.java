package com.ttsnover.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReaderActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<String> paras = new ArrayList<String>();

    private int bid, idx, count;
    private String bookTitle;
    private boolean previewMode = false;
    private String previewSid = "", previewBookUrl = "";
    private JSONArray previewToc = new JSONArray();
    private float pendingOff = -1;

    private View rootReader;
    private View layoutTopHud, layoutBottomHud;
    private TextView tvChTitle, tvBookSubtitle, tvBodyTitle, tvBody, tvProgressInfo;
    private ScrollView sv;
    private Button btnPrev, btnNext, btnPlay, btnToc;
    private ImageButton btnBack, btnStyle;

    private boolean hudVisible = true;
    private int currentFontSize = 18;
    private String currentTheme = "dark";
    private String bodyRaw = "";
    private String lastHighlighted = "";
    private int lastTouchedOffset = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_reader);
        bid = getIntent().getIntExtra("bid", 0);
        idx = getIntent().getIntExtra("idx", 0);
        count = getIntent().getIntExtra("count", 0);
        bookTitle = getIntent().getStringExtra("title");
        previewMode = getIntent().getBooleanExtra("preview", false);
        previewSid = getIntent().getStringExtra("previewSid");
        previewBookUrl = getIntent().getStringExtra("previewBookUrl");
        if (previewSid == null) previewSid = "";
        if (previewBookUrl == null) previewBookUrl = "";
        if (previewMode) {
            try { previewToc = new JSONArray(getIntent().getStringExtra("previewToc")); }
            catch (Exception ignored) { previewToc = new JSONArray(); }
            if (count <= 0) count = previewToc.length();
        }
        pendingOff = getIntent().getFloatExtra("off", -1f);

        rootReader = findViewById(R.id.rootReader);
        layoutTopHud = findViewById(R.id.layoutTopHud);
        layoutBottomHud = findViewById(R.id.layoutBottomHud);
        tvChTitle = findViewById(R.id.tvChTitle);
        tvBookSubtitle = findViewById(R.id.tvBookSubtitle);
        tvBodyTitle = findViewById(R.id.tvBodyTitle);
        tvBody = findViewById(R.id.tvBody);
        tvProgressInfo = findViewById(R.id.tvProgressInfo);
        sv = findViewById(R.id.svBody);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnPlay = findViewById(R.id.btnPlay);
        btnToc = findViewById(R.id.btnToc);
        btnBack = findViewById(R.id.btnBack);
        btnStyle = findViewById(R.id.btnStyle);

        if (bookTitle != null) {
            tvBookSubtitle.setText(previewMode ? bookTitle + " · 试读模式（未加入书架）" : bookTitle);
        }

        currentFontSize = Prefs.readerFontSize();
        currentTheme = Prefs.readerTheme();
        applyTheme(currentTheme);
        applyFontSize(currentFontSize);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        if (btnStyle != null) {
            btnStyle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showStyleDialog();
                }
            });
        }

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(idx - 1);
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(idx + 1);
            }
        });
        btnToc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (previewMode) {
                    Toast.makeText(ReaderActivity.this, "试读可用上一章 / 下一章连续阅读；加入书架后可打开完整目录和朗读", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent i = new Intent(ReaderActivity.this, TocActivity.class);
                i.putExtra("bid", bid);
                i.putExtra("title", bookTitle);
                i.putExtra("count", count);
                i.putExtra("cur", idx);
                startActivity(i);
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPlayClicked();
            }
        });

        // 点击正文切换沉浸式全屏 / 控制栏显示
        View.OnClickListener toggleListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleHud();
            }
        };
        tvBody.setOnClickListener(toggleListener);
        tvBody.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    try {
                        TextView x = (TextView) v;
                        int line = x.getLayout().getLineForVertical((int) e.getY() - x.getTotalPaddingTop() + x.getScrollY());
                        lastTouchedOffset = x.getLayout().getOffsetForHorizontal(line, e.getX() - x.getTotalPaddingLeft());
                    } catch (Exception ignored) { }
                }
                return false;
            }
        });
        tvBody.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startReadingFromTouchedLine();
                return true;
            }
        });
        sv.setOnClickListener(toggleListener);
        sv.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP && !TtsService.RUNNING) {
                    v.postDelayed(new Runnable() {
                        @Override public void run() { openNextChapterAtBottom(); }
                    }, 80);
                }
                return false;
            }
        });

        load();
        ui.postDelayed(tick, 600);
    }

    private void toggleHud() {
        hudVisible = !hudVisible;
        int vis = hudVisible ? View.VISIBLE : View.GONE;
        if (layoutTopHud != null) layoutTopHud.setVisibility(vis);
        if (layoutBottomHud != null) layoutBottomHud.setVisibility(vis);
    }

    private void applyFontSize(int size) {
        currentFontSize = Math.max(14, Math.min(28, size));
        Prefs.setReaderFontSize(currentFontSize);
        if (tvBody != null) {
            tvBody.setTextSize(currentFontSize);
        }
    }

    private void applyTheme(String theme) {
        currentTheme = theme;
        Prefs.setReaderTheme(currentTheme);
        int bgColor;
        int fgColor;

        if ("sepia".equals(theme)) {
            bgColor = getResources().getColor(R.color.read_sepia_bg);
            fgColor = getResources().getColor(R.color.read_sepia_fg);
        } else if ("sage".equals(theme)) {
            bgColor = getResources().getColor(R.color.read_sage_bg);
            fgColor = getResources().getColor(R.color.read_sage_fg);
        } else if ("oled".equals(theme)) {
            bgColor = getResources().getColor(R.color.read_oled_bg);
            fgColor = getResources().getColor(R.color.read_oled_fg);
        } else {
            bgColor = getResources().getColor(R.color.read_dark_bg);
            fgColor = getResources().getColor(R.color.read_dark_fg);
        }

        if (rootReader != null) rootReader.setBackgroundColor(bgColor);
        if (tvBody != null) tvBody.setTextColor(fgColor);
        if (tvBodyTitle != null) tvBodyTitle.setTextColor(fgColor);
    }

    private void showStyleDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 30, 40, 20);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("阅读排版与主题");
        tvTitle.setTextSize(16);
        tvTitle.setTextColor(getResources().getColor(R.color.fg));
        tvTitle.setGravity(Gravity.CENTER);
        root.addView(tvTitle);

        // 字号调整行
        LinearLayout fontRow = new LinearLayout(this);
        fontRow.setOrientation(LinearLayout.HORIZONTAL);
        fontRow.setGravity(Gravity.CENTER_VERTICAL);
        fontRow.setPadding(0, 30, 0, 20);

        TextView tvFontLabel = new TextView(this);
        tvFontLabel.setText("字号大小");
        tvFontLabel.setTextColor(getResources().getColor(R.color.fg2));
        tvFontLabel.setTextSize(13);
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        fontRow.addView(tvFontLabel, lpLabel);

        Button btnDec = new Button(this);
        btnDec.setText("A -");
        btnDec.setBackgroundResource(R.drawable.bg_btn);
        btnDec.setTextColor(getResources().getColor(R.color.fg));

        final TextView tvSizeVal = new TextView(this);
        tvSizeVal.setText(currentFontSize + " sp");
        tvSizeVal.setTextColor(getResources().getColor(R.color.accent));
        tvSizeVal.setTextSize(14);
        tvSizeVal.setGravity(Gravity.CENTER);
        tvSizeVal.setPadding(30, 0, 30, 0);

        Button btnInc = new Button(this);
        btnInc.setText("A +");
        btnInc.setBackgroundResource(R.drawable.bg_btn);
        btnInc.setTextColor(getResources().getColor(R.color.fg));

        btnDec.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyFontSize(currentFontSize - 1);
                tvSizeVal.setText(currentFontSize + " sp");
            }
        });
        btnInc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyFontSize(currentFontSize + 1);
                tvSizeVal.setText(currentFontSize + " sp");
            }
        });

        fontRow.addView(btnDec);
        fontRow.addView(tvSizeVal);
        fontRow.addView(btnInc);
        root.addView(fontRow);

        // 主题选择行
        TextView tvThemeLabel = new TextView(this);
        tvThemeLabel.setText("背景主题");
        tvThemeLabel.setTextColor(getResources().getColor(R.color.fg2));
        tvThemeLabel.setTextSize(13);
        tvThemeLabel.setPadding(0, 10, 0, 15);
        root.addView(tvThemeLabel);

        LinearLayout themeRow = new LinearLayout(this);
        themeRow.setOrientation(LinearLayout.HORIZONTAL);

        final String[] themes = {"dark", "sepia", "sage", "oled"};
        final String[] themeNames = {"暗夜", "羊皮纸", "豆沙绿", "极黑"};

        for (int i = 0; i < themes.length; i++) {
            final String th = themes[i];
            Button b = new Button(this);
            b.setText(themeNames[i]);
            b.setTextSize(12);
            b.setBackgroundResource(R.drawable.bg_btn);
            b.setTextColor(getResources().getColor(R.color.fg));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 100, 1f);
            if (i > 0) lp.leftMargin = 12;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyTheme(th);
                }
            });
            themeRow.addView(b, lp);
        }
        root.addView(themeRow);

        builder.setView(root);
        builder.setPositiveButton("完成", null);
        builder.show();
    }

    private static final int REQ_NOTIF = 9;
    private boolean playAfterPerm = false;

    private void onPlayClicked() {
        if (previewMode) {
            Toast.makeText(this, "试读模式不保存书籍；加入书架后可使用整本朗读和续读", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            playAfterPerm = true;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            return;
        }
        doPlay();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_NOTIF && playAfterPerm) {
            playAfterPerm = false;
            doPlay();
        }
    }

    private void doPlay() {
        if (TtsService.RUNNING && TtsService.BID == bid && TtsService.IDX == idx) {
            TtsService.toggle(this);
        } else {
            TtsService.start(this, bid, idx, bookTitle, count);
        }
    }

    /** 长按正文任一行，明确从这一行开始朗读。 */
    private void startReadingFromTouchedLine() {
        String picked = lineAt(lastTouchedOffset);
        if (picked.isEmpty()) {
            Toast.makeText(this, "请先点在正文某一行上", Toast.LENGTH_SHORT).show();
            return;
        }
        final String from = picked;
        new AlertDialog.Builder(this)
                .setTitle("从这里开始朗读")
                .setMessage(from.length() > 80 ? from.substring(0, 80) + "…" : from)
                .setNegativeButton("取消", null)
                .setPositiveButton("从这一行开始读", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        TtsService.start(ReaderActivity.this, bid, idx, bookTitle, count, from);
                    }
                }).show();
    }

    private String lineAt(int offset) {
        if (bodyRaw == null || bodyRaw.isEmpty()) return "";
        int at = Math.max(0, Math.min(offset, bodyRaw.length() - 1));
        int start = bodyRaw.lastIndexOf("\n", at);
        int end = bodyRaw.indexOf("\n", at);
        String line = bodyRaw.substring(start < 0 ? 0 : start + 1, end < 0 ? bodyRaw.length() : end).trim();
        return line.replaceFirst("^[　\\s]+", "");
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean mine = TtsService.RUNNING && TtsService.BID == bid;
            if (mine && TtsService.IDX != idx) {
                idx = TtsService.IDX;
                load();
            }
            if (mine) {
                btnPlay.setText(TtsService.PAUSED ? "继续朗读" : "暂停朗读");
                highlightSpeaking(TtsService.CURRENT_CHUNK);
            } else {
                btnPlay.setText("开始朗读");
                if (!lastHighlighted.isEmpty()) highlightSpeaking("");
            }
            ui.postDelayed(this, 700);
        }
    };

    private void highlightSpeaking(String spoken) {
        if (spoken == null) spoken = "";
        if (spoken.equals(lastHighlighted)) return;
        lastHighlighted = spoken;
        SpannableString styled = new SpannableString(bodyRaw == null ? "" : bodyRaw);
        if (!spoken.isEmpty()) {
            String key = spoken.trim();
            int start = bodyRaw.indexOf(key);
            if (start < 0 && key.length() > 18) start = bodyRaw.indexOf(key.substring(0, 18));
            if (start >= 0) {
                int end = Math.min(bodyRaw.length(), start + key.length());
                styled.setSpan(new BackgroundColorSpan(getResources().getColor(R.color.accent)), start, end, 33);
                final int at = start;
                tvBody.setText(styled);
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            int y = tvBody.getLayout().getLineTop(tvBody.getLayout().getLineForOffset(at));
                            sv.smoothScrollTo(0, Math.max(0, y - sv.getHeight() / 3));
                        } catch (Exception ignored) { }
                    }
                }, 40);
                return;
            }
        }
        tvBody.setText(styled);
    }

    private void openNextChapterAtBottom() {
        View child = sv.getChildAt(0);
        if (child == null || count <= 0 || idx + 1 >= count) return;
        if (sv.getScrollY() + sv.getHeight() >= child.getHeight() - 24) {
            go(idx + 1);
        }
    }

    private void go(int i) {
        if (i < 0 || (count > 0 && i >= count)) return;
        idx = i;
        pendingOff = -1;
        load();
    }

    private void load() {
        final String loadingCh = "第 " + (idx + 1) + " 章";
        tvChTitle.setText(loadingCh);
        if (tvBodyTitle != null) tvBodyTitle.setText(loadingCh);
        tvBody.setText("加载章节中…");

        final int want = idx;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject r;
                    if (previewMode) {
                        JSONObject ch = previewToc.optJSONObject(want);
                        if (ch == null) throw new Api.Err(404, "没有第 " + (want + 1) + " 章");
                        JSONObject body = new JSONObject();
                        body.put("sid", previewSid);
                        body.put("url", ch.optString("url", ""));
                        body.put("name", ch.optString("name", "第 " + (want + 1) + " 章"));
                        body.put("book", previewBookUrl);
                        r = Api.post("/api/src/preview/ch", body);
                    } else {
                        r = Api.json("/api/books/" + bid + "/ch/" + want);
                    }
                    final JSONArray ps = r.optJSONArray("paras");
                    final String t = r.optString("title", loadingCh);
                    final int n = previewMode ? previewToc.length() : r.optInt("count", count);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            count = n;
                            paras.clear();
                            StringBuilder sb = new StringBuilder();
                            if (ps != null) {
                                for (int i = 0; i < ps.length(); i++) {
                                    String p = ps.optString(i, "").trim();
                                    if (p.isEmpty()) continue;
                                    // 经典两空格缩进，排版舒适
                                    if (!p.startsWith("　　") && !p.startsWith("\u3000\u3000")) {
                                        p = "\u3000\u3000" + p;
                                    }
                                    paras.add(p);
                                    sb.append(p).append("\n\n");
                                }
                            }
                            tvChTitle.setText(t);
                            if (tvBodyTitle != null) tvBodyTitle.setText(t);
                            bodyRaw = sb.length() == 0 ? "（本章暂无正文或书源正忙，请重试或换源）" : sb.toString();
                            lastHighlighted = "";
                            tvBody.setText(bodyRaw);

                            if (tvProgressInfo != null) {
                                int total = (count > 0 ? count : (idx + 1));
                                int pct = Math.min(100, (idx + 1) * 100 / Math.max(1, total));
                                tvProgressInfo.setText("第 " + (idx + 1) + " / " + total + " 章 · " + pct + "%");
                            }

                            sv.scrollTo(0, 0);
                            if (pendingOff > 0) {
                                final float f = pendingOff;
                                pendingOff = -1;
                                ui.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        View child = sv.getChildAt(0);
                                        if (child == null) return;
                                        int max = Math.max(1, child.getHeight() - sv.getHeight());
                                        sv.scrollTo(0, (int) (max * f));
                                    }
                                }, 80);
                            }
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (e instanceof Api.Err && ((Api.Err) e).isAuth()) {
                                Api.goLogin(ReaderActivity.this);
                                return;
                            }
                            tvBody.setText("加载失败：" + Api.errText(e));
                        }
                    });
                }
            }
        });
    }

    private float scrollFraction() {
        View child = sv.getChildAt(0);
        if (child == null) return 0f;
        int max = child.getHeight() - sv.getHeight();
        if (max <= 0) return 0f;
        return Math.max(0f, Math.min(1f, sv.getScrollY() / (float) max));
    }

    private void saveProgress() {
        if (previewMode) return;
        final int ch = idx;
        final float off = scrollFraction();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("ch", ch);
                    body.put("off", Math.round(off * 1000) / 1000.0);
                    Api.post("/api/books/" + bid + "/progress", body);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        saveProgress();
        pool.shutdown();
        super.onDestroy();
    }
}
