package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * A round, wavy progress ring (the "expressive" look) that sits around the download button: the
 * wave rolls round the circle, fills as the percentage grows, spins while it is still waiting, and
 * fades away by itself when the download is over.
 */
public class DlRing extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int active = 0xFF6750A4, track = 0x33FFFFFF;
    private int percent = -1;            // -1: indeterminate (spins)
    private boolean show;
    private float visible;               // 0..1, eases to the goal
    private float shown;                 // the percent being drawn (eased)
    private long last;

    public DlRing(Context c) {
        this(c, null);
    }

    public DlRing(Context c, @Nullable AttributeSet a) {
        super(c, a);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setColors(int activeColor, int trackColor) {
        active = activeColor;
        track = trackColor;
        invalidate();
    }

    /** @param pct 0..100, or -1 when the progress is not known yet */
    public void setState(boolean visibleNow, int pct) {
        if (visibleNow && !show) {
            shown = Math.max(pct, 0);
            last = 0;
        }
        show = visibleNow;
        percent = pct;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = last == 0 ? 0.016f : (now - last) / 1e9f;
        last = now;
        float goal = show ? 1f : 0f;
        visible += (goal - visible) * Math.min(1f, dt * 8f);
        if (Math.abs(goal - visible) < 0.01f) visible = goal;
        if (percent >= 0) shown += (percent - shown) * Math.min(1f, dt * 7f);
        if (visible <= 0f) {
            last = 0;
            if (show) postInvalidateOnAnimation();   // about to fade in
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        float stroke = 3f * d;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - stroke * 2f;
        float t = now / 1e9f;

        paint.setStrokeWidth(stroke);
        paint.setColor(track);
        paint.setAlpha((int) (Color_a(track) * visible));
        canvas.drawCircle(cx, cy, r, paint);

        float sweep = percent < 0 ? 100f : Math.max(shown, 2f);          // degrees = percent * 3.6
        float startDeg = percent < 0 ? (t * 220f) % 360f - 90f : -90f;
        float sweepDeg = sweep * 3.6f;
        if (percent < 0) sweepDeg = 120f;
        float amp = 1.5f * d;
        int waves = 9;
        path.rewind();
        int steps = Math.max(8, (int) (sweepDeg / 3f));
        for (int i = 0; i <= steps; i++) {
            float deg = startDeg + sweepDeg * i / steps;
            double rad = Math.toRadians(deg);
            float rr = r + (float) Math.sin(Math.toRadians(deg - startDeg) * waves - t * 6f) * amp;
            float x = cx + (float) Math.cos(rad) * rr;
            float y = cy + (float) Math.sin(rad) * rr;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        paint.setColor(active);
        paint.setAlpha((int) (255 * visible));
        canvas.drawPath(path, paint);

        postInvalidateOnAnimation();
    }

    private static int Color_a(int c) {
        return (c >>> 24) & 0xFF;
    }
}
