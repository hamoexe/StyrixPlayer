package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;

import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Song;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads album art off the main thread with an in-memory cache. No external libraries. */
public final class ArtLoader {

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };
    private static final Set<String> missing = Collections.synchronizedSet(new HashSet<>());
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private ArtLoader() {}

    /** Blocking decode. Call off the main thread. */
    @Nullable
    public static Bitmap decode(Context ctx, @Nullable Uri songUri, @Nullable Uri artUri, int px) {
        Bitmap bmp = null;
        // a photo picked in the metadata editor always wins over the file's own art
        if (artUri != null && "file".equals(artUri.getScheme())) {
            bmp = decodeStream(ctx, artUri, px);
            if (bmp != null) return bmp;
        }
        if (artUri != null && ("http".equals(artUri.getScheme()) || "https".equals(artUri.getScheme()))) {
            return decodeHttp(ctx, artUri, px); // YouTube Music thumbnails (disk cached)
        }
        if (Build.VERSION.SDK_INT >= 29 && songUri != null) {
            try {
                bmp = ctx.getContentResolver().loadThumbnail(songUri, new Size(px, px), null);
            } catch (Exception ignored) {
            }
        }
        if (bmp == null && artUri != null) bmp = decodeStream(ctx, artUri, px);
        return bmp;
    }

    /** Shrinks big images but keeps their proportions (the view crops them to a square). */
    private static Bitmap fit(Bitmap full, int px) {
        int w = full.getWidth(), h = full.getHeight();
        int shorter = Math.min(w, h);
        if (shorter <= px * 2) return full;
        float s = (float) px / shorter;
        return Bitmap.createScaledBitmap(full, Math.max(1, Math.round(w * s)),
                Math.max(1, Math.round(h * s)), true);
    }

    private static final long ART_CACHE_MAX = 150L * 1024 * 1024;
    private static final Object artLock = new Object();

    private static java.io.File artDir(Context ctx) {
        java.io.File d = new java.io.File(ctx.getCacheDir(), "yt_art");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static String hash(String url) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-1").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    /** YouTube covers are kept on disk, so a song's cover appears instantly (and offline) next time. */
    @Nullable
    private static Bitmap decodeHttp(Context ctx, Uri uri, int px) {
        java.io.File f = new java.io.File(artDir(ctx), hash(uri.toString()));
        if (f.length() > 0) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                f.setLastModified(System.currentTimeMillis());
                return fit(b, px);
            }
            f.delete();
        }
        java.net.HttpURLConnection c = null;
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            try (InputStream in = c.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Bitmap full = BitmapFactory.decodeFile(tmp.getAbsolutePath());
            if (full == null) {
                tmp.delete();
                return null;
            }
            if (!tmp.renameTo(f)) tmp.delete();
            trimArtCache(ctx);
            return fit(full, px);
        } catch (Exception e) {
            tmp.delete();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Keeps the cover cache under its limit by deleting the least recently used files. */
    private static void trimArtCache(Context ctx) {
        synchronized (artLock) {
            java.io.File[] files = artDir(ctx).listFiles();
            if (files == null) return;
            long total = 0;
            for (java.io.File x : files) total += x.length();
            if (total <= ART_CACHE_MAX) return;
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (java.io.File x : files) {
                if (total <= ART_CACHE_MAX * 8 / 10) break;
                total -= x.length();
                x.delete();
            }
        }
    }

    public static long artCacheBytes(Context ctx) {
        java.io.File[] files = artDir(ctx).listFiles();
        long total = 0;
        if (files != null) for (java.io.File x : files) total += x.length();
        return total;
    }

    public static void clearArtCache(Context ctx) {
        java.io.File[] files = artDir(ctx).listFiles();
        if (files != null) for (java.io.File x : files) x.delete();
        cache.evictAll();
        missing.clear();
    }

    @Nullable
    private static Bitmap decodeStream(Context ctx, Uri uri, int px) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                Bitmap full = BitmapFactory.decodeStream(in);
                if (full != null) {
                    return fit(full, px);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Forget cached art for a song (after its cover photo changed). */
    public static void invalidate(long id) {
        for (String k : cache.snapshot().keySet()) {
            if (k.startsWith(id + ":")) cache.remove(k);
        }
        synchronized (missing) {
            java.util.Iterator<String> it = missing.iterator();
            while (it.hasNext()) if (it.next().startsWith(id + ":")) it.remove();
        }
    }

    public static void load(Context ctx, long id, @Nullable Uri songUri, @Nullable Uri artUri,
                            ImageView iv, int px, int placeholderRes) {
        final String key = id + ":" + px;
        iv.animate().cancel();
        iv.setAlpha(1f);
        iv.setTag(key);

        Bitmap hit = cache.get(key);
        if (hit != null) {
            iv.setImageBitmap(hit);
            return;
        }
        iv.setImageResource(placeholderRes);
        if (missing.contains(key)) return;

        final Context app = ctx.getApplicationContext();
        pool.execute(() -> {
            Bitmap b = decode(app, songUri, artUri, px);
            if (b == null) {
                missing.add(key);
                return;
            }
            cache.put(key, b);
            main.post(() -> {
                if (key.equals(iv.getTag())) {
                    iv.setImageBitmap(b);
                    iv.setAlpha(0f);
                    iv.animate().alpha(1f).setDuration(180).start();
                }
            });
        });
    }

    public static void load(Context ctx, Song s, ImageView iv, int px) {
        load(ctx, s.id, s.uri, s.artUri, iv, px, R.drawable.ic_art_placeholder);
    }

    public static void loadItem(Context ctx, MediaItem item, ImageView iv, int px) {
        long id;
        try {
            id = Long.parseLong(item.mediaId);
        } catch (NumberFormatException e) {
            id = item.mediaId.hashCode();
        }
        Uri songUri = item.localConfiguration != null ? item.localConfiguration.uri : null;
        load(ctx, id, songUri, item.mediaMetadata.artworkUri, iv, px, R.drawable.ic_art_placeholder);
    }
}
