package com.hamo.styrixplayer.yt;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.hamo.styrixplayer.I18n;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.ui.MainActivity;
import com.hamo.styrixplayer.ui.Toast;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One place for every download (songs from YouTube Music, audio and video from the Download page).
 * Jobs run one after another; each has a progress state the UI can watch and a notification that is
 * updated at a calm pace. (Android throws away notification updates that come too fast, which is
 * what left the old notification stuck on "Downloading 100%": the final update got dropped.)
 */
public final class YtDownloads {

    public static final int QUEUED = 0, RUNNING = 1, DONE = 2, FAILED = 3, CANCELLED = 4;

    /** What a running job reports to. */
    public interface Reporter {
        void progress(long done, long total);

        /** A step without a percentage ("Merging…"); null goes back to the normal progress text. */
        void stage(String text);

        AtomicBoolean cancelFlag();
    }

    /** The work itself. Returns the text to show when it is saved, or null for the default. */
    public interface Job {
        String run(Reporter r) throws Exception;
    }

    public static final class Task {
        public final String key, title;
        public volatile int state = QUEUED;
        public volatile int percent = -1;        // -1: unknown
        public volatile String stage, message;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        long lastPost;
        int lastPct = -2;

        Task(String key, String title) {
            this.key = key;
            this.title = title;
        }

        public boolean finished() { return state >= DONE; }
    }

    /** Called on the main thread when a task starts, moves on a percent, or ends. */
    public interface Listener {
        void onChanged(Task t);
    }

    private static final String CHANNEL = "downloads";
    private static final long MIN_GAP = 600;      // ms between notification updates

