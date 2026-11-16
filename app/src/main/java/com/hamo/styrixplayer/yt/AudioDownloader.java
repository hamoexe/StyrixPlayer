package com.hamo.styrixplayer.yt;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.tags.TagData;
import com.hamo.styrixplayer.tags.Tags;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Saves a resolved YouTube audio stream into Music/StyrixPlayer and adds it to the library. */
public final class AudioDownloader {

    public interface Progress {
        void onProgress(long done, long total);
    }

    public static final class Cancelled extends IOException {
        public Cancelled() { super("Cancelled"); }
    }

    private static final String FOLDER = "StyrixPlayer";
    private static final long CHUNK = 8L * 1024 * 1024; // YouTube throttles unbounded requests

    /** True when the last download put its tags into the file (false: only the in-app override). */
    public static volatile boolean lastEmbedded;

    private AudioDownloader() {}

    private static File coversDir(Context app) {
        File dir = new File(app.getFilesDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /**
     * Writes title / artist / cover into the downloaded file. Works on a copy and checks it by
     * reading it back; the real file is only touched when the copy is good, and put back if
     * writing over it fails. Returns false when the format can't hold tags (webm) or anything failed.
     */
    private static boolean embedTags(Context app, Uri uri, File legacy, YouTubeResolver.Info info,
                                     File cover) {
        File dir = new File(app.getCacheDir(), "dltag");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File src = new File(dir, "src.bin"), out = new File(dir, "out.bin");
        ContentResolver cr = app.getContentResolver();
        try {
            try (InputStream in = legacy != null ? new java.io.FileInputStream(legacy) : cr.openInputStream(uri);
                 OutputStream os = new FileOutputStream(src)) {
                if (in == null) return false;
                pipe(in, os);
            }
            if (!Tags.detect(src).writable()) return false;

            TagData e = new TagData();
            e.title = info.title;
            e.artist = info.author;
            e.albumArtist = info.author;
            if (cover != null && cover.length() > 0) {
                e.cover = readFile(cover);
                e.coverMode = TagData.CoverMode.SET;
            }
            Tags.write(src, out, e);

            TagData back = Tags.read(out);
            if (info.title != null && !info.title.trim().equals(back.title == null ? "" : back.title.trim())) return false;
            if (e.coverMode == TagData.CoverMode.SET && (back.cover == null || back.cover.length == 0)) return false;
            if (out.length() < src.length() / 2) return false;

            try {
                writeOver(cr, uri, legacy, out);
            } catch (IOException | RuntimeException ex) {
                try { writeOver(cr, uri, legacy, src); } catch (Exception ignored) { }
                return false;
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private static void writeOver(ContentResolver cr, Uri uri, File legacy, File from) throws IOException {
        try (OutputStream os = legacy != null ? new FileOutputStream(legacy) : cr.openOutputStream(uri, "wt");
             InputStream in = new java.io.FileInputStream(from)) {
            if (os == null) throw new IOException("Can't open the file for writing");
            pipe(in, os);
            os.flush();
        }
    }

    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static byte[] readFile(File f) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try (InputStream in = new java.io.FileInputStream(f)) { pipe(in, b); }
        return b.toByteArray();
    }

    /** Blocking. Call off the main thread. Returns the new song's MediaStore id. */
    public static long download(Context ctx, YouTubeResolver.Info info, Progress progress,
                                java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        Context app = ctx.getApplicationContext();
        ContentResolver cr = app.getContentResolver();
        String name = safeName(info.title) + "." + info.extension();

        Uri uri;
        File legacyFile = null;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Audio.Media.MIME_TYPE, info.baseMime());
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + FOLDER);
            v.put(MediaStore.Audio.Media.TITLE, info.title);
            v.put(MediaStore.Audio.Media.ARTIST, info.author);
            v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IOException("Couldn't create the file in Music");
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MUSIC), FOLDER);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Couldn't create " + dir);
            legacyFile = new File(dir, name);
            uri = Uri.fromFile(legacyFile);
        }

        // The cover is fetched first so it can go INTO the file together with the title / artist.
        File coverFile = fetchCover(app, info, new File(coversDir(app), "yt_" + info.videoId + ".jpg"));

        boolean ok = false;
        try (OutputStream out = Build.VERSION.SDK_INT >= 29
                ? cr.openOutputStream(uri) : new FileOutputStream(legacyFile)) {
            if (out == null) throw new IOException("Couldn't open the file for writing");
            copyStream(info, out, progress, cancel);
            ok = true;
        } finally {
            if (!ok) {
                if (Build.VERSION.SDK_INT >= 29) cr.delete(uri, null, null);
                else if (legacyFile != null) //noinspection ResultOfMethodCallIgnored
                    legacyFile.delete();
            }
        }

