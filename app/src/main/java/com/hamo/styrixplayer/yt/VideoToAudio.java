package com.hamo.styrixplayer.yt;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.Composition;

import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.tags.TagData;
import com.hamo.styrixplayer.tags.Tags;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Turns the sound of a video into an audio song (.m4a). Media3 Transformer does the work: when the
 * video's sound is already AAC it is copied as it is (fast, no quality loss), otherwise it is
 * converted to AAC. Then title, artist and cover go into the file and it is saved to
 * Music/StyrixPlayer, so it shows up in the library with a proper name.
 */
public final class VideoToAudio {

    public interface Listener {
        void onProgress(int percent);
        /** Sound extracted; the file still has to be saved with {@link #save}. */
        void onExported(File audio);
        void onError(String message);
    }

    private static final String FOLDER = "StyrixPlayer";

    private VideoToAudio() {}

    /**
     * Starts the export. Call on the MAIN thread (Transformer needs a looper thread); the listener
     * is called on that same thread. Returns an object to cancel with.
     */
    public static Handle export(Context ctx, Uri video, long startMs, long endMs, final Listener l) {
        final Context app = ctx.getApplicationContext();
        final File out = new File(app.getCacheDir(), "v2a_export.m4a");
        //noinspection ResultOfMethodCallIgnored
        out.delete();

        MediaItem.Builder mi = new MediaItem.Builder().setUri(video);
        if (startMs > 0 || endMs > 0) {
            MediaItem.ClippingConfiguration.Builder cc = new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(Math.max(0, startMs));
            if (endMs > startMs) cc.setEndPositionMs(endMs);
            mi.setClippingConfiguration(cc.build());
        }
        EditedMediaItem item = new EditedMediaItem.Builder(mi.build()).setRemoveVideo(true).build();

        final Handle h = new Handle();
        h.transformer = new Transformer.Builder(app)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        h.done = true;
                        l.onExported(out);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException e) {
                        h.done = true;
                        l.onError(e.getMessage() == null ? "The video's sound couldn't be extracted" : e.getMessage());
                    }
                })
                .build();
        try {
            h.transformer.start(item, out.getAbsolutePath());
        } catch (RuntimeException e) {
            h.done = true;
            l.onError("Couldn't start: " + e.getMessage());
            return h;
        }
        final ProgressHolder ph = new ProgressHolder();
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(new Runnable() {
            @Override
            public void run() {
                if (h.done) return;
                int st = h.transformer.getProgress(ph);
                if (st == Transformer.PROGRESS_STATE_AVAILABLE) l.onProgress(ph.progress);
                main.postDelayed(this, 300);
            }
        });
        return h;
    }

    public static final class Handle {
        Transformer transformer;
        volatile boolean done;

        public void cancel() {
            done = true;
            try { if (transformer != null) transformer.cancel(); } catch (RuntimeException ignored) { }
        }
    }

    /**
     * Puts title / artist / cover into the exported file and saves it to Music/StyrixPlayer.
     * Blocking: call off the main thread. Returns the new song's MediaStore id.
     */
    public static long save(Context ctx, File audio, String title, @Nullable String artist,
                            @Nullable byte[] cover) throws IOException {
        Context app = ctx.getApplicationContext();
        ContentResolver cr = app.getContentResolver();
        title = title.trim();
        artist = artist == null ? "" : artist.trim();

        // tags go into a second copy; the plain export is used if that doesn't check out
        File tagged = new File(app.getCacheDir(), "v2a_tagged.m4a");
        File use = audio;
        try {
            TagData e = new TagData();
            e.title = title;
            if (!artist.isEmpty()) { e.artist = artist; e.albumArtist = artist; }
            if (cover != null && cover.length > 0) {
                e.cover = cover;
                e.coverMode = TagData.CoverMode.SET;
            }
            Tags.write(audio, tagged, e);
            TagData back = Tags.read(tagged);
            if (title.equals(back.title == null ? "" : back.title.trim()) && tagged.length() > audio.length() / 2) {
                use = tagged;
            }
        } catch (IOException | RuntimeException ignored) {
            use = audio;
        }

        String name = AudioDownloader.safeName(title) + ".m4a";
        Uri uri;
        File legacy = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            v.put(MediaStore.Audio.Media.TITLE, title);
            if (!artist.isEmpty()) v.put(MediaStore.Audio.Media.ARTIST, artist);
            v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IOException("Couldn't create the file in Music");
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Couldn't create " + dir);
            legacy = new File(dir, name);
            for (int i = 2; legacy.exists() && i < 100; i++) {
                legacy = new File(dir, name.substring(0, name.length() - 4) + " (" + i + ").m4a");
            }
            uri = Uri.fromFile(legacy);
        }

        boolean ok = false;
        try (InputStream in = new FileInputStream(use);
             OutputStream out = Build.VERSION.SDK_INT >= 29 ? cr.openOutputStream(uri) : new FileOutputStream(legacy)) {
            if (out == null) throw new IOException("Couldn't open the file for writing");
            AudioDownloader.pipe(in, out);
            out.flush();
            ok = true;
        } finally {
            if (!ok) {
                if (Build.VERSION.SDK_INT >= 29) cr.delete(uri, null, null);
                else if (legacy != null) //noinspection ResultOfMethodCallIgnored
                    legacy.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tagged.delete();
            //noinspection ResultOfMethodCallIgnored
            audio.delete();
        }

        long id;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            id = ContentUris.parseId(uri);
            AudioDownloader.waitForDuration(cr, uri);
        } else {
            id = AudioDownloader.scanLegacy(app, legacy);
        }

        String art = "";
        if (cover != null && cover.length > 0) {
            File dest = new File(AudioDownloader.coversDir(app), "v2a_" + id + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(dest)) {
                fo.write(cover);
                art = dest.getAbsolutePath();
            } catch (IOException ignored) {
                art = "";
            }
        }
        // in-app override too: the cover and name show at once, even if the tags couldn't be written
        Db.get(app).setMeta(id, title, artist, "", art);
        Library.load(app);
        return id;
    }
}
