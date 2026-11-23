package com.hamo.styrixplayer.playback;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.hamo.styrixplayer.I18n;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Prefs;
import com.hamo.styrixplayer.ui.MainActivity;
import com.hamo.styrixplayer.yt.NpDownloader;
import com.hamo.styrixplayer.yt.YtStreams;
import com.hamo.styrixplayer.yt.YtTrack;

import java.io.IOException;

/**
 * Owns the player. Media3 provides the notification, lock screen controls and
 * headset buttons. The service also owns the sleep timer, saves the session for
 * "resume", and applies per-track speed.
 */
public class PlaybackService extends MediaSessionService {

    /** Heart and download buttons in the media notification. */
    private static final String ACTION_FAV = "com.hamo.styrixplayer.NOTIF_FAV";
    private static final String ACTION_DL = "com.hamo.styrixplayer.NOTIF_DL";
    private static final SessionCommand FAV_COMMAND = new SessionCommand(ACTION_FAV, Bundle.EMPTY);
    private static final SessionCommand DL_COMMAND = new SessionCommand(ACTION_DL, Bundle.EMPTY);
    private static PlaybackService instance;
    private com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton> layout =
            com.google.common.collect.ImmutableList.of();
    private String layoutKey = "";

    /** Re-reads heart / download state (call after a like or favorite changes elsewhere). */
    public static void refreshButtons() {
        final PlaybackService s = instance;
        if (s != null) s.scheduleLayout();
    }


    public static final String ACTION_SLEEP_TIMER = "com.hamo.styrixplayer.SLEEP_TIMER";
    public static final String EXTRA_MINUTES = "minutes";

    private static final SessionCommand SLEEP_COMMAND =
            new SessionCommand(ACTION_SLEEP_TIMER, Bundle.EMPTY);

