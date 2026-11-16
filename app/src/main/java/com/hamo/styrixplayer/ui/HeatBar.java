package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * "Most played part": a small bar graph above the seek bar. Each bar is a slice of the song; the
 * taller (and brighter) it is, the more often you have listened through that slice.
 */
public class HeatBar extends View {

    public static final int SLICES = 48;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    @Nullable private int[] counts;
    private int tint;           // 0 = work it out from the theme

    /** The color of the player right now (also the video's colors), so the bars always match it. */
    public void setTint(int color) {
        if (tint == color) return;
        tint = color;
        invalidate();
    }
    private int max;

    public HeatBar(Context c) {
        this(c, null);
    }

    public HeatBar(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** True once there is something worth drawing (some slice heard more than once). */
    public boolean setData(@Nullable int[] data) {
        counts = data;
        max = 0;
        if (data != null) for (int v : data) max = Math.max(max, v);
        invalidate();
        return max >= 2;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int[] c = counts;
        if (c == null || max < 2) return;
        AmbientTheme.Palette p = AmbientTheme.active(getContext()) ? AmbientTheme.current() : null;
        int color = tint != 0 ? tint : p != null ? p.accent
                : MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);
        float d = getResources().getDisplayMetrics().density;
        float gap = 2 * d;
        float w = (getWidth() - gap * (c.length - 1)) / c.length;
        float h = getHeight();
        for (int i = 0; i < c.length; i++) {
            float f = (float) c[i] / max;
            float bar = Math.max(2 * d, h * (float) Math.pow(f, 0.85));
            paint.setColor(color);
            paint.setAlpha(c[i] == 0 ? 40 : (int) (90 + 165 * f));
            float x = i * (w + gap);
            rect.set(x, h - bar, x + w, h);
            canvas.drawRoundRect(rect, w / 2f, w / 2f, paint);
        }
    }

    // ---------- storage: one comma separated line per song ----------

    private static SharedPreferences store(Context c) {
        return c.getApplicationContext().getSharedPreferences("heat", Context.MODE_PRIVATE);
    }

    static int[] load(Context c, String key) {
        int[] out = new int[SLICES];
        String s = store(c).getString(key, null);
        if (s == null) return out;
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(parts.length, SLICES); i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void save(Context c, String key, int[] counts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < counts.length; i++) sb.append(i == 0 ? "" : ",").append(counts[i]);
        store(c).edit().putString(key, sb.toString()).apply();
    }
}
