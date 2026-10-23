package com.ttsnover.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 后台朗读：ForegroundService + MediaSession + MediaPlayer。
 * 熄屏/锁屏继续播，通知栏可上一章/暂停/下一章/停止，播完自动下一章。
 * 文本按 Chunker 切段，逐段 POST /api/tts 取 mp3，播放时预取下一段。
 */
public class TtsService extends Service {

    private static final String TAG = "Tts";
    private static final String CH_ID = "tts_play";
    private static final int NOTI_ID = 4101;

    public static final String A_START = "tts.start";
    public static final String A_TOGGLE = "tts.toggle";
    public static final String A_NEXT = "tts.next";
    public static final String A_PREV = "tts.prev";
    public static final String A_STOP = "tts.stop";

    public static volatile boolean RUNNING = false;
    public static volatile boolean PAUSED = false;
    public static volatile int BID = -1;
    public static volatile int IDX = -1;
    /** 当前正在合成/播放的文本块，供阅读页定位和高亮；空串表示未朗读。 */
    public static volatile String CURRENT_CHUNK = "";

    private final Object lock = new Object();
    private final ExecutorService synthPool = Executors.newSingleThreadExecutor();
    private MediaPlayer player;
    private MediaSession session;
    private PowerManager.WakeLock wakelock;
    private Thread worker;

    private volatile boolean alive = false;
    private volatile boolean paused = false;
    private volatile boolean abortChunk = false;

    private int bid, idx, count;
    private String bookTitle = "";
    private String chTitle = "";
    private List<String> chunks = new ArrayList<String>();
    private int pos = 0;
    /** 仅本次启动的第一章生效，随后章节自然从头连续朗读。 */
    private String startFrom = "";
    private Future<byte[]> prefetched;
    private String prefetchedFor = "";

    // ---------------- 静态入口 ----------------

    public static void start(Context c, int bid, int idx, String title, int count) {
        start(c, bid, idx, title, count, "");
    }

    /** 从当前章节中包含 startFrom 的文本块起朗读；空字符串代表整章开头。 */
    public static void start(Context c, int bid, int idx, String title, int count, String startFrom) {
        Intent i = new Intent(c, TtsService.class);
        i.setAction(A_START);
        i.putExtra("bid", bid);
        i.putExtra("idx", idx);
        i.putExtra("title", title);
        i.putExtra("count", count);
        i.putExtra("startFrom", startFrom == null ? "" : startFrom);
        go(c, i);
    }

    public static void toggle(Context c) {
        Intent i = new Intent(c, TtsService.class);
        i.setAction(A_TOGGLE);
        go(c, i);
    }

    public static void stopAll(Context c) {
        Intent i = new Intent(c, TtsService.class);
        i.setAction(A_STOP);
        try {
            c.startService(i);
        } catch (Exception ignored) {
        }
    }

