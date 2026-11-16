package com.hamo.styrixplayer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;

import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.color.DynamicColorsOptions;
import com.hamo.styrixplayer.data.Prefs;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App colors. Four styles: wallpaper colors, the built-in palette, one color you pick, or
 * "ambient", where the whole app is tinted from the cover of the song that is playing.
 * Colors are applied when a screen is created, so a change recreates the screen.
 */
public final class AppTheme {

    public static final int MODE_WALLPAPER = 0, MODE_APP = 1, MODE_CUSTOM = 2, MODE_AMBIENT = 3;
    public static final String[] MODE_LABELS = {"Wallpaper colors", "App colors", "Custom color",
            "Ambient (follow the cover)"};
    public static final String[] MODE_SUMMARIES = {
            "Android 12 and newer",
            "The built-in purple palette",
            "One color you choose",
            "The app takes its colors from the song's cover art"};

    public static final int DEFAULT_CUSTOM = 0xFF7C4DFF;
    public static final int[] SWATCHES = {
            0xFF7C4DFF, 0xFF2979FF, 0xFF00B8D4, 0xFF00BFA5, 0xFF43A047, 0xFFAEEA00,
            0xFFFFB300, 0xFFFF6D00, 0xFFE53935, 0xFFF50057, 0xFFD500F9, 0xFF607D8B};

    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static int generation;

    private AppTheme() {}

    public static int mode(Context c) {
        int def = Prefs.getBool(c, Prefs.DYNAMIC, true) ? MODE_WALLPAPER : MODE_APP;
        int m = Prefs.getInt(c, Prefs.COLOR_MODE, def);
        return m < 0 || m > MODE_AMBIENT ? def : m;
    }

    public static int customColor(Context c) {
        return Prefs.getInt(c, Prefs.CUSTOM_COLOR, DEFAULT_CUSTOM);
    }

    /** The color the scheme is built from, or 0 when the mode doesn't use one. */
    private static int seed(Context c) {
        switch (mode(c)) {
            case MODE_CUSTOM:
                return customColor(c);
            default:
                return 0;
        }
    }

    /** Changes whenever the colors a screen should have change. */
    public static String key(Context c) {
        return mode(c) + ":" + seed(c) + ":" + com.hamo.styrixplayer.I18n.key(c);
    }

    /** Call in onCreate, before the layout is inflated. */
    public static void apply(Activity a) {
        int m = mode(a);
        if (m == MODE_WALLPAPER) {
            DynamicColors.applyToActivityIfAvailable(a);
        } else if (m == MODE_CUSTOM) {
            DynamicColors.applyToActivityIfAvailable(a,
                    new DynamicColorsOptions.Builder().setContentBasedSource(seed(a)).build());
        } else if (m == MODE_AMBIENT) {
            // start from a theme in the cover's hue (not the baseline purple), so anything the live
            // recolor misses (popup menus, tabs, placeholders, outlines) still matches the cover
            int s = Prefs.getInt(a, Prefs.AMBIENT_SEED, 0);
            if (s == 0) s = customColor(a);
            DynamicColors.applyToActivityIfAvailable(a,
                    new DynamicColorsOptions.Builder().setContentBasedSource(s).build());
        }
    }

    /** A new song started: in ambient mode, pick up its cover's color. */
    public static void onTrack(final BaseActivity a, @Nullable MediaItem item) {
        if (item == null || mode(a) != MODE_AMBIENT) return;
        final int gen = ++generation;
        final Context app = a.getApplicationContext();
        final Uri songUri = item.localConfiguration != null ? item.localConfiguration.uri : null;
        final Uri art = item.mediaMetadata.artworkUri;
        pool.execute(() -> {
            Bitmap b = ArtLoader.decode(app, songUri, art, 96);
            if (b == null) return;
            final int found = seedFrom(b);
            if (found == 0) return;
            main.post(() -> {
                if (gen != generation || a.isFinishing() || a.isDestroyed()) return;
                int old = Prefs.getInt(app, Prefs.AMBIENT_SEED, 0);
                if (old != 0 && similar(old, found)) return; // too close to notice
                Prefs.setInt(app, Prefs.AMBIENT_SEED, found);
                AmbientTheme.animateTo(a, found); // blends in place, nothing is rebuilt
            });
        });
    }

    private static boolean similar(int a, int b) {
        float[] x = new float[3], y = new float[3];
        Color.colorToHSV(a, x);
        Color.colorToHSV(b, y);
        float dh = Math.abs(x[0] - y[0]);
        if (dh > 180f) dh = 360f - dh;
        return dh < 14f && Math.abs(x[1] - y[1]) < 0.2f && Math.abs(x[2] - y[2]) < 0.2f;
    }

    /** The most "colorful" shade in the picture, ignoring near-black, near-white and grey. */
    static int seedFrom(Bitmap src) {
        Bitmap s = Bitmap.createScaledBitmap(src, 48, 48, true);
        final int buckets = 12;
        double[] weight = new double[buckets], sx = new double[buckets], sy = new double[buckets],
                ss = new double[buckets], sv = new double[buckets];
        double allH = 0, allS = 0, allV = 0;
        float[] hsv = new float[3];
        int n = 0;
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                Color.colorToHSV(s.getPixel(x, y), hsv);
                allS += hsv[1];
                allV += hsv[2];
                n++;
                if (hsv[2] < 0.18f || hsv[1] < 0.22f) continue;
                double w = hsv[1] * (0.35 + hsv[2]);
                int bk = ((int) (hsv[0] / 30f)) % buckets;
                double rad = Math.toRadians(hsv[0]);
                weight[bk] += w;
                sx[bk] += Math.cos(rad) * w;
                sy[bk] += Math.sin(rad) * w;
                ss[bk] += hsv[1] * w;
                sv[bk] += hsv[2] * w;
            }
        }
        int best = 0;
        for (int i = 1; i < buckets; i++) if (weight[i] > weight[best]) best = i;
        if (weight[best] < n * 0.03) {
            // black-and-white cover: a pure grey seed (no hue at all) so the whole UI stays B&W
            float v = (float) (allV / Math.max(1, n));
            return Color.HSVToColor(new float[]{0f, 0f, Math.max(0.35f, Math.min(0.8f, v))});
        }
        double hue = Math.toDegrees(Math.atan2(sy[best], sx[best]));
        if (hue < 0) hue += 360;
        float sat = (float) Math.max(0.5, Math.min(0.92, ss[best] / weight[best]));
        float val = (float) Math.max(0.55, Math.min(0.92, sv[best] / weight[best]));
        return Color.HSVToColor(new float[]{(float) hue, sat, val});
    }

    /**
     * Colors a switch from the theme that is active right now. The Material switch otherwise kept
     * the baseline lavender while the rest of the screen followed the wallpaper/custom/ambient color.
     */
    public static void tintSwitch(MaterialSwitch sw) {
        int primary = MaterialColors.getColor(sw, com.google.android.material.R.attr.colorPrimary);
        int onPrimary = MaterialColors.getColor(sw, com.google.android.material.R.attr.colorOnPrimary);
        int track = MaterialColors.getColor(sw, com.google.android.material.R.attr.colorSurfaceContainerHighest);
        int outline = MaterialColors.getColor(sw, com.google.android.material.R.attr.colorOutline);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(st, new int[]{onPrimary, outline}));
        sw.setTrackTintList(new ColorStateList(st, new int[]{primary, track}));
        sw.setTrackDecorationTintList(new ColorStateList(st, new int[]{Color.TRANSPARENT, outline}));
    }
}
