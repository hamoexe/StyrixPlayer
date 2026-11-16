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
import com.hamo.styrixplayer.yt.AudioDownloader;
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

        findViewById(R.id.dl_paste).setOnClickListener(v -> paste());
        go.setOnClickListener(v -> {
            if (busy) cancel.set(true);
            else start();
        });
        handleIntent(getIntent());
    }

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

        io.execute(() -> {
            try {
                final YouTubeResolver.Info info = YouTubeResolver.resolve(text);
                main.post(() -> {
                    title.setText(info.title + (info.author.isEmpty() ? "" : "\n" + info.author));
                    status.setText("Downloading\u2026");
                });
                AudioDownloader.download(this, info, (done, total) -> main.post(() -> {
                    if (total > 0) {
                        bar.setIndeterminate(false);
                        bar.setProgress((int) (done * 100 / total));
                        status.setText("Downloading\u2026 " + (done * 100 / total) + "%");
                    }
                }), cancel);
                main.post(() -> finish(true, "Saved to Music/StyrixPlayer. It's in your library now."));
            } catch (AudioDownloader.Cancelled e) {
                main.post(() -> finish(false, "Cancelled"));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                main.post(() -> finish(false, "Failed: " + msg));
            }
        });
    }

    private void finish(boolean success, String message) {
        busy = false;
        go.setText("Download audio");
        bar.setVisibility(View.GONE);
        status.setText(message);
        if (success) input.setText("");
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        io.shutdownNow();
        super.onDestroy();
    }
}