    // Background priority: looking a video up (NewPipe runs a JS interpreter) is heavy CPU work and
    // must not fight the UI thread, which is what made the whole app feel slow when you pressed download.
    private static final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "downloads");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private static final CopyOnWriteArraySet<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static final CopyOnWriteArraySet<Listener> watchers = new CopyOnWriteArraySet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private YtDownloads() {}

    /** Called on the main thread whenever a download finishes (success or not). */
    public static void addListener(Runnable r) { listeners.add(r); }

    public static void removeListener(Runnable r) { listeners.remove(r); }

    public static void addWatcher(Listener l) { watchers.add(l); }

    public static void removeWatcher(Listener l) { watchers.remove(l); }

    public static boolean isActive(String key) {
        Task t = tasks.get(key);
        return t != null && !t.finished();
    }

    /** The task for this key while it is queued or running, else null. */
    public static Task get(String key) {
        Task t = tasks.get(key);
        return t != null && !t.finished() ? t : null;
    }

    public static void cancel(String key) {
        Task t = tasks.get(key);
        if (t != null) t.cancel.set(true);
    }

    private static void toast(Context c, String msg) {
        main.post(() -> Toast.makeText(c, I18n.t(msg), Toast.LENGTH_SHORT).show());
    }

    // ---------- notifications ----------

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Downloads",
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    private static NotificationCompat.Builder builder(Context c, String title) {
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(title)
                .setContentIntent(open)
                .setOnlyAlertOnce(true);
    }

    @SuppressLint("MissingPermission")
    private static void post(Context c, int id, NotificationCompat.Builder b) {
        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
        } catch (SecurityException ignored) {
            // notifications are switched off: the download still runs
        }
    }

    private static void postProgress(Context app, Task t, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - t.lastPost < MIN_GAP) return;
        t.lastPost = now;
        String text;
        boolean indeterminate = t.percent < 0 || t.stage != null;
        if (t.state == QUEUED) text = I18n.t("Waiting…");
        else if (t.stage != null) text = I18n.t(t.stage);
        else if (t.percent < 0) text = I18n.t("Starting download…");
        else text = I18n.t("Downloading…") + " " + t.percent + "%";
        NotificationCompat.Builder b = builder(app, t.title).setContentText(text).setOngoing(true)
                .setProgress(indeterminate ? 0 : 100, Math.max(t.percent, 0), indeterminate);
        post(app, t.key.hashCode(), b);
    }

    /** The last update is held back until the rate limit has passed, and sent twice to be safe. */
    private static void postFinal(Context app, Task t, String text) {
        final int id = t.key.hashCode();
        final Runnable send = () -> post(app, id, builder(app, t.title).setContentText(text)
                .setOngoing(false).setAutoCancel(true).setProgress(0, 0, false));
        long wait = Math.max(0, t.lastPost + MIN_GAP + 150 - System.currentTimeMillis());
        main.postDelayed(send, wait);
        main.postDelayed(send, wait + 1500);
    }

    private static void changed(Task t) {
        main.post(() -> {
            for (Listener l : watchers) l.onChanged(t);
        });
    }

    // ---------- running ----------

    /** Queues a job. Returns null (and says why) when the same key is already queued or running. */
    public static Task submit(Context ctx, String key, String title, Job job) {
        final Context app = ctx.getApplicationContext();
        final String shown = title == null || title.isEmpty() ? "YouTube download" : title;
        final Task t = new Task(key, shown);
        synchronized (tasks) {
            Task old = tasks.get(key);
            if (old != null && !old.finished()) {
                toast(app, "Already downloading");
                return null;
            }
            tasks.put(key, t);
        }
        ensureChannel(app);
        postProgress(app, t, true);
        changed(t);

        pool.execute(() -> {
            if (t.cancel.get()) {
                end(app, t, CANCELLED, "Cancelled");
                return;
            }
            t.state = RUNNING;
            postProgress(app, t, true);
            changed(t);
            final Reporter rep = new Reporter() {
                @Override public void progress(long done, long total) {
                    if (total <= 0) return;
                    int pct = (int) Math.min(100, done * 100 / total);
                    if (pct == t.lastPct && t.stage == null) return;
                    t.lastPct = pct;
                    t.percent = pct;
                    t.stage = null;
                    postProgress(app, t, false);
                    changed(t);
                }

                @Override public void stage(String text) {
                    t.stage = text;
                    postProgress(app, t, true);
                    changed(t);
                }

                @Override public AtomicBoolean cancelFlag() { return t.cancel; }
            };
            try {
                String msg = job.run(rep);
                end(app, t, DONE, msg == null ? "Saved" : msg);
            } catch (AudioDownloader.Cancelled e) {
                end(app, t, CANCELLED, "Cancelled");
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                end(app, t, FAILED, "Download failed: " + m);
                toast(app, "Download failed: " + m);
            }
        });
        return t;
    }

    private static void end(Context app, Task t, int state, String message) {
        t.message = message;
        t.stage = null;
        t.percent = state == DONE ? 100 : t.percent;
        t.state = state;
        postFinal(app, t, I18n.t(message));
        main.post(() -> {
            for (Runnable r : listeners) r.run();
            for (Listener l : watchers) l.onChanged(t);
        });
    }

    /** A YouTube Music song to Music/StyrixPlayer, with the title, artist and cover from the list. */
    public static void start(Context ctx, String videoId, String title, String artist,
                             String thumbUrl) {
        final Context app = ctx.getApplicationContext();
        if (YtLocal.songIdFor(app, videoId) > 0) {
            toast(app, "Already on your phone");
            return;
        }
        if (submit(app, videoId, title, r -> {
            YouTubeResolver.Info info = YouTubeResolver.resolve(videoId);
            if (title != null && !title.isEmpty()) info.title = title;
            if (artist != null && !artist.isEmpty()) info.author = artist;
            if (thumbUrl != null && !thumbUrl.isEmpty()) info.coverUrl = thumbUrl;
            AudioDownloader.download(app, info, r::progress, r.cancelFlag());
            return AudioDownloader.lastEmbedded ? "Saved to Music/StyrixPlayer"
                    : "Saved to Music/StyrixPlayer (tags only in the app)";
        }) != null) {
            toast(app, "Downloading…");
        }
    }
}
