package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;
import com.hamo.styrixplayer.data.Prefs;
import com.hamo.styrixplayer.playback.SpectrumTap;

/** Rounded bars or a smooth line that dance to the music. Draws only while it is on screen. */
public class VisualizerView extends View {
    private static final long DELAY_NS = 245_000_000L; // the audio is analysed before it reaches the speaker
    private final int n = SpectrumTap.BANDS;
    private final float[] raw = new float[SpectrumTap.BANDS];
    private final float[] shown = new float[SpectrumTap.BANDS];
    private final float[] win = new float[SpectrumTap.BANDS];
    private final Path line = new Path(), fill = new Path();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader fillShader;
    // settings (see reload())
    private int style, lo, hi;
    private float top, range, rise, fall;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float gap, minH;
    private int color;
    private boolean ambient;
    private final AmbientTheme.Listener palette = p -> { color = p.accent; shader = null; fillShader = null; invalidate(); };
    private Shader shader;
    private int shaderH;

    public VisualizerView(Context c) { this(c, null); }

    public VisualizerView(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        gap = 3f * d;
        minH = 3f * d;
        color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(3f * d);
        reload();
    }

    /** Reads the tweak settings. Call again after they change. */
    public void reload() {
        Context c = getContext();
        style = Prefs.getInt(c, Prefs.VIZ_STYLE, 0) == 1 ? 1 : 0;
        int gain = clamp(Prefs.getInt(c, Prefs.VIZ_GAIN, 35));
        int smooth = clamp(Prefs.getInt(c, Prefs.VIZ_SMOOTH, 50));
        lo = clamp(Prefs.getInt(c, Prefs.VIZ_LO, 0));
        hi = clamp(Prefs.getInt(c, Prefs.VIZ_HI, 100));
        if (hi - lo < 10) { hi = Math.min(100, lo + 10); lo = hi - 10; }
        // a level at "top" dB fills the view; "range" dB below that is empty
        top = -6f - (gain - 50) * 0.3f;
        range = 54f;
        rise = 0.9f - smooth * 0.006f;
        fall = 0.30f - smooth * 0.0027f;
        invalidate();
    }

    private static int clamp(int v) { return Math.max(0, Math.min(100, v)); }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AmbientTheme.register(palette);
        AmbientTheme.Palette p = AmbientTheme.active(getContext()) ? AmbientTheme.current() : null;
        if (p != null) color = p.accent;
        shader = null;
        fillShader = null;
        reload();
        refreshActive();
    }

    @Override
    protected void onDetachedFromWindow() {
        AmbientTheme.unregister(palette);
        SpectrumTap.get().setActive(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        refreshActive();
    }

    @Override
    public void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        refreshActive();
    }

    private void refreshActive() {
        boolean on = isShown() && getWindowVisibility() == VISIBLE;
        SpectrumTap.get().setActive(on);
        if (on) postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        boolean fresh = SpectrumTap.get().read(raw, DELAY_NS);
        // pick the part of the spectrum to show and stretch it across the whole width
        float from = lo / 100f * (n - 1), to = hi / 100f * (n - 1);
        boolean any = false;
        for (int i = 0; i < n; i++) {
            float target = 0f;
            if (fresh) {
                float pos = from + (to - from) * i / (n - 1);
                int i0 = (int) pos, i1 = Math.min(n - 1, i0 + 1);
                float db = raw[i0] + (raw[i1] - raw[i0]) * (pos - i0);
                float v = Math.max(0f, Math.min(1f, (db - (top - range)) / range));
                target = (float) Math.pow(v, 1.5);
            }
            // quick to rise, slower to fall: reads as a beat instead of flicker
            float k = target > shown[i] ? rise : fall;
            shown[i] += (target - shown[i]) * k;
            if (shown[i] > 0.01f) any = true;
        }
        if (style == 1) drawLine(cv, w, h, any); else drawBars(cv, w, h);
        // keep animating while sound plays or the bars are still settling; sleep when silent
        if (isShown()) {
            if (fresh || any) postInvalidateOnAnimation();
            else postInvalidateDelayed(100);   // silent: just keep an eye out for the music starting
        }
    }

    private void drawBars(Canvas cv, int w, int h) {
        if (shader == null || shaderH != h) {
            shader = new LinearGradient(0, h, 0, 0, (color & 0x00FFFFFF) | 0x66000000, color, Shader.TileMode.CLAMP);
            shaderH = h;
        }
        paint.setShader(shader);
        float bw = (w - gap * (n - 1)) / n;
        float rad = bw / 2f;
        for (int i = 0; i < n; i++) {
            float bh = shown[i] * h;
            if (bh < minH) {
                if (bh < 0.5f) continue;      // silent: draw nothing instead of a row of dashes
                bh = minH;
            }
            float x = i * (bw + gap);
            r.set(x, h - bh, x + bw, h);
            cv.drawRoundRect(r, rad, rad, paint);
        }
    }

    private void drawLine(Canvas cv, int w, int h, boolean any) {
        if (!any) return;
        if (fillShader == null || shaderH != h) {
            fillShader = new LinearGradient(0, h, 0, 0, (color & 0x00FFFFFF), (color & 0x00FFFFFF) | 0x77000000,
                    Shader.TileMode.CLAMP);
            shaderH = h;
        }
        float pad = stroke.getStrokeWidth();
        float usable = h - pad * 2f;
        float step = w / (float) (n - 1);
        line.rewind();
        float px = 0, py = h - pad - shown[0] * usable;
        line.moveTo(px, py);
        for (int i = 1; i < n; i++) {
            float x = i * step, y = h - pad - shown[i] * usable;
            // curve through the midpoints so the line is smooth with no corners
            line.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            px = x; py = y;
        }
        line.lineTo(px, py);
        fill.set(line);
        fill.lineTo(w, h);
        fill.lineTo(0, h);
        fill.close();
        paint.setShader(fillShader);
        cv.drawPath(fill, paint);
        stroke.setColor(color);
        cv.drawPath(line, stroke);
    }
}
