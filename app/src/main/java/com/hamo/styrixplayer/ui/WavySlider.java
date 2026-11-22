package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;

/**
 * The seek bar in the "expressive" style: the part already played is a travelling wave that rolls
 * while music plays and relaxes to a straight line when it pauses; the rest is a plain line. The
 * normal Slider still does the thumb and the touch handling, its own track is just made invisible.
 */
public class WavySlider extends Slider {

    // No initializers: the super constructor calls the tint setters below before these would run.
    private int activeColor, inactiveColor;
    private boolean waving;
    private float amp;            // 0..1, eases towards 1 while playing
    private long last;
    private Paint paint;
    private Path path;
    private PosSource source;

    /** Where playback is right now; asked every frame so the thumb glides instead of jumping. */
    public interface PosSource {
        /** @return the position in the slider's own units, or a negative number to leave it alone */
        float position();
    }

    public void setPosSource(PosSource s) {
        source = s;
    }

    public WavySlider(@NonNull Context c) {
        this(c, null);
    }

    public WavySlider(@NonNull Context c, @Nullable AttributeSet a) {
        super(c, a);
        init();
    }

    public WavySlider(@NonNull Context c, @Nullable AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        path = new Path();
        if (activeColor == 0) activeColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary);
        if (inactiveColor == 0) inactiveColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant);
        super.setTrackActiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        super.setTrackInactiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        setWillNotDraw(false);
    }

    @Override
    public void setTrackActiveTintList(@NonNull ColorStateList c) {
        activeColor = c.getDefaultColor();
        super.setTrackActiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        invalidate();
    }

    @Override
    public void setTrackInactiveTintList(@NonNull ColorStateList c) {
        inactiveColor = c.getDefaultColor();
        super.setTrackInactiveTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        invalidate();
    }

    /** True while music plays: the wave rolls. False: it settles to a straight line. */
    public void setWaving(boolean on) {
        if (waving == on) return;
        waving = on;
        last = 0;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (paint != null) drawTrack(canvas);
        super.onDraw(canvas);          // thumb and halo (the track tints are transparent)
    }

    private void drawTrack(Canvas canvas) {
        if (source != null && waving) {
            float v = source.position();
            if (v >= 0) {
                v = Math.min(v, getValueTo());
                if (Math.abs(v - getValue()) > 0.5f) super.setValue(Math.max(v, getValueFrom()));
            }
        }
        float d = getResources().getDisplayMetrics().density;
        float track = getTrackWidth();
        float left = (getWidth() - track) / 2f;
        float cy = getHeight() / 2f;
        float range = getValueTo() - getValueFrom();
        float frac = range <= 0 ? 0 : Math.max(0f, Math.min(1f, (getValue() - getValueFrom()) / range));
        float thumbX = left + track * frac;
        float stroke = Math.max(4 * d, getTrackHeight());
        float gap = getThumbRadius() + 2 * d;      // breathing room around the thumb

        long now = System.nanoTime();
        float dt = last == 0 ? 0 : (now - last) / 1e9f;
        last = now;
        float goal = waving && isEnabled() ? 1f : 0f;
        amp += (goal - amp) * Math.min(1f, dt * 6f);
        if (Math.abs(goal - amp) < 0.01f) amp = goal;
        float phase = (now / 1e9f) * 5.5f;         // radians per second of travel

        paint.setStrokeWidth(stroke);

        // the rest of the song: a straight line
        float restFrom = thumbX + gap;
        if (restFrom < left + track) {
            paint.setColor(inactiveColor);
            canvas.drawLine(restFrom, cy, left + track, cy, paint);
        }

        // the part played: the wave
        float end = thumbX - gap;
        if (end > left) {
            paint.setColor(activeColor);
            float a = 3.2f * d * amp;
            float wl = 26 * d;
            path.rewind();
            float step = 2 * d;
            for (float x = left; x <= end + 0.01f; x += step) {
                float xx = Math.min(x, end);
                // the wave fades in over the first stretch so the start sits on the line
                float ramp = Math.min(1f, (xx - left) / (14 * d));
                float y = cy + (float) Math.sin((xx - left) / wl * 2 * Math.PI - phase) * a * ramp;
                if (x == left) path.moveTo(xx, y); else path.lineTo(xx, y);
                if (xx == end) break;
            }
            canvas.drawPath(path, paint);
        }

        if (amp > 0f && amp < 1f || waving) postInvalidateOnAnimation();
    }
}
