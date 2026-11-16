package com.hamo.styrixplayer.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/** All user-facing settings live here. */
public final class Prefs {
    private static final String NAME = "settings";

    public static final String THEME = "theme";                  // 0 system, 1 light, 2 dark
    public static final String DYNAMIC = "dynamic";              // wallpaper colors
    public static final String COLOR_MODE = "color_mode";        // 0 wallpaper, 1 app, 2 custom, 3 ambient
    public static final String CUSTOM_COLOR = "custom_color";
    public static final String AMBIENT_SEED = "ambient_seed";    // last color taken from a cover
    public static final String MIN_LEN = "min_len_sec";          // hide songs shorter than
    public static final String RESUME = "resume";                // restore last session
    public static final String SPEED_PER_TRACK = "speed_per_track";
    public static final String KEEP_PITCH = "keep_pitch";
    public static final String UPDATE_CHECK = "update_check";        // look for new releases on start
    public static final String UPDATE_LAST_CHECK = "update_last_check";
    public static final String UPDATE_SKIPPED = "update_skipped";
    public static final String SMART_TITLES = "smart_titles";    // clean up messy titles
    public static final String VIDEO_AMBIENT = "video_ambient";    // 0 calm, 1 normal, 2 fast, 3 live
    public static final String BG_MESH = "bg_mesh";              // corner-to-corner color mesh behind the player (off: blurred cover)
    public static final String HEATMAP = "heatmap";                // most-played graph above the seek bar
    public static final String SONG_SORT = "song_sort";           // 0 title, 1 artist, 2 longest, 3 shortest
    public static final String MUSIC_CACHE_MB = "music_cache_mb"; // streamed-song cache size, 0 = off

    private Prefs() {}

    /** How fast the video drives the ambient colors: 0 calm, 1 normal, 2 fast, 3 live. */
    public static int videoAmbient(Context c) {
        return Math.max(0, Math.min(3, sp(c).getInt(VIDEO_AMBIENT, 2)));
    }

    /** Milliseconds between two looks at the video picture. */
    public static int videoSampleMs(Context c) {
        return new int[]{300, 180, 100, 50}[videoAmbient(c)];
    }

    /** Milliseconds a color takes to blend into the next one (0 = instantly). */
    public static int videoBlendMs(Context c) {
        return new int[]{500, 260, 140, 0}[videoAmbient(c)];
    }

    /** Smallest color change worth repainting for. */
    public static int videoSkipDist(Context c) {
        return new int[]{24, 18, 10, 3}[videoAmbient(c)];
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    public static void setBool(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }

    public static long getLong(Context c, String key, long def) {
        return sp(c).getLong(key, def);
    }

    public static void setLong(Context c, String key, long value) {
        sp(c).edit().putLong(key, value).apply();
    }

    public static String getString(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    public static void setString(Context c, String key, String value) {
        sp(c).edit().putString(key, value).apply();
    }

    public static int getInt(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    public static void setInt(Context c, String key, int value) {
        sp(c).edit().putInt(key, value).apply();
    }

    public static long minLenMs(Context c) {
        return getInt(c, MIN_LEN, 30) * 1000L;
    }

    public static void applyNightMode(Context c) {
        int mode;
        switch (getInt(c, THEME, 0)) {
            case 1: mode = AppCompatDelegate.MODE_NIGHT_NO; break;
            case 2: mode = AppCompatDelegate.MODE_NIGHT_YES; break;
            default: mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(mode);
    }
}
