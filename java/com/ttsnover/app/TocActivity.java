package com.ttsnover.reader;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TocActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<JSONObject> chs = new ArrayList<JSONObject>();

    private int bid, cur;
    private String title;
    private TextView tvTitle, tvMeta, btnReverse, btnLocate;
    private ListView lv;
    private ChAdapter adapter;
    private boolean isReversed = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_toc);
        bid = getIntent().getIntExtra("bid", 0);
        cur = getIntent().getIntExtra("cur", 0);
        title = getIntent().getStringExtra("title");
        tvTitle = findViewById(R.id.tvTitle);
        tvMeta = findViewById(R.id.tvMeta);
        btnReverse = findViewById(R.id.btnReverse);
        btnLocate = findViewById(R.id.btnLocate);
        lv = findViewById(R.id.lvCh);

        tvTitle.setText(title == null ? "" : title);
        adapter = new ChAdapter();
        lv.setAdapter(adapter);

        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                JSONObject o = chs.get(pos);
                open(o.optInt("idx", pos), 0);
            }
        });

        lv.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                JSONObject o = chs.get(pos);
                int targetIdx = o.optInt("idx", pos);
                TtsService.start(TocActivity.this, bid, targetIdx, title, chs.size());
                Toast.makeText(TocActivity.this, "开始朗读: " + o.optString("title"), Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        findViewById(R.id.btnRead).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(cur, 0);
            }
        });

        if (btnReverse != null) {
            btnReverse.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    isReversed = !isReversed;
                    Collections.reverse(chs);
                    adapter.notifyDataSetChanged();
                    btnReverse.setText(isReversed ? "正序" : "倒序");
                }
            });
        }

        if (btnLocate != null) {
            btnLocate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    locateCurrent();
                }
            });
        }

        load();
    }

    private void locateCurrent() {
        for (int i = 0; i < chs.size(); i++) {
            if (chs.get(i).optInt("idx", i) == cur) {
                lv.setSelection(i);
                break;
            }
        }
    }

    private void load() {
        tvMeta.setText("加载目录中…");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject r = Api.json("/api/books/" + bid);
                    final JSONArray arr = r.optJSONArray("chapters");
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            chs.clear();
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject o = arr.optJSONObject(i);
                                    if (o != null) chs.add(o);
                                }
                            }
                            if (isReversed) {
                                Collections.reverse(chs);
                            }
                            adapter.notifyDataSetChanged();
                            tvMeta.setText("共 " + chs.size() + " 章 · 读至第 " + (cur + 1) + " 章");
                            locateCurrent();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (e instanceof Api.Err && ((Api.Err) e).isAuth()) {
                                Api.goLogin(TocActivity.this);
                                return;
                            }
                            tvMeta.setText("加载失败：" + Api.errText(e));
                        }
                    });
                }
            }
        });
    }

    private void open(int idx, int off) {
        if (idx < 0) return;
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra("bid", bid);
        i.putExtra("idx", idx);
        i.putExtra("off", off);
        i.putExtra("title", title);
        i.putExtra("count", chs.size());
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (chs.isEmpty()) load();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private class ChAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return chs.size();
        }

        @Override
        public Object getItem(int i) {
            return chs.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View cv, ViewGroup parent) {
            View v = cv;
            if (v == null) {
                v = LayoutInflater.from(TocActivity.this)
                        .inflate(R.layout.item_chapter, parent, false);
            }
            JSONObject o = chs.get(i);
            int idx = o.optInt("idx", i);

            TextView tvCIdx = v.findViewById(R.id.tvCIdx);
            TextView t = v.findViewById(R.id.tvCTitle);
            View tvCHere = v.findViewById(R.id.tvCHere);

            tvCIdx.setText(String.valueOf(idx + 1));
            t.setText(o.optString("title"));

            boolean here = (idx == cur);
            if (here) {
                t.setTextColor(getResources().getColor(R.color.accent));
                t.setTypeface(null, Typeface.BOLD);
                tvCIdx.setTextColor(getResources().getColor(R.color.accent));
                tvCIdx.setBackgroundResource(R.drawable.bg_tag_accent);
                if (tvCHere != null) tvCHere.setVisibility(View.VISIBLE);
            } else {
                t.setTextColor(getResources().getColor(R.color.fg));
                t.setTypeface(null, Typeface.NORMAL);
                tvCIdx.setTextColor(getResources().getColor(R.color.fg3));
                tvCIdx.setBackgroundResource(R.drawable.bg_tag);
                if (tvCHere != null) tvCHere.setVisibility(View.GONE);
            }
            return v;
        }
    }
}
