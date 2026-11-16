package com.hamo.styrixplayer.data;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory copy of the scanned music library, shared by every screen. */
public final class Library {
    private static final List<Song> songs = new ArrayList<>();
    private static final Map<Long, Song> byId = new HashMap<>();
    private static boolean loaded;

    private Library() {}

    /** Scans MediaStore. Call off the main thread. */
    public static synchronized void load(Context c) {
        TitleParser.enabled = Prefs.getBool(c, Prefs.SMART_TITLES, true);
        List<Song> found = SongScanner.scan(c.getApplicationContext(), Prefs.minLenMs(c));
        Map<Long, String[]> meta = Db.get(c).allMeta();

        songs.clear();
        byId.clear();
        for (Song s : found) {
            String[] m = meta.get(s.id);
            Song use = m == null ? s : s.withMeta(pick(m[0], s.rawTitle), pick(m[1], s.rawArtist),
                    pick(m[2], s.rawAlbum), artOf(m[3]));
            songs.add(use);
            byId.put(use.id, use);
        }
        loaded = true;
    }

    @Nullable
    private static android.net.Uri artOf(@Nullable String path) {
        if (path == null || path.isEmpty()) return null;
        java.io.File f = new java.io.File(path);
        return f.exists() ? android.net.Uri.fromFile(f) : null;
    }

    private static String pick(String override, String raw) {
        return override == null || override.isEmpty() ? raw : override;
    }

    /** Re-applies the saved metadata edit for one song and returns the updated song. */
    @Nullable
    public static synchronized Song applyMeta(Context c, long id) {
        Song base = byId.get(id);
        if (base == null) return null;
        String[] m = Db.get(c).getMeta(id);
        Song updated = m == null
                ? base.withMeta(base.rawTitle, base.rawArtist, base.rawAlbum, null)
                : base.withMeta(pick(m[0], base.rawTitle), pick(m[1], base.rawArtist),
                pick(m[2], base.rawAlbum), artOf(m[3]));
        byId.put(id, updated);
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).id == id) {
                songs.set(i, updated);
                break;
            }
        }
        return updated;
    }

    /** Drops a song from the in-memory library (after it was deleted from the device). */
    public static synchronized void remove(long id) {
        byId.remove(id);
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).id == id) {
                songs.remove(i);
                break;
            }
        }
    }

    public static synchronized void ensureLoaded(Context c) {
        if (!loaded) load(c);
    }

    public static synchronized boolean isLoaded() {
        return loaded;
    }

    public static synchronized List<Song> all() {
        return new ArrayList<>(songs);
    }

    @Nullable
    public static synchronized Song get(long id) {
        return byId.get(id);
    }

    /** Maps ids to songs, silently skipping ones that no longer exist on the device. */
    public static synchronized List<Song> resolve(List<Long> ids) {
        List<Song> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Song s = byId.get(id);
            if (s != null) out.add(s);
        }
        return out;
    }
}
