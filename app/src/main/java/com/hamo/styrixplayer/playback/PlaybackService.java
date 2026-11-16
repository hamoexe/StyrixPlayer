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
                    .build();
            return MediaSession.ConnectionResult.accept(
                    commands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS);
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
            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
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
