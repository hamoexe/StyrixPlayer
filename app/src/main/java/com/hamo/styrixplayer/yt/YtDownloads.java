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
import com.hamo.styrixplayer.ui.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.ui.MainActivity;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Saves a YouTube Music song to the phone (Music/StyrixPlayer) with a progress notification. */
public final class YtDownloads {

    private static final String CHANNEL = "downloads";

    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static final Set<String> active = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private YtDownloads() {}

    /** Called on the main thread whenever a download finishes (success or not). */
    public static void addListener(Runnable r) { listeners.add(r); }

    public static void removeListener(Runnable r) { listeners.remove(r); }

    public static boolean isActive(String videoId) { return active.contains(videoId); }

    private static void toast(Context c, String msg) {
        main.post(() -> Toast.makeText(c, com.hamo.styrixplayer.I18n.t(msg), Toast.LENGTH_SHORT).show());
    }

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
    private static void notify(Context c, int id, NotificationCompat.Builder b) {
        try {
            NotificationManagerCompat.from(c).notify(id, b.build());
        } catch (SecurityException ignored) {
            // notifications are switched off: the download still runs
        }
    }

    public static void start(Context ctx, String videoId, String title, String artist,
                             String thumbUrl) {
        final Context app = ctx.getApplicationContext();
        if (YtLocal.songIdFor(app, videoId) > 0) {
            toast(app, "Already on your phone");
            return;
        }
        if (!active.add(videoId)) {
            toast(app, "Already downloading");
            return;
        }
        ensureChannel(app);
        final int nid = videoId.hashCode();
        final String shown = title == null || title.isEmpty() ? "YouTube song" : title;
        notify(app, nid, builder(app, shown).setContentText(com.hamo.styrixplayer.I18n.t("Starting download…"))
                .setOngoing(true).setProgress(0, 0, true));
        toast(app, "Downloading…");

        pool.execute(() -> {
            String error = null;
            try {
                YouTubeResolver.Info info = YouTubeResolver.resolve(videoId);
                if (title != null && !title.isEmpty()) info.title = title;
                if (artist != null && !artist.isEmpty()) info.author = artist;
                if (thumbUrl != null && !thumbUrl.isEmpty()) info.coverUrl = thumbUrl;
                final int[] last = {-1};
                AudioDownloader.download(app, info, (done, total) -> {
                    if (total <= 0) return;
                    int pct = (int) (done * 100 / total);
                    if (pct == last[0]) return;
                    last[0] = pct;
                    notify(app, nid, builder(app, shown).setContentText(com.hamo.styrixplayer.I18n.t("Downloading… " + pct + "%"))
                            .setOngoing(true).setProgress(100, pct, false));
                }, new AtomicBoolean(false));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                active.remove(videoId);
            }
            if (error == null) {
                notify(app, nid, builder(app, shown).setContentText(com.hamo.styrixplayer.I18n.t(AudioDownloader.lastEmbedded ? "Saved to Music/StyrixPlayer" : "Saved to Music/StyrixPlayer (tags only in the app)"))
                        .setOngoing(false).setAutoCancel(true).setProgress(0, 0, false));
            } else {
                notify(app, nid, builder(app, shown).setContentText(com.hamo.styrixplayer.I18n.t("Download failed: " + error))
                        .setOngoing(false).setAutoCancel(true).setProgress(0, 0, false));
                toast(app, "Download failed: " + error);
            }
            main.post(() -> {
                for (Runnable r : listeners) r.run();
            });
        });
    }
}
