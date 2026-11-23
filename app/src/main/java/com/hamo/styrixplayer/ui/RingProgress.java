package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * A thin progress ring, drawn exactly centered in its own bounds (so it always sits concentric
 * with whatever is inside it). Follows the Ambient palette when that is on.
 */
public class RingProgress extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final float stroke;
    private int progress, max = 1000;
    private final AmbientTheme.Listener palette = p -> invalidate();

    public RingProgress(Context c) { this(c, null); }

    public RingProgress(Context c, AttributeSet a) {
        super(c, a);
        stroke = 3f * c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(stroke);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(stroke);
        arc.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setMax(int m) { max = Math.max(1, m); invalidate(); }

    public void setProgress(int p) {
        p = Math.max(0, Math.min(max, p));
        if (p == progress) return;
        progress = p;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AmbientTheme.register(palette);
    }

    @Override
    protected void onDetachedFromWindow() {
        AmbientTheme.unregister(palette);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int accent;
        AmbientTheme.Palette p = AmbientTheme.active(getContext()) ? AmbientTheme.current() : null;
        if (p != null) accent = p.accent;
        else accent = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);
        arc.setColor(accent);
        track.setColor((accent & 0x00FFFFFF) | 0x38000000);
        float half = stroke / 2f;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f - half;
        box.set(cx - r, cy - r, cx + r, cy + r);
        cv.drawCircle(cx, cy, r, track);
        if (progress > 0) cv.drawArc(box, -90f, 360f * progress / max, false, arc);
    }
}