        // Put title, artist and cover into the file itself (so every other app shows them too).
        boolean embedded = embedTags(app, uri, legacyFile, info, coverFile);

        long id;
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            id = ContentUris.parseId(uri);
            waitForDuration(cr, uri);
        } else {
            id = scanLegacy(app, legacyFile);
        }

        // Cover art + tidy title/artist, applied as a metadata override so the library shows them.
        String art = null;
        if (coverFile != null && coverFile.exists()) {
            File dest = new File(coversDir(app), "yt_" + id + ".jpg");
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            art = coverFile.renameTo(dest) ? dest.getAbsolutePath() : coverFile.getAbsolutePath();
        }
        // In-app override: shows the cover at once and covers files that can't hold tags (webm).
        // When the tags are in the file the editor / scanner read them from there.
        Db.get(app).setMeta(id, info.title, info.author, "", art == null ? "" : art);
        lastEmbedded = embedded;
        YtLocal.record(app, info.videoId, id);
        Library.load(app);
        return id;
    }

    private static void copyStream(YouTubeResolver.Info info, OutputStream out, Progress progress,
                                   java.util.concurrent.atomic.AtomicBoolean cancel) throws IOException {
        long total = info.contentLength;
        long pos = 0;
        byte[] buf = new byte[64 * 1024];
        while (total < 0 || pos < total) {
            if (cancel.get()) throw new Cancelled();
            long end = total < 0 ? pos + CHUNK - 1 : Math.min(pos + CHUNK, total) - 1;
            HttpURLConnection c = (HttpURLConnection) new URL(info.streamUrl).openConnection();
            try {
                c.setConnectTimeout(15_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("User-Agent", NpDownloader.UA);
                c.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                int code = c.getResponseCode();
                if (code == 416 && pos > 0 && total < 0) break; // asked past the end: finished
                if (code != 200 && code != 206) throw new IOException("Download refused (HTTP " + code + ")");
                long got = 0;
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel.get()) throw new Cancelled();
                        out.write(buf, 0, n);
                        got += n;
                        pos += n;
                        progress.onProgress(pos, total);
                    }
                }
                if (got == 0) break;
                if (total < 0 && got < CHUNK) break; // unknown length: a short chunk is the end
            } finally {
                c.disconnect();
            }
        }
        if (total > 0 && pos < total) throw new IOException("Download ended early");
    }

    /** MediaStore fills in DURATION a moment after the file stops being pending. */
    private static void waitForDuration(ContentResolver cr, Uri uri) {
        for (int i = 0; i < 20; i++) {
            try (Cursor c = cr.query(uri, new String[]{MediaStore.Audio.Media.DURATION}, null, null, null)) {
                if (c != null && c.moveToFirst() && c.getLong(0) > 0) return;
            } catch (RuntimeException ignored) {
                return;
            }
            try { Thread.sleep(250); } catch (InterruptedException e) { return; }
        }
    }

    private static long scanLegacy(Context app, File f) throws IOException {
        final AtomicReference<Uri> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        MediaScannerConnection.scanFile(app, new String[]{f.getAbsolutePath()}, null,
                (path, u) -> { result.set(u); latch.countDown(); });
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        Uri u = result.get();
        if (u == null) throw new IOException("The file saved but wasn't picked up by the media scanner");
        return ContentUris.parseId(u);
    }

    /** Downloads the best square cover to dest. Null when none could be fetched. */
    private static File fetchCover(Context app, YouTubeResolver.Info info, File dest) {
        String[] candidates = {
                info.coverUrl,
                "https://i.ytimg.com/vi/" + info.videoId + "/maxresdefault.jpg",
                info.thumbUrl,
                "https://i.ytimg.com/vi/" + info.videoId + "/hqdefault.jpg"};
        for (String u : candidates) {
            if (u == null || u.isEmpty()) continue;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                if (c.getResponseCode() != 200) { c.disconnect(); continue; }
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                c.disconnect();
                if (dest.length() > 0) {
                    squareCrop(dest);
                    return dest;
                }
            } catch (IOException ignored) {
                // try the next size
            }
        }
        return null;
    }

    /** Video thumbnails are 16:9; keep only the centre square so covers aren't stretched anywhere. */
    private static void squareCrop(File f) {
        try {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b == null || b.getWidth() == b.getHeight()) return;
            int side = Math.min(b.getWidth(), b.getHeight());
            android.graphics.Bitmap sq = android.graphics.Bitmap.createBitmap(
                    b, (b.getWidth() - side) / 2, (b.getHeight() - side) / 2, side, side);
            try (FileOutputStream out = new FileOutputStream(f)) {
                sq.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out);
            }
        } catch (Exception ignored) {
            // keep the original file
        }
    }

    private static String safeName(String s) {
        String n = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.isEmpty() ? "youtube_audio" : n;
    }
}