    private static void go(Context c, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "start failed: " + e);
        }
    }

    /** 试听：不占服务，播完自动释放。 */
    public static void playOnce(Context c, final byte[] audio) {
        try {
            final File f = new File(c.getCacheDir(), "tts_preview.mp3");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(audio);
            fo.close();
            final MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            mp.start();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    m.release();
                    f.delete();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "preview failed: " + e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ttsnover:tts");
        wakelock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        String act = it == null ? null : it.getAction();
        if (act == null) return START_NOT_STICKY;

        if (A_STOP.equals(act)) {
            shutdown();
            return START_NOT_STICKY;
        }
        if (A_TOGGLE.equals(act)) {
            if (!alive) return START_NOT_STICKY;
            paused = !paused;
            PAUSED = paused;
            synchronized (lock) {
                if (!paused) {
                    lock.notifyAll();
                }
            }
            if (paused) pausePlayer();
            else resumePlayer();
            updateNotification();
            pushState();
            return START_NOT_STICKY;
        }
        if (A_NEXT.equals(act) || A_PREV.equals(act)) {
            if (!alive) return START_NOT_STICKY;
            int target = idx + (A_NEXT.equals(act) ? 1 : -1);
            if (target < 0) target = 0;
            if (count > 0 && target >= count) target = count - 1;
            jumpTo(target);
            return START_NOT_STICKY;
        }

        // A_START
        bid = it.getIntExtra("bid", bid);
        idx = it.getIntExtra("idx", idx);
        count = it.getIntExtra("count", count);
        String t = it.getStringExtra("title");
        if (t != null && !t.isEmpty()) bookTitle = t;
        String requestedStart = it.getStringExtra("startFrom");

        ensureChannel();
        startFg();
        sessionSetup();

        if (!alive) {
            alive = true;
            RUNNING = true;
            BID = bid;
            IDX = idx;
            PAUSED = false;
            paused = false;
            startFrom = requestedStart == null ? "" : requestedStart.trim();
            CURRENT_CHUNK = "";
            wakelock.acquire(4 * 60 * 60 * 1000L);
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "tts-worker");
            worker.start();
        } else {
            BID = bid;
            startFrom = requestedStart == null ? "" : requestedStart.trim();
            CURRENT_CHUNK = "";
            jumpTo(idx);
        }
        updateNotification();
        pushState();
        return START_NOT_STICKY;
    }

    private void startFg() {
        Notification n = buildNotification("准备朗读…");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTI_ID, n);
        }
    }

    // ---------------- 播放循环 ----------------

    private void loop() {
        try {
            while (alive) {
                if (!waitIfPaused()) break;
                if (pos >= chunks.size()) {
                    boolean first = chunks.isEmpty();
                    int want = first ? idx : idx + 1;
                    boolean ok = loadChapter(want);
                    if (!ok) {
                        for (int k = 1; k <= 2 && alive; k++) {
                            if (count > 0 && want + k >= count) break;
                            if (loadChapter(want + k)) {
                                ok = true;
                                break;
                            }
                        }
                    }
                    if (!ok) break;
                }
                if (!alive) break;
                if (!waitIfPaused()) break;
                String text = chunks.get(pos);
                CURRENT_CHUNK = text;
                byte[] audio;
                try {
                    audio = takeAudio(text);
                } catch (Exception e) {
                    Log.w(TAG, "synth failed: " + e);
                    notify("朗读失败：" + Api.errText(e));
                    break;
                }
                prefetchNext();
                if (!playBlocking(audio)) break;
                pos++;
                saveProgress();
            }
        } catch (Exception e) {
            Log.w(TAG, "loop error", e);
        } finally {
            if (alive) shutdown();
        }
    }

    private boolean waitIfPaused() {
        synchronized (lock) {
            while (alive && paused) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    return alive;
                }
            }
        }
        return alive;
    }

    private byte[] takeAudio(String text) throws Exception {
        if (prefetched != null && text.equals(prefetchedFor)) {
            try {
                byte[] b = prefetched.get(1, TimeUnit.SECONDS);
                prefetched = null;
                prefetchedFor = "";
                if (b != null && b.length > 512) return b;
            } catch (Exception ignored) {
            }
        }
        return synth(text);
    }

    private void prefetchNext() {
        if (pos + 1 >= chunks.size()) return;
        final String next = chunks.get(pos + 1);
        prefetchedFor = next;
        prefetched = synthPool.submit(new Callable<byte[]>() {
            @Override
            public byte[] call() {
                try {
                    return synth(next);
                } catch (Exception e) {
                    return null;
                }
            }
        });
    }

    private byte[] synth(String text) throws Exception {
        JSONObject body = new JSONObject();
        body.put("text", text);
        if (!Prefs.ttsProvider().isEmpty()) body.put("provider", Prefs.ttsProvider());
        if (!Prefs.ttsVoice().isEmpty()) body.put("voice", Prefs.ttsVoice());
        body.put("speed", Prefs.ttsSpeed());
        return Api.bytes("/api/tts", body, 300000);
    }

    private boolean loadChapter(int want) {
        try {
            JSONObject r = Api.json("/api/books/" + bid + "/ch/" + want);
            JSONArray ps = r.optJSONArray("paras");
            List<String> list = new ArrayList<String>();
            if (ps != null) {
                for (int i = 0; i < ps.length(); i++) {
                    String p = ps.optString(i, "").trim();
                    if (!p.isEmpty()) list.add(p);
                }
            }
            List<String> c = Chunker.split(list);
            // A line selected in the reader starts from the chunk that contains it.
            if (!startFrom.isEmpty()) {
                c = cutFrom(c, startFrom);
                startFrom = "";
            }
            if (c.isEmpty()) return false;
            chunks = c;
            pos = 0;
            idx = want;
            IDX = idx;
            chTitle = r.optString("title", "第 " + (want + 1) + " 章");
            int n = r.optInt("count", count);
            if (n > 0) count = n;
            updateNotification();
            pushState();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "load chapter " + want + " failed: " + e);
            return false;
        }
    }

    /** 删除点选行前的语块；找不到时稳妥地从本章开头开始。 */
    static List<String> cutFrom(List<String> chunks, String startFrom) {
        if (chunks == null || chunks.isEmpty() || startFrom == null || startFrom.trim().isEmpty()) return chunks;
        String key = startFrom.trim();
        for (int i = 0; i < chunks.size(); i++) {
            String c = chunks.get(i);
            if (c.contains(key) || key.contains(c)) {
                return new ArrayList<String>(chunks.subList(i, chunks.size()));
            }
        }
        return chunks;
    }

    private boolean playBlocking(byte[] audio) {
        File f = new File(getCacheDir(), "tts_now.mp3");
        try {
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(audio);
            fo.close();
            MediaPlayer mp = new MediaPlayer();
            abortChunk = false;
            synchronized (lock) {
                player = mp;
            }
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            final CountDownLatch done = new CountDownLatch(1);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    done.countDown();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    done.countDown();
                    return true;
                }
            });
            mp.start();
            setPlaying(true);
            while (alive && !abortChunk && done.getCount() > 0) {
                try {
                    done.await(300, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                }
                if (paused) pausePlayer();
            }
            synchronized (lock) {
                player = null;
            }
            try {
                mp.release();
            } catch (Exception ignored) {
            }
            f.delete();
            return alive;
        } catch (Exception e) {
            Log.w(TAG, "play failed: " + e);
            return alive;
        }
    }

    private void pausePlayer() {
        synchronized (lock) {
            if (player != null) {
                try {
                    if (player.isPlaying()) player.pause();
                } catch (Exception ignored) {
                }
            }
        }
        setPlaying(false);
    }

    private void resumePlayer() {
        synchronized (lock) {
            if (player != null) {
                try {
                    player.start();
                } catch (Exception ignored) {
                }
            }
        }
        setPlaying(true);
    }

    private void jumpTo(int target) {
        idx = target;
        IDX = target;
        pos = 0;
        chunks = new ArrayList<String>();
        abortChunk = true;
        synchronized (lock) {
            if (player != null) {
                try {
                    player.stop();
                } catch (Exception ignored) {
                }
            }
            lock.notifyAll();
        }
        updateNotification();
        pushState();
    }

    private void saveProgress() {
        try {
            JSONObject body = new JSONObject();
            body.put("ch", idx);
            body.put("off", chunks.isEmpty() ? 0 : (double) pos / chunks.size());
            Api.post("/api/books/" + bid + "/progress", body);
        } catch (Exception ignored) {
        }
    }

    private void notify(String msg) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTI_ID + 1, buildNotification(msg));
    }

    // ---------------- 通知 / MediaSession ----------------

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "朗读", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void sessionSetup() {
        if (session != null) return;
        session = new MediaSession(this, "TTSNover");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (paused) {
                    paused = false;
                    PAUSED = false;
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                    resumePlayer();
                    updateNotification();
                }
            }

            @Override
            public void onPause() {
                paused = true;
                PAUSED = true;
                pausePlayer();
                updateNotification();
            }

            @Override
            public void onSkipToNext() {
                Intent i = new Intent(TtsService.this, TtsService.class);
                i.setAction(A_NEXT);
                onStartCommand(i, 0, 0);
            }

            @Override
            public void onSkipToPrevious() {
                Intent i = new Intent(TtsService.this, TtsService.class);
                i.setAction(A_PREV);
                onStartCommand(i, 0, 0);
            }

            @Override
            public void onStop() {
                shutdown();
            }
        });
        session.setActive(true);
        setPlaying(false);
    }

    private void setPlaying(boolean playing) {
        if (session == null) return;
        PlaybackState st = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(paused ? PlaybackState.STATE_PAUSED : (playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_NONE),
                        pos, 1f)
                .build();
        session.setPlaybackState(st);
    }

    private void pushState() {
        if (session != null) {
            android.media.MediaMetadata.Builder md = new android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, chTitle)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, bookTitle)
                    .putLong(android.media.MediaMetadata.METADATA_KEY_TRACK_NUMBER, idx + 1);
            session.setMetadata(md.build());
        }
    }

    private PendingIntent pi(String action) {
        Intent i = new Intent(this, TtsService.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, action.hashCode(), i, flags);
    }

    private Notification buildNotification(String sub) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, ReaderActivity.class);
        open.putExtra("bid", bid);
        open.putExtra("idx", idx);
        open.putExtra("title", bookTitle);
        open.putExtra("count", count);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pf |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(this, 7, open, pf);

        b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle(bookTitle)
                .setContentText(chTitle.isEmpty() ? sub : chTitle + " · " + (paused ? "已暂停" : "朗读中"))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);

        b.addAction(R.drawable.ic_note, "上一章", pi(A_PREV));
        b.addAction(paused ? R.drawable.ic_play : R.drawable.ic_pause,
                paused ? "继续" : "暂停", pi(A_TOGGLE));
        b.addAction(R.drawable.ic_note, "下一章", pi(A_NEXT));
        b.addAction(R.drawable.ic_note, "停止", pi(A_STOP));

        if (session != null && Build.VERSION.SDK_INT >= 21) {
            Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
            st.setMediaSession(session.getSessionToken());
            b.setStyle(st);
        }
        return b.build();
    }

    private void updateNotification() {
        if (!alive && RUNNING) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTI_ID, buildNotification("朗读中"));
        } catch (Exception ignored) {
        }
    }

    private void shutdown() {
        alive = false;
        RUNNING = false;
        PAUSED = false;
        CURRENT_CHUNK = "";
        paused = false;
        synchronized (lock) {
            if (player != null) {
                try {
                    player.stop();
                } catch (Exception ignored) {
                }
            }
            lock.notifyAll();
        }
        try {
            if (session != null) {
                session.setActive(false);
                session.release();
                session = null;
            }
        } catch (Exception ignored) {
        }
        try {
            if (wakelock != null && wakelock.isHeld()) wakelock.release();
        } catch (Exception ignored) {
        }
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        alive = false;
        RUNNING = false;
        try {
            synthPool.shutdownNow();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
