package com.hamo.styrixplayer.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import com.hamo.styrixplayer.tags.TagData;
import com.hamo.styrixplayer.tags.Tags;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Safe way to write tags into a song on the device:
 *  1. prepare(): copy the song to the app cache, write the new tags into a second copy, check it.
 *  2. (the caller asks Android for permission to modify the song)
 *  3. commit(): write the checked copy over the original; if that fails, put the original back.
 * Call these off the main thread.
 */
public final class TagWriter {

    public static final class Prepared {
        public final File src, out;
        public final Tags.Format format;

        Prepared(File src, File out, Tags.Format format) {
            this.src = src;
            this.out = out;
            this.format = format;
        }

        public void cleanup() {
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
        }
    }

    private TagWriter() {}

    /** Looks at the first bytes of the song to see which tag format it uses. */
    public static Tags.Format detect(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Can't open the song");
            byte[] head = new byte[16];
            int n = 0, r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
            return Tags.detect(java.util.Arrays.copyOf(head, n));
        }
    }

    /** Null when this format can't be tagged. */
    @Nullable
    public static Prepared prepare(Context c, Uri uri, TagData edit) throws IOException {
        Tags.Format fmt = detect(c, uri);
        if (!fmt.writable()) return null;

        File dir = new File(c.getCacheDir(), "tagwork");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File src = new File(dir, "src.bin"), out = new File(dir, "out.bin");

        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(src)) {
            if (in == null) throw new IOException("Can't open the song");
            copy(in, os);
        }
        if (dir.getUsableSpace() < src.length() + 8L * 1024 * 1024) {
            src.delete();
            throw new IOException("Not enough free space to edit this file");
        }

        try {
            Tags.write(src, out, edit);
            TagData back = Tags.read(out);
            check(edit.title, back.title, "title");
            check(edit.artist, back.artist, "artist");
            check(edit.album, back.album, "album");
            if (edit.coverMode == TagData.CoverMode.SET && (back.cover == null || back.cover.length == 0)) {
                throw new IOException("The cover didn't make it into the file");
            }
            if (out.length() < src.length() / 2) throw new IOException("The new file looks too small");
        } catch (IOException | RuntimeException e) {
            src.delete();
            out.delete();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        return new Prepared(src, out, fmt);
    }

    private static void check(@Nullable String wanted, @Nullable String got, String what) throws IOException {
        if (wanted == null) return;
        String w = wanted.trim(), g = got == null ? "" : got.trim();
        if (!w.equals(g)) throw new IOException("Check failed: " + what + " didn't come back the same");
    }

    /** Writes the prepared file over the song. SecurityException = no permission (nothing was written). */
    public static void commit(Context c, Uri uri, Prepared p, TagData edit) throws IOException {
        ContentResolver cr = c.getContentResolver();
        try {
            copyTo(cr, uri, p.out);
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                if (pfd != null && pfd.getStatSize() != p.out.length()) {
                    throw new IOException("The saved file has the wrong size");
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IOException e) {
            restore(cr, uri, p);
            throw e;
        } catch (RuntimeException e) {
            restore(cr, uri, p);
            throw new IOException(e);
        }
        refreshMediaStore(c, uri, edit);
    }

    private static void restore(ContentResolver cr, Uri uri, Prepared p) {
        try {
            copyTo(cr, uri, p.src);
        } catch (Exception ignored) {
        }
    }

    private static void copyTo(ContentResolver cr, Uri uri, File from) throws IOException {
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rwt")) {
            if (pfd == null) throw new IOException("Can't open the song for writing");
            try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                 FileInputStream in = new FileInputStream(from)) {
                copy(in, fos);
                fos.flush();
                fos.getFD().sync();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    /** Tells Android's media database about the new tags so lists update straight away. */
    private static void refreshMediaStore(Context c, Uri uri, TagData e) {
        ContentResolver cr = c.getContentResolver();
        try {
            ContentValues v = new ContentValues();
            if (notEmpty(e.title)) v.put(MediaStore.Audio.Media.TITLE, e.title.trim());
            if (notEmpty(e.artist)) v.put(MediaStore.Audio.Media.ARTIST, e.artist.trim());
            if (notEmpty(e.album)) v.put(MediaStore.Audio.Media.ALBUM, e.album.trim());
            if (Build.VERSION.SDK_INT >= 30 && notEmpty(e.albumArtist)) {
                v.put(MediaStore.Audio.Media.ALBUM_ARTIST, e.albumArtist.trim());
            }
            Integer year = leadingInt(e.year, 4);
            if (year != null) v.put(MediaStore.Audio.Media.YEAR, year);
            Integer track = leadingInt(e.track, 4);
            if (track != null) v.put(MediaStore.Audio.Media.TRACK, track);
            if (v.size() > 0) cr.update(uri, v, null, null);
        } catch (Exception ignored) {
        }
        // let the scanner re-read the file too (genre, cover thumbnails ...)
        try (Cursor cur = cr.query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String path = cur.getString(0);
                if (path != null) MediaScannerConnection.scanFile(c, new String[]{path}, null, null);
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean notEmpty(@Nullable String s) {
        return s != null && !s.trim().isEmpty();
    }

    @Nullable
    private static Integer leadingInt(@Nullable String s, int maxDigits) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < maxDigits; i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (sb.length() > 0) break;
            else if (ch != ' ') return null;
        }
        if (sb.length() == 0) return null;
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
