package com.hamo.styrixplayer.ui;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.data.OnlineTags;
import com.hamo.styrixplayer.data.Song;
import com.hamo.styrixplayer.data.TagWriter;
import com.hamo.styrixplayer.data.TitleParser;
import com.hamo.styrixplayer.tags.TagData;
import com.hamo.styrixplayer.tags.Tags;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A real tag editor: title, artist, album, album artist, year, track, genre and cover are written
 * into the audio file itself (MP3, M4A, FLAC, Ogg Vorbis/Opus). For other formats the changes are
 * kept inside the app instead.
 */
public class MetadataEditorActivity extends BaseActivity {

    public static final String EXTRA_SONG_ID = "song_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Song song;
    private String iTitle = "", iArtist = "", iAlbum = "", iAlbumArtist = "", iYear = "",
            iTrack = "", iGenre = "";
    @Nullable private byte[] newCover;
    private boolean removeCover;
    @Nullable private Tags.Format format;

    @Nullable private TagWriter.Prepared prepared;
    @Nullable private TagData pendingEdit;

    private ImageView cover;
    private EditText eTitle, eArtist, eAlbum, eAlbumArtist, eYear, eTrack, eGenre;
    private TextView info;
    private View progress, btnSave, btnOnline, btnSmart, btnCover, btnRemove;

    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<IntentSenderRequest> writeLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_metadata_editor);
        padSystemBars(findViewById(R.id.ed_root), true, true);

        cover = findViewById(R.id.ed_cover);
        eTitle = findViewById(R.id.ed_title);
        eArtist = findViewById(R.id.ed_artist);
        eAlbum = findViewById(R.id.ed_album);
        eAlbumArtist = findViewById(R.id.ed_album_artist);
        eYear = findViewById(R.id.ed_year);
        eTrack = findViewById(R.id.ed_track);
        eGenre = findViewById(R.id.ed_genre);
        info = findViewById(R.id.ed_info);
        progress = findViewById(R.id.ed_progress);
        btnSave = findViewById(R.id.ed_save);
        btnOnline = findViewById(R.id.ed_btn_online);
        btnSmart = findViewById(R.id.ed_btn_smart);
        btnCover = findViewById(R.id.ed_btn_cover);
        btnRemove = findViewById(R.id.ed_btn_remove_cover);

        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            io.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) return;
                    setCover(squareJpeg(readAll(in), 1000));
                } catch (Exception e) {
                    toast("Couldn't read that picture");
                }
            });
        });
        writeLauncher = registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), r -> {
            if (r.getResultCode() == RESULT_OK) {
                commit();
            } else {
                dropPrepared();
                setBusy(false);
                toast("Cancelled. Nothing was changed.");
            }
        });

        findViewById(R.id.ed_back).setOnClickListener(v -> finish());
        findViewById(R.id.ed_cancel).setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> save());
        btnCover.setOnClickListener(v -> imagePicker.launch("image/*"));
        btnRemove.setOnClickListener(v -> {
            newCover = null;
            removeCover = true;
            cover.setImageResource(R.drawable.ic_art_placeholder);
        });
        btnSmart.setOnClickListener(v -> tidyTitle());
        btnOnline.setOnClickListener(v -> findOnline());

        final long id = getIntent().getLongExtra(EXTRA_SONG_ID, -1);
        if (id < 0) {
            finish();
            return;
        }
        setBusy(true);
        final Context app = getApplicationContext();
        io.execute(() -> {
            Library.ensureLoaded(app);
            final Song s = Library.get(id);
            if (s == null) {
                runOnUiThread(() -> {
                    toast("Couldn't find that song");
                    finish();
                });
                return;
            }
            String year = "", track = "", genre = "", albumArtist = "";
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(app, s.uri);
                year = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR));
                track = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER));
                genre = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE));
                albumArtist = nz(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST));
            } catch (Exception ignored) {
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                }
            }
            if (year.length() > 4 && Character.isDigit(year.charAt(0))) year = year.substring(0, 4);
            Tags.Format fmt = null;
            try {
                fmt = TagWriter.detect(app, s.uri);
            } catch (Exception ignored) {
            }
            final String fy = year, ft = track, fg = genre, fa = albumArtist;
            final Tags.Format ff = fmt;
            runOnUiThread(() -> bind(s, fy, ft, fg, fa, ff));
        });
    }

    private void bind(Song s, String year, String track, String genre, String albumArtist,
                      @Nullable Tags.Format fmt) {
        if (isDestroyed()) return;
        song = s;
        format = fmt;
        iTitle = nz(s.title);
        iArtist = nz(s.artist).equals("Unknown artist") ? "" : nz(s.artist);
        iAlbum = nz(s.album);
        iAlbumArtist = albumArtist;
        iYear = year;
        iTrack = track;
        iGenre = genre;
        eTitle.setText(iTitle);
        eArtist.setText(iArtist);
        eAlbum.setText(iAlbum);
        eAlbumArtist.setText(iAlbumArtist);
        eYear.setText(iYear);
        eTrack.setText(iTrack);
        eGenre.setText(iGenre);
        ArtLoader.load(this, s, cover, 512);

        if (fmt != null && fmt.writable()) {
            info.setText(fmt.label + ": changes are written into the file itself."
                    + (Build.VERSION.SDK_INT >= 30 ? " Android will ask you to allow editing this song." : ""));
        } else {
            info.setText("This file type can't hold tags, so changes are kept inside the app only.");
        }
        setBusy(false);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        dropPrepared();
        super.onDestroy();
    }

    // ---------- helpers on the form ----------

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String text(EditText e) {
        return e.getText().toString().trim();
    }

    /** The new text if the user changed the field, otherwise null (= leave the file's value alone). */
    @Nullable
    private static String changed(EditText e, String initial) {
        String now = text(e);
        return now.equals(initial.trim()) ? null : now;
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        for (View v : new View[]{btnSave, btnOnline, btnSmart, btnCover, btnRemove, eTitle, eArtist,
                eAlbum, eAlbumArtist, eYear, eTrack, eGenre}) {
            v.setEnabled(!busy);
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void setCover(@Nullable byte[] jpeg) {
        if (jpeg == null) {
            toast("Couldn't use that picture");
            return;
        }
        final Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            newCover = jpeg;
            removeCover = false;
            if (bmp != null) cover.setImageBitmap(bmp);
        });
    }

    // ---------- tidy title / online lookup ----------

    private void tidyTitle() {
        TitleParser.Result r = TitleParser.parse(text(eTitle), text(eArtist));
        String artist = r.artistLine;
        int orig = artist.indexOf(" · orig. ");
        if (orig >= 0) artist = artist.substring(orig + " · orig. ".length()).trim();
        String title = r.title;
        if (!r.tags.isEmpty()) title += " (" + join(r.tags) + ")";
        if (artist.equalsIgnoreCase("Unknown artist") || artist.equalsIgnoreCase("<unknown>")) artist = "";
        eTitle.setText(title);
        if (!artist.isEmpty()) eArtist.setText(artist);
        Toast.makeText(this, "Title tidied", Toast.LENGTH_SHORT).show();
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private void findOnline() {
        TitleParser.Result r = TitleParser.parse(text(eTitle), text(eArtist));
        String artist = r.artistLine;
        int orig = artist.indexOf(" · orig. ");
        if (orig >= 0) artist = artist.substring(orig + " · orig. ".length()).trim();
        if (artist.equalsIgnoreCase("Unknown artist")) artist = "";
        final String term = (r.title + " " + artist).trim();
        if (term.isEmpty()) {
            toast("Type a title first");
            return;
        }
        showOnlineSearch(term);
    }

    /** A search box (you can fix the words) above a clear list of results; tap a result to use it. */
    private void showOnlineSearch(String startTerm) {
        final float d = getResources().getDisplayMetrics().density;
        final int onSurface = com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);
        final int outline = com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOutline, 0x66FFFFFF);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), 0);

        final android.widget.EditText box = new android.widget.EditText(this);
        box.setHint("Song and artist");
        box.setSingleLine(true);
        box.setText(startTerm);
        box.setSelection(box.length());
        box.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        root.addView(box, new android.widget.LinearLayout.LayoutParams(-1, -2));

        final TextView status = new TextView(this);
        status.setTextSize(13);
        status.setAlpha(0.7f);
        status.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        root.addView(status, new android.widget.LinearLayout.LayoutParams(-1, -2));

        final java.util.ArrayList<OnlineTags.Hit> shown = new java.util.ArrayList<>();
        final android.widget.ListView list = new android.widget.ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(outline & 0x33FFFFFF | 0x22000000));
        list.setDividerHeight(Math.max(1, (int) d));
        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public android.view.View getView(int i, android.view.View v, android.view.ViewGroup parent) {
                OnlineTags.Hit h = shown.get(i);
                android.widget.LinearLayout row = new android.widget.LinearLayout(parent.getContext());
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
                android.util.TypedValue tv = new android.util.TypedValue();
                if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                    row.setBackgroundResource(tv.resourceId);
                }
                android.widget.LinearLayout col = new android.widget.LinearLayout(parent.getContext());
                col.setOrientation(android.widget.LinearLayout.VERTICAL);
                TextView t = new TextView(parent.getContext());
                t.setText(h.title);
                t.setTextSize(16);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                t.setTextColor(onSurface);
                col.addView(t);
                TextView a = new TextView(parent.getContext());
                a.setText(h.artist);
                a.setTextSize(14);
                a.setTextColor(onSurface);
                a.setAlpha(0.85f);
                col.addView(a);
                String more = h.album + (h.year.isEmpty() ? "" : (h.album.isEmpty() ? "" : " · ") + h.year);
                if (!more.isEmpty()) {
                    TextView m = new TextView(parent.getContext());
                    m.setText(more);
                    m.setTextSize(12);
                    m.setTextColor(onSurface);
                    m.setAlpha(0.6f);
                    col.addView(m);
                }
                row.addView(col, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
                TextView go = new TextView(parent.getContext());
                go.setText("›");
                go.setTextSize(26);
                go.setTextColor(onSurface);
                go.setAlpha(0.5f);
                go.setPadding((int) (12 * d), 0, 0, 0);
                row.addView(go);
                return row;
            }
        };
        list.setAdapter(adapter);
        root.addView(list, new android.widget.LinearLayout.LayoutParams(-1, (int) (320 * d)));

        final androidx.appcompat.app.AlertDialog dialog = new ThemedDialogBuilder(this)
                .setTitle("Find song info")
                .setView(root)
                .setPositiveButton("Search", null)
                .setNegativeButton("Cancel", null)
                .create();

        final int[] gen = {0};
        final Runnable search = () -> {
            final String term = box.getText().toString().trim();
            if (term.isEmpty()) return;
            final int my = ++gen[0];
            status.setText("Searching…");
            shown.clear();
            adapter.notifyDataSetChanged();
            io.execute(() -> {
                try {
                    final List<OnlineTags.Hit> hits = OnlineTags.search(term);
                    runOnUiThread(() -> {
                        if (my != gen[0] || !dialog.isShowing()) return;
                        shown.addAll(hits);
                        adapter.notifyDataSetChanged();
                        status.setText(hits.isEmpty()
                                ? "Nothing found. Try fewer words, e.g. only the song name."
                                : "Tap the right song to fill in the tags");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (my == gen[0]) status.setText("Couldn't search: " + e.getMessage());
                    });
                }
            });
        };
        list.setOnItemClickListener((p, v, pos, id) -> {
            dialog.dismiss();
            applyHit(shown.get(pos));
        });
        box.setOnEditorActionListener((v, action, ev) -> {
            search.run();
            return true;
        });
        dialog.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> search.run());
        search.run();
    }

    private void applyHit(OnlineTags.Hit h) {
        eTitle.setText(h.title);
        eArtist.setText(h.artist);
        eAlbum.setText(h.album);
        if (!h.albumArtist.isEmpty()) eAlbumArtist.setText(h.albumArtist);
        eYear.setText(h.year);
        eTrack.setText(h.trackText());
        eGenre.setText(h.genre);
        if (h.artworkUrl == null) return;
        setBusy(true);
        io.execute(() -> {
            try {
                byte[] raw = OnlineTags.cover(h);
                if (raw != null) setCover(squareJpeg(raw, 1000));
            } catch (Exception e) {
                toast("Filled the tags, but the cover didn't download");
            }
            runOnUiThread(() -> setBusy(false));
        });
    }

    // ---------- saving ----------

    private TagData buildEdit() {
        TagData e = new TagData();
        e.title = changed(eTitle, iTitle);
        e.artist = changed(eArtist, iArtist);
        e.album = changed(eAlbum, iAlbum);
        e.albumArtist = changed(eAlbumArtist, iAlbumArtist);
        e.year = changed(eYear, iYear);
        e.track = changed(eTrack, iTrack);
        e.genre = changed(eGenre, iGenre);
        if (newCover != null) {
            e.cover = newCover;
            e.coverMode = TagData.CoverMode.SET;
        } else if (removeCover) {
            e.coverMode = TagData.CoverMode.REMOVE;
        }
        return e;
    }

    private void save() {
        if (song == null) return;
        final TagData edit = buildEdit();
        if (!edit.hasText() && edit.coverMode == TagData.CoverMode.KEEP) {
            toast("Nothing changed");
            return;
        }
        if (format == null || !format.writable()) {
            saveInAppOnly();
            return;
        }
        pendingEdit = edit;
        setBusy(true);
        final Context app = getApplicationContext();
        io.execute(() -> {
            try {
                final TagWriter.Prepared p = TagWriter.prepare(app, song.uri, edit);
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        if (p != null) p.cleanup();
                        return;
                    }
                    if (p == null) {
                        saveInAppOnly();
                        return;
                    }
                    prepared = p;
                    requestWriteAccess();
                });
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false));
                toast("Couldn't prepare the file: " + e.getMessage());
            }
        });
    }

    private void requestWriteAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(),
                        Collections.singletonList(song.uri));
                writeLauncher.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build());
            } catch (Exception e) {
                dropPrepared();
                setBusy(false);
                toast("Couldn't ask for permission: " + e.getMessage());
            }
        } else {
            commit();
        }
    }

    private void commit() {
        final TagWriter.Prepared p = prepared;
        final TagData edit = pendingEdit;
        if (p == null || edit == null) {
            setBusy(false);
            return;
        }
        final Context app = getApplicationContext();
        io.execute(() -> {
            try {
                TagWriter.commit(app, song.uri, p, edit);
                // the file now holds the truth, so an old in-app override must not hide it
                Db.get(app).clearMeta(song.id);
                deleteCustomArt(song.id);
                p.cleanup();
                runOnUiThread(this::done);
            } catch (SecurityException se) {
                IntentSender sender = Build.VERSION.SDK_INT >= 29 ? recoverable(se) : null;
                if (sender != null) {
                    runOnUiThread(() -> writeLauncher.launch(new IntentSenderRequest.Builder(sender).build()));
                } else {
                    runOnUiThread(() -> {
                        dropPrepared();
                        setBusy(false);
                    });
                    toast("Android didn't allow editing this file");
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dropPrepared();
                    setBusy(false);
                });
                toast("Couldn't save: " + e.getMessage() + " (the original file was kept)");
            }
        });
    }

    @RequiresApi(29)
    @Nullable
    private static IntentSender recoverable(SecurityException se) {
        if (se instanceof android.app.RecoverableSecurityException) {
            return ((android.app.RecoverableSecurityException) se).getUserAction()
                    .getActionIntent().getIntentSender();
        }
        return null;
    }

    private void dropPrepared() {
        if (prepared != null) {
            prepared.cleanup();
            prepared = null;
        }
    }

    private void done() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_SONG_ID, song.id));
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    /** For files that can't hold tags: keep the text (and cover) inside the app instead. */
    private void saveInAppOnly() {
        final long id = song.id;
        final String t = text(eTitle), a = text(eArtist), al = text(eAlbum);
        final byte[] coverBytes = newCover;
        final boolean remove = removeCover;
        setBusy(true);
        io.execute(() -> {
            try {
                String[] old = Db.get(this).getMeta(id);
                String artPath = old == null ? "" : old[3];
                if (remove) {
                    deleteCustomArt(id);
                    artPath = "";
                } else if (coverBytes != null) {
                    deleteCustomArt(id);
                    File out = new File(artDir(), id + "_" + System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(coverBytes);
                    }
                    artPath = out.getAbsolutePath();
                }
                Db.get(this).setMeta(id, t, a, al, artPath);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Saved in the app (this file type can't hold tags)",
                            Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_SONG_ID, id));
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> setBusy(false));
                toast("Couldn't save: " + e.getMessage());
            }
        });
    }

    private File artDir() {
        File dir = new File(getFilesDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private void deleteCustomArt(long id) {
        File[] files = artDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(id + "_")) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    // ---------- images ----------

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** Centre-crops to a square no bigger than maxPx and returns it as JPEG. */
    @Nullable
    private static byte[] squareJpeg(byte[] src, int maxPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(src, 0, src.length, bounds);
            int sample = 1;
            int smallest = Math.min(bounds.outWidth, bounds.outHeight);
            while (smallest / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap full = BitmapFactory.decodeByteArray(src, 0, src.length, opts);
            if (full == null) return null;
            int side = Math.min(full.getWidth(), full.getHeight());
            Bitmap square = Bitmap.createBitmap(full, (full.getWidth() - side) / 2,
                    (full.getHeight() - side) / 2, side, side);
            if (side > maxPx) square = Bitmap.createScaledBitmap(square, maxPx, maxPx, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            square.compress(Bitmap.CompressFormat.JPEG, 92, out);
            return out.toByteArray();
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
