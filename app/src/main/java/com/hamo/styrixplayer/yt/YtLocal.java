package com.hamo.styrixplayer.yt;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;

import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.data.Song;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the copy of a YouTube Music song that is already on the phone, so it plays offline.
 * A song counts as local when it was downloaded from the app, or when a file in the library has
 * the same title and artist (checked against both the file's tags and your edited metadata) and,
 * when YouTube tells us the length, nearly the same duration.
 */
public final class YtLocal {

    private static final String PREFS = "yt_downloads";

    /** Matches found so far, so the player can swap a stream for the file at the last moment. */
    private static final Map<String, Long> known = new ConcurrentHashMap<>();

    private YtLocal() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void record(Context c, @Nullable String videoId, long songId) {
        if (videoId == null || songId <= 0) return;
        sp(c).edit().putLong(videoId, songId).apply();
        known.put(videoId, songId);
    }

    /** The downloaded copy's song id, or -1. */
    public static long songIdFor(Context c, String videoId) {
        long id = sp(c).getLong(videoId, -1);
        return id > 0 && Library.get(id) != null ? id : -1;
    }

    /** The YouTube video id a downloaded song came from, or null. */
    @Nullable
    public static String videoIdForSong(Context c, long songId) {
        for (Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Long && (Long) v == songId) return e.getKey();
        }
        return null;
    }

    /** Uri of an offline copy found earlier, or null. Safe to call from the player thread. */
    @Nullable
    public static Uri localUri(String videoId) {
        Long id = known.get(videoId);
        if (id == null) return null;
        Song s = Library.get(id);
        return s == null ? null : s.uri;
    }

    private static String norm(@Nullable String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|\\[.*?\\]", " ");
        return t.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static boolean artistMatches(String trackArtist, Song s) {
        String ta = norm(trackArtist.contains(",") ? trackArtist.substring(0, trackArtist.indexOf(',')) : trackArtist);
        if (ta.isEmpty()) return false;
        for (String a : new String[]{s.artist, s.rawArtist, s.artistLine}) {
            String sa = norm(a);
            if (sa.isEmpty() || sa.equals("unknown")) continue;
            if (sa.contains(ta) || ta.contains(sa)) return true;
        }
        return false;
    }

    /** videoId -> local song, for every track that has an offline copy. Call off the main thread. */
    public static Map<String, Song> match(Context c, List<YtTrack> tracks) {
        Map<String, Song> out = new HashMap<>();
        if (tracks.isEmpty() || !Library.isLoaded()) return out;

        Map<String, List<Song>> byTitle = new HashMap<>();
        for (Song s : Library.all()) {
            for (String t : new String[]{s.cleanTitle, s.title, s.rawTitle}) {
                String k = norm(t);
                if (k.isEmpty()) continue;
                List<Song> l = byTitle.get(k);
                if (l == null) byTitle.put(k, l = new ArrayList<>());
                if (!l.contains(s)) l.add(s);
            }
        }

        for (YtTrack t : tracks) {
            long id = songIdFor(c, t.videoId);
            if (id > 0) {
                out.put(t.videoId, Library.get(id));
                known.put(t.videoId, id);
                continue;
            }
            List<String> titleKeys = new ArrayList<>();
            titleKeys.add(norm(t.title));
            int dash = t.title.indexOf(" - ");
            if (dash > 0) titleKeys.add(norm(t.title.substring(dash + 3)));

            Song hit = null;
            for (String key : titleKeys) {
                List<Song> cands = byTitle.get(key);
                if (cands == null) continue;
                for (Song s : cands) {
                    if (t.durationMs > 0 && s.durationMs > 0
                            && Math.abs(s.durationMs - t.durationMs) > 5000) continue;
                    if (artistMatches(t.artist, s)) {
                        hit = s;
                        break;
                    }
                }
                if (hit != null) break;
            }
            if (hit != null) {
                out.put(t.videoId, hit);
                known.put(t.videoId, hit.id);
            }
        }
        return out;
    }

    /** The local file if there is one, otherwise the streamed version. */
    public static MediaItem itemFor(YtTrack t, @Nullable Map<String, Song> local) {
        Song s = local == null ? null : local.get(t.videoId);
        return s != null ? s.toMediaItem() : t.toMediaItem();
    }
}
