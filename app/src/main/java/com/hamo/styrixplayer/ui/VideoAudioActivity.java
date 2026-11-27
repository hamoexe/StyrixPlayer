package com.hamo.styrixplayer.ui;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.textfield.TextInputEditText;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.yt.VideoToAudio;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pick (or share) a video and get its sound as a song in your library. Before converting you name
 * it, so it never ends up as a nameless track: the title starts from the video's name, and the
 * cover is a frame of the video (or any image you pick).
 */
public class VideoAudioActivity extends BaseActivity {

    private static final String STATE_URI = "video_uri";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextInputEditText titleIn, artistIn;
    private TextView source, status, rangeText;
    private ImageView coverView;
    private MaterialSwitch trim;
    private RangeSlider range;
    private MaterialButton go, frameBtn, pickBtn;
    private LinearProgressIndicator bar;

    @Nullable private Uri video;
    private long durationMs;
    @Nullable private byte[] cover;
    private int frameIndex;
    private boolean busy;
    @Nullable private VideoToAudio.Handle handle;

    private final ActivityResultLauncher<String[]> pickVideo =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) { finish(); return; }
                load(uri, true);
            });

    private final ActivityResultLauncher<String[]> pickImage =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) coverFromImage(uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_audio);
        padSystemBars(findViewById(R.id.va_root), true, true);
        findViewById(R.id.va_back).setOnClickListener(v -> finish());

        titleIn = findViewById(R.id.va_title);
        artistIn = findViewById(R.id.va_artist);
        source = findViewById(R.id.va_source);
        status = findViewById(R.id.va_status);
        rangeText = findViewById(R.id.va_range_text);
        coverView = findViewById(R.id.va_cover);
        trim = findViewById(R.id.va_trim);
        range = findViewById(R.id.va_range);
        go = findViewById(R.id.va_go);
        frameBtn = findViewById(R.id.va_frame);
        pickBtn = findViewById(R.id.va_pick);
        bar = findViewById(R.id.va_bar);

        final float r = 20 * getResources().getDisplayMetrics().density;
        coverView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        coverView.setClipToOutline(true);

        trim.setOnCheckedChangeListener((b, on) -> {
            range.setVisibility(on ? View.VISIBLE : View.GONE);
            rangeText.setVisibility(on ? View.VISIBLE : View.GONE);
            updateRangeText();
        });
        range.setLabelFormatter(v -> clock((long) v * 1000L));
        range.addOnChangeListener((s, v, user) -> updateRangeText());
        frameBtn.setOnClickListener(v -> nextFrame());
        pickBtn.setOnClickListener(v -> pickImage.launch(new String[]{"image/*"}));
        go.setOnClickListener(v -> convert());
        setBusy(false);

        Uri start = null;
        if (savedInstanceState != null) {
            String s = savedInstanceState.getString(STATE_URI);
            if (s != null) { load(Uri.parse(s), false); return; }
        }
        Intent in = getIntent();
        if (in != null) {
            if (Intent.ACTION_SEND.equals(in.getAction())) {
                start = in.getParcelableExtra(Intent.EXTRA_STREAM);
            } else if (Intent.ACTION_VIEW.equals(in.getAction())) {
                start = in.getData();
            }
        }
        if (start != null) load(start, true);
        else pickVideo.launch(new String[]{"video/*"});
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (video != null) out.putString(STATE_URI, video.toString());
    }

    @Override
    protected void onDestroy() {
        if (handle != null && busy) handle.cancel();
        io.shutdown();
        super.onDestroy();
    }

    // ---------- reading the video ----------

    private void load(final Uri uri, final boolean fillText) {
        video = uri;
        status.setText("Reading the video…");
        io.execute(() -> {
            String name = displayName(uri);
            long dur = 0;
            String mTitle = null, mArtist = null;
            Bitmap frame = null;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, uri);
                dur = parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                mTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                mArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (mArtist == null || mArtist.trim().isEmpty()) {
                    mArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
                }
                frame = mmr.getFrameAtTime(Math.min(1_000_000L, dur * 100L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (RuntimeException ignored) {
                // not readable: the user still gets the picker error below when converting
            } finally {
                try { mmr.release(); } catch (Exception ignored) { }
            }
            final byte[] jpeg = frame == null ? null : squareJpeg(frame);
            final long fDur = dur;
            final String fTitle = niceTitle(mTitle, name);
            final String fArtist = mArtist == null ? "" : mArtist.trim();
            final String fName = name;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                durationMs = fDur;
                source.setText(fName);
                if (fillText) {
                    titleIn.setText(fTitle);
                    artistIn.setText(fArtist);
                }
                if (jpeg != null && cover == null) setCover(jpeg);
                long secs = fDur / 1000L;
                boolean canTrim = secs >= 4;
                trim.setVisibility(canTrim ? View.VISIBLE : View.GONE);
                if (canTrim) {
                    range.setValueFrom(0f);
                    range.setValueTo((float) secs);
                    range.setStepSize(1f);
                    range.setValues(0f, (float) secs);
                }
                status.setText("");
            });
        });
    }

    @Nullable
    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall through
        }
        String p = uri.getLastPathSegment();
        return p == null ? "video" : p;
    }

    /** A usable song name: the video's own title tag, else its file name made readable. */
    private static String niceTitle(@Nullable String tag, @Nullable String fileName) {
        if (tag != null && !tag.trim().isEmpty()) return tag.trim();
        String n = fileName == null ? "" : fileName;
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replace('_', ' ').replaceAll("\\s+", " ").trim();
        // phone / app default names mean nothing: VID_20240101_1234, PXL_..., Screenrecording_...
        if (n.isEmpty() || n.matches("(?i)^(vid|img|pxl|mov|video|screen[ ]?record(ing)?|screenrecorder|snaptik|ssstik|tiktok|download)[ -]?\\d.*")
                || n.matches("^[\\d -]+$")) {
            return "Audio " + new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(new Date());
        }
        return n;
    }

    private static long parseLong(@Nullable String s) {
        try { return s == null ? 0 : Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    // ---------- cover ----------

    private void setCover(byte[] jpeg) {
        cover = jpeg;
        coverView.setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length));
    }

    /** Cycles through frames at 10 %, 30 %, 50 %, 70 % and 90 % of the video. */
    private void nextFrame() {
        final Uri uri = video;
        if (uri == null || busy) return;
        final long at = (long) (durationMs * 1000L * (0.1 + 0.2 * (frameIndex++ % 5)));
        io.execute(() -> {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            Bitmap f = null;
            try {
                mmr.setDataSource(this, uri);
                f = mmr.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (RuntimeException ignored) {
                // keep the current cover
            } finally {
                try { mmr.release(); } catch (Exception ignored) { }
            }
            if (f == null) return;
            final byte[] j = squareJpeg(f);
            main.post(() -> { if (!isFinishing() && j != null) setCover(j); });
        });
    }

    private void coverFromImage(final Uri uri) {
        io.execute(() -> {
            Bitmap b = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    b = BitmapFactory.decodeStream(in, null, o);
                }
            } catch (IOException | RuntimeException ignored) {
                // not an image we can read
            }
            final byte[] j = b == null ? null : squareJpeg(b);
            main.post(() -> {
                if (isFinishing()) return;
                if (j != null) setCover(j);
                else status.setText("That image couldn't be read");
            });
        });
    }

    /** Centre square, at most 800 px, as JPEG: covers stay small and are never stretched. */
    @Nullable
    private static byte[] squareJpeg(Bitmap b) {
        try {
            int side = Math.min(b.getWidth(), b.getHeight());
            Bitmap sq = Bitmap.createBitmap(b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            if (side > 800) sq = Bitmap.createScaledBitmap(sq, 800, 800, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            sq.compress(Bitmap.CompressFormat.JPEG, 92, out);
            return out.toByteArray();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------- trim ----------

    private static String clock(long ms) {
        long s = ms / 1000L;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void updateRangeText() {
        List<Float> v = range.getValues();
        if (v.size() < 2) return;
        long a = (long) (v.get(0) * 1000L), b = (long) (v.get(1) * 1000L);
        rangeText.setText(clock(a) + " – " + clock(b) + "   (" + clock(Math.max(0, b - a)) + ")");
    }

    // ---------- converting ----------

    private void setBusy(boolean on) {
        busy = on;
        go.setEnabled(!on);
        frameBtn.setEnabled(!on);
        pickBtn.setEnabled(!on);
        trim.setEnabled(!on);
        range.setEnabled(!on);
        titleIn.setEnabled(!on);
        artistIn.setEnabled(!on);
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void convert() {
        final Uri uri = video;
        if (uri == null || busy) return;
        final String title = titleIn.getText() == null ? "" : titleIn.getText().toString().trim();
        if (title.isEmpty()) {
            titleIn.setError("Give it a name");
            return;
        }
        final String artist = artistIn.getText() == null ? "" : artistIn.getText().toString().trim();
        long start = 0, end = 0;
        if (trim.getVisibility() == View.VISIBLE && trim.isChecked()) {
            List<Float> v = range.getValues();
            start = (long) (v.get(0) * 1000L);
            end = (long) (v.get(1) * 1000L);
            if (end >= durationMs - 500) end = 0;     // up to the end of the video
            if (start <= 0 && end == 0) start = 0;
        }
        final byte[] cov = cover;
        setBusy(true);
        bar.setIndeterminate(false);
        bar.setProgress(0);
        status.setText("Extracting the sound…");
        handle = VideoToAudio.export(this, uri, start, end, new VideoToAudio.Listener() {
            @Override
            public void onProgress(int percent) {
                bar.setProgress(percent);
            }

            @Override
            public void onExported(final File audio) {
                status.setText("Saving to your library…");
                bar.setIndeterminate(true);
                io.execute(() -> {
                    try {
                        VideoToAudio.save(VideoAudioActivity.this, audio, title, artist, cov);
                        main.post(() -> {
                            Toast.makeText(VideoAudioActivity.this, "Saved to Music/StyrixPlayer", Toast.LENGTH_LONG).show();
                            finish();
                        });
                    } catch (IOException | RuntimeException e) {
                        final String m = e.getMessage() == null ? "Couldn't save the song" : e.getMessage();
                        main.post(() -> fail(m));
                    }
                });
            }

            @Override
            public void onError(String message) {
                fail(message);
            }
        });
    }

    private void fail(String message) {
        if (isFinishing() || isDestroyed()) return;
        setBusy(false);
        status.setText(message);
    }
}
