package com.hamo.styrixplayer.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Db;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Edit, translate, import or export the lyrics of one song. Saved lyrics are kept offline for good. */
public class LyricsEditorActivity extends BaseActivity {

    public static final String EXTRA_SONG_ID = "song_id";
    public static final String EXTRA_NAME = "name";

    private static final Pattern LINE_STAMP = Pattern.compile("(?m)^\\s*\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]");
    private static final Pattern WORD_STAMP = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private long songId;
    private String name = "lyrics";
    private EditText text;
    private String original = "";

    private ActivityResultLauncher<String> importPicker;
    private ActivityResultLauncher<String> exportPicker;
    private ActivityResultLauncher<Intent> syncLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics_editor);
        padSystemBars(findViewById(R.id.le_root), true, true);

        songId = getIntent().getLongExtra(EXTRA_SONG_ID, -1);
        String n = getIntent().getStringExtra(EXTRA_NAME);
        if (n != null && !n.trim().isEmpty()) name = n.trim();
        if (songId < 0) {
            finish();
            return;
        }
        text = findViewById(R.id.le_text);

        importPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) importFrom(uri);
        });
        exportPicker = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) exportTo(uri);
        });

        syncLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != RESULT_OK || r.getData() == null) return;
            String t = r.getData().getStringExtra(LyricsSyncActivity.EXTRA_TEXT);
            if (t == null) return;
            text.setText(t);
            Toast.makeText(this, "Timing added. Press Save to keep it.", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.le_sync).setOnClickListener(v -> {
            String t = text.getText().toString();
            if (t.trim().isEmpty()) {
                Toast.makeText(this, "Paste or type the lyrics first, one line per row", Toast.LENGTH_LONG).show();
                return;
            }
            syncLauncher.launch(new Intent(this, LyricsSyncActivity.class)
                    .putExtra(LyricsSyncActivity.EXTRA_SONG_ID, songId)
                    .putExtra(LyricsSyncActivity.EXTRA_NAME, name)
                    .putExtra(LyricsSyncActivity.EXTRA_TEXT, t));
        });
        findViewById(R.id.le_back).setOnClickListener(v -> confirmClose());
        findViewById(R.id.le_cancel).setOnClickListener(v -> confirmClose());
        findViewById(R.id.le_save).setOnClickListener(v -> save());
        findViewById(R.id.le_import).setOnClickListener(v -> importPicker.launch("*/*"));
        findViewById(R.id.le_export).setOnClickListener(v -> exportPicker.launch(safeFileName() + ".lrc"));
        findViewById(R.id.le_strip).setOnClickListener(v -> {
            text.setText(WORD_STAMP.matcher(text.getText().toString()).replaceAll(""));
            Toast.makeText(this, "Word timing cleared", Toast.LENGTH_SHORT).show();
        });

        final Db db = Db.get(this);
        io.execute(() -> {
            Db.LyricsRow row = db.getLyrics(songId);
            String t = "";
            if (row != null) {
                if (row.synced != null && !row.synced.trim().isEmpty()) t = row.synced;
                else if (row.plain != null) t = row.plain;
            }
            final String loaded = t;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                original = loaded;
                text.setText(loaded);
            });
        });

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmClose();
            }
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private String safeFileName() {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "lyrics" : s;
    }

    private void confirmClose() {
        if (text.getText().toString().equals(original)) {
            finish();
            return;
        }
        new ThemedDialogBuilder(this)
                .setTitle("Discard changes?")
                .setPositiveButton("Discard", (d, w) -> finish())
                .setNegativeButton("Keep editing", null)
                .show();
    }

    private void save() {
        final String t = text.getText().toString().replace("\r\n", "\n").trim();
        final Db db = Db.get(this);
        if (t.isEmpty()) {
            new ThemedDialogBuilder(this)
                    .setTitle("Remove saved lyrics?")
                    .setMessage("The text is empty. The song will look for lyrics online again.")
                    .setPositiveButton("Remove", (d, w) -> io.execute(() -> {
                        db.deleteLyrics(songId);
                        runOnUiThread(this::finishOk);
                    }))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        final boolean timed = LINE_STAMP.matcher(t).find();
        io.execute(() -> {
            if (timed) db.putCustomLyrics(songId, t, null);
            else db.putCustomLyrics(songId, null, t);
            runOnUiThread(this::finishOk);
        });
    }

    private void finishOk() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_SONG_ID, songId));
        Toast.makeText(this, "Lyrics saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void importFrom(final Uri uri) {
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("can't open file");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 2_000_000) throw new java.io.IOException("file is too big");
                    out.write(buf, 0, n);
                }
                byte[] b = out.toByteArray();
                int off = (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB
                        && (b[2] & 0xFF) == 0xBF) ? 3 : 0;
                final String s = new String(b, off, b.length - off, StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    text.setText(s);
                    Toast.makeText(this, "Imported. Press Save to keep it.", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't import: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void exportTo(final Uri uri) {
        final byte[] data = text.getText().toString().getBytes(StandardCharsets.UTF_8);
        io.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException("can't write file");
                out.write(data);
                runOnUiThread(() -> Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Couldn't export: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        });
    }
}
