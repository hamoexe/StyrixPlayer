package com.hamo.styrixplayer.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.hamo.styrixplayer.R;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.hamo.styrixplayer.yt.AudioDownloader;
import com.hamo.styrixplayer.yt.VideoDownloader;
import com.hamo.styrixplayer.yt.YtDownloads;
import com.hamo.styrixplayer.yt.YouTubeResolver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Paste (or share) a YouTube link and the audio lands in Music/StyrixPlayer and your library. */
public class DownloadActivity extends BaseActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    private TextInputEditText input;
    private MaterialButton go;
    private TextView status, title;
    private LinearProgressIndicator bar;
    private boolean busy;
    private boolean videoMode;
    private volatile String currentKey;       // the download this page is watching

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);
        padSystemBars(findViewById(R.id.dl_root), true, true);
        findViewById(R.id.dl_back).setOnClickListener(v -> finish());

        input = findViewById(R.id.dl_input);
        go = findViewById(R.id.dl_go);
        status = findViewById(R.id.dl_status);
        title = findViewById(R.id.dl_title);
        bar = findViewById(R.id.dl_bar);

        MaterialButtonToggleGroup mode = findViewById(R.id.dl_mode);
        mode.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            videoMode = id == R.id.dl_mode_video;
            if (!busy) go.setText(videoMode ? "Download video" : "Download audio");
        });

        findViewById(R.id.dl_paste).setOnClickListener(v -> paste());
        go.setOnClickListener(v -> {
            if (busy) {
                cancel.set(true);                       // while looking the video up
                String k = currentKey;
                if (k != null) YtDownloads.cancel(k);   // while downloading
            } else start();
        });
        YtDownloads.addWatcher(watcher);
        handleIntent(getIntent());
    }

    /** Follows the shared download manager, so a download goes on (with its notification) if you leave. */
    private final YtDownloads.Listener watcher = t -> {
        if (isDestroyed() || !t.key.equals(currentKey)) return;
        if (t.finished()) {
            currentKey = null;
            finish(t.state == YtDownloads.DONE, t.message == null ? "" : t.message);
        } else if (t.state == YtDownloads.QUEUED) {
            status.setText("Waiting\u2026");
        } else if (t.stage != null) {
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(true);
            status.setText(t.stage);
        } else if (t.percent >= 0) {
            bar.setVisibility(View.VISIBLE);
            bar.setIndeterminate(false);
            bar.setProgress(t.percent);
            status.setText("Downloading\u2026 " + t.percent + "%");
        }
    };

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && Intent.ACTION_SEND.equals(i.getAction())) {
            String t = i.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null && YouTubeResolver.parseVideoId(t) != null && !busy) {
                input.setText(t.trim());
                start();
            }
        }
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData d = cm == null ? null : cm.getPrimaryClip();
        if (d != null && d.getItemCount() > 0 && d.getItemAt(0).getText() != null) {
            input.setText(d.getItemAt(0).getText().toString().trim());
        }
    }

    private void start() {
        final String text = input.getText() == null ? "" : input.getText().toString();
        if (YouTubeResolver.parseVideoId(text) == null) {
            status.setText("Paste a YouTube link first");
            return;
        }
        busy = true;
        cancel.set(false);
        go.setText("Cancel");
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        title.setText("");
        status.setText("Looking up the video\u2026");
        if (videoMode) startVideo(text); else startAudio(text);
    }

    private void startAudio(final String text) {
        final Context app = getApplicationContext();
        io.execute(() -> {
            try {
                final YouTubeResolver.Info info = YouTubeResolver.resolve(text);
                if (cancel.get()) throw new AudioDownloader.Cancelled();
                main.post(() -> {
                    title.setText(info.title + (info.author.isEmpty() ? "" : "\n" + info.author));
                    status.setText("Downloading\u2026");
                    final String key = YouTubeResolver.parseVideoId(text);
                    currentKey = key;
                    if (YtDownloads.submit(app, key, info.title, r -> {
                        AudioDownloader.download(app, info, r::progress, r.cancelFlag());
                        return "Saved to Music/StyrixPlayer. It's in your library now.";
                    }) == null) {
                        currentKey = null;
                        finish(false, "Already downloading");
                    }
                });
            } catch (AudioDownloader.Cancelled e) {
                main.post(() -> finish(false, "Cancelled"));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> finish(false, "Failed: " + msg));
            }
        });
    }

    private void startVideo(final String text) {
        io.execute(() -> {
            try {
                final VideoDownloader.Plan plan = VideoDownloader.plan(text);
                if (cancel.get()) throw new AudioDownloader.Cancelled();
                main.post(() -> {
                    title.setText(plan.title + (plan.author.isEmpty() ? "" : "\n" + plan.author));
                    status.setText("Choose quality");
                    bar.setVisibility(View.GONE);
                    askQuality(plan);
                });
            } catch (AudioDownloader.Cancelled e) {
                main.post(() -> finish(false, "Cancelled"));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> finish(false, "Failed: " + msg));
            }
        });
    }

    private void askQuality(final VideoDownloader.Plan plan) {
        if (isFinishing() || isDestroyed()) return;
        final String[] labels = new String[plan.options.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = plan.options.get(i).label();
        new ThemedDialogBuilder(this)
                .setTitle("Choose quality")
                .setItems(labels, (d, which) -> runVideo(plan, plan.options.get(which)))
                .setOnCancelListener(d -> finish(false, "Cancelled"))
                .show();
    }

    private void runVideo(final VideoDownloader.Plan plan, final VideoDownloader.Option opt) {
        final Context app = getApplicationContext();
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        status.setText("Downloading\u2026");
        final String key = "v:" + plan.videoId + ":" + opt.height;
        currentKey = key;
        if (YtDownloads.submit(app, key, plan.title + " (" + opt.height + "p)", r -> {
            VideoDownloader.download(app, plan, opt, new VideoDownloader.Progress() {
                @Override public void onProgress(long done, long total) {
                    r.progress(done, total);
                }

                @Override public void onMerging() {
                    r.stage("Merging video and audio\u2026");
                }
            }, r.cancelFlag());
            return "Saved to Movies/StyrixPlayer";
        }) == null) {
            currentKey = null;
            finish(false, "Already downloading");
        }
    }

    private void finish(boolean success, String message) {
        busy = false;
        go.setText(videoMode ? "Download video" : "Download audio");
        bar.setVisibility(View.GONE);
        status.setText(message);
        if (success) input.setText("");
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);                      // only stops a lookup; a running download carries on
        YtDownloads.removeWatcher(watcher);
        io.shutdownNow();
        super.onDestroy();
    }
}
