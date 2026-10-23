package com.ttsnover.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

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

    private TextView tvChTitle, tvBody;
    private ScrollView sv;
    private Button btnPrev, btnNext, btnPlay;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_reader);
        bid = getIntent().getIntExtra("bid", 0);
        idx = getIntent().getIntExtra("idx", 0);
        count = getIntent().getIntExtra("count", 0);
        bookTitle = getIntent().getStringExtra("title");
        pendingOff = getIntent().getFloatExtra("off", -1f);

        tvChTitle = findViewById(R.id.tvChTitle);
        tvBody = findViewById(R.id.tvBody);
        sv = findViewById(R.id.svBody);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        btnPlay = findViewById(R.id.btnPlay);

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
        findViewById(R.id.btnToc).setOnClickListener(new View.OnClickListener() {
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

        load();
        ui.postDelayed(tick, 600);
    }

    private static final int REQ_NOTIF = 9;
    private boolean playAfterPerm = false;

    private void onPlayClicked() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            // 先要权限：没有通知就没法常驻前台，熄屏会被系统掐掉
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
                // 朗读自动跳到下一章：阅读界面跟着翻
                idx = TtsService.IDX;
                load();
            }
            if (mine) {
                btnPlay.setText(TtsService.PAUSED ? "继续" : "暂停");
            } else {
                btnPlay.setText("朗读");
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
        tvChTitle.setText("第 " + (idx + 1) + " 章");
        tvBody.setText("加载中…");
        final int want = idx;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject r = Api.json("/api/books/" + bid + "/ch/" + want);
                    final JSONArray ps = r.optJSONArray("paras");
                    final String t = r.optString("title");
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
                                    paras.add(p);
                                    sb.append(p).append("\n\n");
                                }
                            }
                            tvChTitle.setText(t);
                            tvBody.setText(sb.length() == 0 ? "（本章没有正文，可能源失效了）" : sb.toString());
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
