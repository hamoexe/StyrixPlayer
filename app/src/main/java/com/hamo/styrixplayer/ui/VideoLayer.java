package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.session.MediaController;

import com.hamo.styrixplayer.yt.NpDownloader;
import com.hamo.styrixplayer.yt.YtVideo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The picture for a streamed song. A second, muted player shows the video stream and is kept in step
 * with the real (audio) player: same position, same speed, same play/pause. It also reports the
 * average color of what is on screen so the player can tint itself like the video.
 */
final class VideoLayer {

    interface Callback {
        /** Size of the decoded video, to fit it inside its box. */
        void onSize(int w, int h);

        /** Average color of the current picture (about once a second while it plays). */
        void onColor(int argb);

        /** The video couldn't be loaded. */
        void onFailed();

        /** Average color of each of 3 x 3 cells of the picture (row by row, top-left first). */
        void onGrid(int[] cells);

        /** The first picture is on screen. */
        void onFirstFrame();
    }

    private final Context app;
    private final TextureView tv;
    private final Callback cb;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Nullable private ExoPlayer vp;
    @Nullable private String wanted;      // video id we are showing / loading
    @Nullable private MediaController main;
    private long lastSeek;
    private boolean released;
    private int failures;

    VideoLayer(Context ctx, TextureView tv, Callback cb) {
        this.app = ctx.getApplicationContext();
        this.tv = tv;
        this.cb = cb;
    }

    /** Start (or keep) showing the video of this id. */
    void show(final String videoId, @Nullable MediaController audio) {
        if (released) return;
        main = audio;
        if (videoId.equals(wanted) && vp != null) return;
        wanted = videoId;
        failures = 0;
        load(videoId);
        h.removeCallbacks(sampler);
        h.postDelayed(sampler, 300);
    }

    private void load(final String videoId) {
        closePlayer();
        io.execute(() -> {
            try {
                final String url = YtVideo.urlFor(videoId);
                h.post(() -> {
                    if (released || !videoId.equals(wanted)) return;
                    open(url);
                });
            } catch (Exception e) {
                h.post(() -> {
                    if (released || !videoId.equals(wanted)) return;
                    cb.onFailed();
                });
            }
        });
    }

    private void open(String url) {
        closePlayer();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(NpDownloader.UA)
                .setAllowCrossProtocolRedirects(true);
        final ExoPlayer p = new ExoPlayer.Builder(app)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(http))
                .build();
        p.setVolume(0f);
        // picture only: no second audio output next to the real player
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true).build());
        p.setVideoTextureView(tv);
        p.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    cb.onSize((int) (size.width * Math.max(0.1f, size.pixelWidthHeightRatio)), size.height);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                cb.onFirstFrame();
                h.removeCallbacks(sampler);
                h.post(sampler);      // grab the color the moment the first picture is on screen
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                final String id = wanted;
                if (id == null) return;
                YtVideo.forget(id);
                if (++failures <= 2) load(id);
                else cb.onFailed();
            }
        });
        // start where the audio is right now, not at 0
        long pos = main != null ? Math.max(0, main.getCurrentPosition()) : 0;
        p.setMediaItem(MediaItem.fromUri(url), pos);
        p.prepare();
        lastSeek = System.currentTimeMillis();
        vp = p;
        sync(main, true);
    }

    /** Call every ~400 ms with the audio player: keeps position, speed and play state in step. */
    void sync(@Nullable MediaController c, boolean force) {
        ExoPlayer p = vp;
        if (p == null || c == null) return;
        boolean playing = c.isPlaying();
        boolean wantPlay = playing || (c.getPlayWhenReady() && c.getPlaybackState() == Player.STATE_BUFFERING);
        float speed = c.getPlaybackParameters().speed;
        if (Math.abs(p.getPlaybackParameters().speed - speed) > 0.001f) {
            p.setPlaybackParameters(new PlaybackParameters(speed));
        }
        if (p.getPlayWhenReady() != wantPlay) p.setPlayWhenReady(wantPlay);
        // a seek throws the buffer away, so never seek a video that is still buffering
        // (that made it show one picture every couple of seconds)
        if (p.getPlaybackState() != Player.STATE_READY) return;
        long target = Math.max(0, c.getCurrentPosition());
        long diff = p.getCurrentPosition() - target;
        long now = System.currentTimeMillis();
        long limit = force ? 120 : (playing ? 450 : 150);
        if (Math.abs(diff) > limit && (force || now - lastSeek > 2000)) {
            lastSeek = now;
            p.seekTo(target);
        }
    }

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            ExoPlayer p = vp;
            if (p != null && tv.isAvailable() && p.getPlaybackState() == Player.STATE_READY) {
                try {
                    Bitmap b = tv.getBitmap(24, 14);
                    if (b != null) {
                        int c = average(b);
                        cb.onGrid(grid(b));
                        b.recycle();
                        if (c != 0) cb.onColor(c);
                    }
                } catch (Exception ignored) {
                }
            }
            h.postDelayed(this, com.hamo.styrixplayer.data.Prefs.videoSampleMs(tv.getContext()));
        }
    };

    /** Mean color of the lit pixels (black bars and dark frames are left out so they don't wash it out). */
    private static int average(Bitmap b) {
        int w = b.getWidth(), hh = b.getHeight();
        long r = 0, g = 0, bl = 0, n = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < w; x++) {
                int px = b.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                if (hsv[2] < 0.1f) continue;
                // colorful pixels count more, so a grey wall doesn't beat the neon sign
                int wgt = 1 + (int) (hsv[1] * 4f);
                r += Color.red(px) * wgt;
                g += Color.green(px) * wgt;
                bl += Color.blue(px) * wgt;
                n += wgt;
            }
        }
        if (n == 0) return 0;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /** Mean color of each of 3 x 3 cells, so the corners can have their own colors. */
    static int[] grid(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] out = new int[9];
        for (int cy = 0; cy < 3; cy++) {
            for (int cx = 0; cx < 3; cx++) {
                int x0 = cx * w / 3, x1 = Math.max(x0 + 1, (cx + 1) * w / 3);
                int y0 = cy * h / 3, y1 = Math.max(y0 + 1, (cy + 1) * h / 3);
                long r = 0, g = 0, bl = 0, n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int px = b.getPixel(x, y);
                        r += Color.red(px);
                        g += Color.green(px);
                        bl += Color.blue(px);
                        n++;
                    }
                }
                out[cy * 3 + cx] = n == 0 ? Color.BLACK : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
            }
        }
        return out;
    }

    private void closePlayer() {
        ExoPlayer p = vp;
        vp = null;
        if (p != null) {
            try {
                p.clearVideoTextureView(tv);
            } catch (Exception ignored) {
            }
            p.release();
        }
    }

    /** Stop showing the video and free the decoder. */
    void hide() {
        wanted = null;
        h.removeCallbacks(sampler);
        closePlayer();
    }

    void release() {
        released = true;
        hide();
        io.shutdownNow();
    }
}
