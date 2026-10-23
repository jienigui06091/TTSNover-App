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
import android.view.Gravity;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_reader);
        bid = getIntent().getIntExtra("bid", 0);
        idx = getIntent().getIntExtra("idx", 0);
        count = getIntent().getIntExtra("count", 0);
        bookTitle = getIntent().getStringExtra("title");
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
            tvBookSubtitle.setText(bookTitle);
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
        sv.setOnClickListener(toggleListener);

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
            } else {
                btnPlay.setText("开始朗读");
            }
            ui.postDelayed(this, 700);
        }
    };

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
                    final JSONObject r = Api.json("/api/books/" + bid + "/ch/" + want);
                    final JSONArray ps = r.optJSONArray("paras");
                    final String t = r.optString("title", loadingCh);
                    final int n = r.optInt("count", count);
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
                            tvBody.setText(sb.length() == 0 ? "（本章暂无正文或书源正忙，请重试或换源）" : sb.toString());

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
