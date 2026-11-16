package com.hamo.styrixplayer.yt;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Remembers which songs are liked on YouTube Music so the heart shows the right state. */
public final class YtLikes {

    private static final String PREFS = "yt_likes";
    private static final String KEY = "ids";

    private YtLikes() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isLiked(Context c, String videoId) {
        return sp(c).getStringSet(KEY, new HashSet<>()).contains(videoId);
    }

    public static void set(Context c, String videoId, boolean liked) {
        Set<String> s = new HashSet<>(sp(c).getStringSet(KEY, new HashSet<>()));
        if (liked) s.add(videoId);
        else s.remove(videoId);
        sp(c).edit().putStringSet(KEY, s).apply();
    }

    /** Called with the full liked-songs list, so the hearts match your account. */
    public static void replaceAll(Context c, Collection<String> ids) {
        sp(c).edit().putStringSet(KEY, new HashSet<>(ids)).apply();
    }
}