    private MediaSession mediaSession;
    private ExoPlayer player;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable sleepRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null) player.pause();
        }
    };

    private final Runnable saveTicker = new Runnable() {
        @Override
        public void run() {
            saveResume();
            handler.postDelayed(this, 10_000);
        }
    };

    private int ytRetries;

    private final Player.Listener playerListener = new Player.Listener() {
        /**
         * A stream that dies mid-song (expired or refused link, dropped connection) is retried
         * from the same spot with a fresh link; after three tries the song is skipped.
         */
        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException error) {
            MediaItem cur = player.getCurrentMediaItem();
            if (cur == null || !YtTrack.isYtId(cur.mediaId)) return;
            YtStreams.forget(cur.mediaId.substring(YtTrack.ID_PREFIX.length()));
            if (ytRetries++ < 3) {
                long pos = Math.max(0, player.getCurrentPosition());
                player.seekTo(player.getCurrentMediaItemIndex(), pos);
                player.prepare();
                player.play();
            } else if (player.hasNextMediaItem()) {
                ytRetries = 0;
                player.seekToNextMediaItem();
                player.prepare();
                player.play();
            }
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            ytRetries = 0;
            scheduleLayout();
            applyTrackSpeed(item);
            saveResume();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            handler.removeCallbacks(saveTicker);
            if (isPlaying) handler.postDelayed(saveTicker, 10_000);
            else saveResume();
        }

        @Override
        public void onTimelineChanged(Timeline timeline, int reason) {
            saveResume();
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean enabled) {
            saveResume();
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            saveResume();
        }
    };

    private final ResolvingDataSource.Resolver ytResolver = new ResolvingDataSource.Resolver() {
        @Override
        public DataSpec resolveDataSpec(DataSpec dataSpec) throws IOException {
            if (!YtTrack.SCHEME.equals(dataSpec.uri.getScheme())) return dataSpec;
            String id = dataSpec.uri.getSchemeSpecificPart();
            // a copy on the phone beats streaming
            android.net.Uri offline = com.hamo.styrixplayer.yt.YtLocal.localUri(id);
            if (offline != null) return dataSpec.withUri(offline);
            try {
                return dataSpec.withUri(android.net.Uri.parse(YtStreams.urlFor(id)));
            } catch (IOException e) {
                YtStreams.forget(id);
                throw e;
            }
        }
    };

    private final MediaSession.Callback callback = new MediaSession.Callback() {
        @Override
        public MediaSession.ConnectionResult onConnect(MediaSession session,
                                                       MediaSession.ControllerInfo controller) {
            SessionCommands commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(SLEEP_COMMAND)
                    .add(FAV_COMMAND)
                    .add(DL_COMMAND)
                    .build();
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .setCustomLayout(layout)
                    .build();
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(MediaSession session,
                                                               MediaSession.ControllerInfo controller,
                                                               SessionCommand customCommand,
                                                               Bundle args) {
            if (ACTION_SLEEP_TIMER.equals(customCommand.customAction)) {
                long minutes = args.getLong(EXTRA_MINUTES, 0);
                handler.removeCallbacks(sleepRunnable);
                if (minutes > 0) handler.postDelayed(sleepRunnable, minutes * 60_000L);
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            }
            if (ACTION_FAV.equals(customCommand.customAction)) {
                toggleHeart();
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            }
            if (ACTION_DL.equals(customCommand.customAction)) {
                downloadCurrent();
                return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS));
            }
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
        }
    };

    @Nullable
    private String currentYtId() {
        MediaItem it = player == null ? null : player.getCurrentMediaItem();
        return it != null && YtTrack.isYtId(it.mediaId)
                ? it.mediaId.substring(YtTrack.ID_PREFIX.length()) : null;
    }

    private long currentLocalId() {
        MediaItem it = player == null ? null : player.getCurrentMediaItem();
        if (it == null || YtTrack.isYtId(it.mediaId)) return -1;
        try {
            return Long.parseLong(it.mediaId);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The heart: likes the song on YouTube Music, or adds a phone song to Favorites. */
    private void toggleHeart() {
        final String yt = currentYtId();
        if (yt != null) {
            if (!com.hamo.styrixplayer.yt.YtAuth.isSignedIn(this)) {
                android.widget.Toast.makeText(this, I18n.t("Sign in to YouTube (Settings) to like songs"),
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            final boolean liked = !com.hamo.styrixplayer.yt.YtLikes.isLiked(this, yt);
            com.hamo.styrixplayer.yt.YtLikes.set(this, yt, liked);
            updateLayout();
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    com.hamo.styrixplayer.yt.YtMusicApi.setLiked(app, yt, liked);
                } catch (Exception e) {
                    com.hamo.styrixplayer.yt.YtLikes.set(app, yt, !liked);
                    handler.post(this::updateLayout);
                }
            }, "notif-like").start();
            return;
        }
        long id = currentLocalId();
        if (id < 0) return;
        Db.get(this).toggleFavorite(id);
        updateLayout();
    }

    /** The download button: saves the streamed song to the phone. */
    private void downloadCurrent() {
        String yt = currentYtId();
        MediaItem cur = player == null ? null : player.getCurrentMediaItem();
        if (yt == null || cur == null) return;
        androidx.media3.common.MediaMetadata m = cur.mediaMetadata;
        com.hamo.styrixplayer.yt.YtDownloads.start(this, yt,
                m.title == null ? "" : m.title.toString(),
                m.artist == null ? "" : m.artist.toString(),
                m.artworkUri == null ? "" : m.artworkUri.toString());
        android.widget.Toast.makeText(this, I18n.t("Download started"),
                android.widget.Toast.LENGTH_SHORT).show();
    }

    // download progress arrives on a worker thread; the session must be touched on the main one
    private final Runnable layoutTask = this::updateLayout;

    /** One refresh a moment later, however many requests come in (song changes, download progress). */
    private void scheduleLayout() {
        handler.removeCallbacks(layoutTask);
        handler.postDelayed(layoutTask, 500);
    }

    private final Runnable layoutRefresh = this::scheduleLayout;

    /** Builds the heart + download buttons for the playing song and hands them to the notification. */
    private void updateLayout() {
        if (mediaSession == null || player == null) return;
        String yt = currentYtId();
        long local = currentLocalId();
        boolean liked = false, canDownload = false, hasHeart = false;
        if (yt != null) {
            hasHeart = true;
            liked = com.hamo.styrixplayer.yt.YtLikes.isLiked(this, yt);
            canDownload = com.hamo.styrixplayer.yt.YtLocal.songIdFor(this, yt) <= 0;
        } else if (local >= 0) {
            hasHeart = true;
            liked = Db.get(this).isFavorite(local);
        }
        String key = hasHeart + "/" + liked + "/" + canDownload;
        if (key.equals(layoutKey)) return;
        layoutKey = key;
        com.google.common.collect.ImmutableList.Builder<androidx.media3.session.CommandButton> b =
                com.google.common.collect.ImmutableList.builder();
        if (hasHeart) {
            b.add(new androidx.media3.session.CommandButton.Builder()
                    .setDisplayName(I18n.t(liked ? "Remove from favorites" : "Add to favorites"))
                    .setIconResId(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border)
                    .setSessionCommand(FAV_COMMAND)
                    .build());
        }
        if (canDownload) {
            b.add(new androidx.media3.session.CommandButton.Builder()
                    .setDisplayName(I18n.t("Download"))
                    .setIconResId(R.drawable.ic_download)
                    .setSessionCommand(DL_COMMAND)
                    .build());
        }
        layout = b.build();
        mediaSession.setCustomLayout(layout);
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // the little badge on the cover in the notification: our own icon, not media3's default
        androidx.media3.session.DefaultMediaNotificationProvider provider =
                new androidx.media3.session.DefaultMediaNotificationProvider(this);
        provider.setSmallIcon(R.drawable.ic_stat_note);
        setMediaNotificationProvider(provider);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        // Songs from YouTube Music have a "ytm:<videoId>" address. It is turned into a real stream
        // URL right before loading, so queued songs never hold an expired link.
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(NpDownloader.UA)
                .setAllowCrossProtocolRedirects(true);
        final DataSource.Factory plain = new ResolvingDataSource.Factory(
                new DefaultDataSource.Factory(this, http), ytResolver);
        // Streamed songs also go through a disk cache keyed by "ytm:<videoId>" (see MusicCache).
        // Local files and songs already downloaded to the phone skip it, so nothing is stored twice.
        final androidx.media3.datasource.cache.SimpleCache musicCache = MusicCache.get(this);
        final DataSource.Factory cachedFactory = musicCache == null ? plain
                : new androidx.media3.datasource.cache.CacheDataSource.Factory()
                        .setCache(musicCache)
                        .setUpstreamDataSourceFactory(plain)
                        .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        DataSource.Factory upstream = () -> new RoutingDataSource(
                plain.createDataSource(), cachedFactory.createDataSource(), spec -> {
                    if (musicCache == null || !MusicCache.enabled(PlaybackService.this)) return false;
                    if (!YtTrack.SCHEME.equals(spec.uri.getScheme())) return false;
                    return com.hamo.styrixplayer.yt.YtLocal.localUri(spec.uri.getSchemeSpecificPart()) == null;
                });

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(upstream))
                .setAudioAttributes(attrs, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true) // pause on headphone unplug
                // keeps CPU and Wi-Fi awake while streaming with the screen off
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                .build();
        player.addListener(playerListener);

        // Tapping the notification opens the full-screen player
        PendingIntent openPlayer = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        mediaSession = new MediaSession.Builder(this, player)
                .setCallback(callback)
                .setSessionActivity(openPlayer)
                .build();
        com.hamo.styrixplayer.yt.YtDownloads.addListener(layoutRefresh);
        scheduleLayout();
    }

    /**
     * Stay a foreground service while paused. Without this, media3 drops to a plain background
     * service the moment playback pauses, and aggressive battery managers (HyperOS and friends)
     * kill it a minute later, taking the notification with it.
     */
    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    @Override
    public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
        super.onUpdateNotification(session, true);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    private void saveResume() {
        if (player == null) return;
        if (!Prefs.getBool(this, Prefs.RESUME, true)) return;
        ResumeStore.save(this, player);
    }

    private void applyTrackSpeed(@Nullable MediaItem item) {
        if (item == null || player == null) return;
        if (!Prefs.getBool(this, Prefs.SPEED_PER_TRACK, false)) return;
        // A song with no saved speed (or a streamed one) keeps whatever is playing now, so the
        // pitch doesn't jump back to normal at every track change.
        PlaybackParameters now = player.getPlaybackParameters();
        float speed = now.speed;
        try {
            speed = Db.get(this).getSpeed(Long.parseLong(item.mediaId), now.speed);
        } catch (NumberFormatException ignored) {
        }
        boolean keepPitch = Prefs.getBool(this, Prefs.KEEP_PITCH, true);
        float pitch = keepPitch ? 1f : speed;
        if (speed == now.speed && pitch == now.pitch) return;
        player.setPlaybackParameters(new PlaybackParameters(speed, pitch));
    }

    @Override
    public void onDestroy() {
        instance = null;
        com.hamo.styrixplayer.yt.YtDownloads.removeListener(layoutRefresh);
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            if (player.getMediaItemCount() > 0) saveResume();
            player.removeListener(playerListener);
        }
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        player = null;
        super.onDestroy();
    }
}
