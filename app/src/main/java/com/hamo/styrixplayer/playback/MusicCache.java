package com.hamo.styrixplayer.playback;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import com.hamo.styrixplayer.data.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.NavigableSet;

/**
 * Disk cache for streamed YouTube songs. A song that has been played (even partly) is kept, so
 * playing it again starts instantly, skips asking YouTube for a link and works without internet.
 * The cache key is the song's own address ("ytm:&lt;videoId&gt;"), which never expires.
 */
public final class MusicCache {

    public static final int DEFAULT_MB = 512;
    private static SimpleCache cache;

    private MusicCache() {}

    public static int limitMb(Context c) {
        return Prefs.getInt(c, Prefs.MUSIC_CACHE_MB, DEFAULT_MB);
    }

    public static boolean enabled(Context c) {
        return limitMb(c) > 0;
    }

    /** The one cache for this process (a second SimpleCache on the same folder would crash). */
    @Nullable
    public static synchronized SimpleCache get(Context c) {
        if (cache != null) return cache;
        try {
            Context app = c.getApplicationContext();
            File dir = new File(app.getCacheDir(), "yt_audio");
            long max = Math.max(64, limitMb(app)) * 1024L * 1024L; // applies from the next app start
            cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(max),
                    new StandaloneDatabaseProvider(app));
        } catch (Throwable t) {
            cache = null;
        }
        return cache;
    }

    public static long usedBytes(Context c) {
        SimpleCache sc = get(c);
        return sc == null ? 0 : sc.getCacheSpace();
    }

    public static void clear(Context c) {
        SimpleCache sc = get(c);
        if (sc == null) return;
        for (String key : new ArrayList<>(sc.getKeys())) {
            try {
                sc.removeResource(key);
            } catch (Throwable ignored) {
            }
        }
    }
}
