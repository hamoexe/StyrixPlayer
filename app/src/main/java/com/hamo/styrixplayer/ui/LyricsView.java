package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.hamo.styrixplayer.lyrics.LrcParser;
import com.hamo.styrixplayer.lyrics.Lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Apple-Music / SpatialFlow style lyrics: big bold lines, the current one sharp and full-size,
 * the rest dimmed, smaller and softly blurred (Android 12+). When the line changes, everything
 * glides up with the lines further down lagging a little (the "cascade"). Drag to browse,
 * tap a line to jump to it, long-press for options.
 *
 * Every line is a TextView positioned by hand; one Choreographer loop drives scroll + style and
 * goes to sleep when nothing is moving.
 */
public class LyricsView extends ViewGroup {

    public interface Callbacks {
        /** Current playback position in ms. */
        long positionMs();

        boolean isPlaying();

        /** Playback speed, used to predict the position between player updates. */
        default float speed() {
            return 1f;
        }

        /** User tapped a line: seek the player here. */
        void onSeek(long positionMs);

        void onLongPress();
    }

    private static final long LEAD_MS = 150;        // show a line a hair early, feels tighter
    private static final long RESUME_MS = 3500;     // after browsing, snap back to the song
    private static final float TEXT_SP = 26f;
    private static final long GAP_MIN_MS = 3000;     // silence at least this long gets the blur + dots
    private static final long GAP_DELAY_MS = 600;    // ...starting this long after the last word
    private static final float BLUR_STEP = 1.5f;   // px per blur level: fewer steps = fewer re-renders

    private static final float UNSUNG_ALPHA = 0.40f;
    private static final int ALL = Integer.MAX_VALUE, NONE = -1;
    private static final float WORD_GROW = 0.06f;   // the word being sung swells by up to 6%
    private static final float SYL_GROW = 0.035f;   // ...and the part (syllable) inside it by 3.5% more

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /**
     * One lyric line. Draws its text in a single pass, with a word-synced karaoke gradient
     * (sung = full color, unsung = dim). No per-letter entrance animation is used.
     */
    private static final class LineView extends View {
        private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final String text;
        private final int padV;
        private final float edge;
        private final int[] sStart, sEnd;       // per word, trailing spaces trimmed (null = no word sync)
        private final int[] gFirst, gLast;      // per syllable: first / last syllable of the whole word it belongs to
        private final float[] wGrow, sGrow;      // swell 0..1 per word (at its first syllable) and per syllable
        private int tgtSyl = -1;                 // syllable being sung now, -1 = none
        private long growNs;
        private boolean growBusy;
        private StaticLayout layout;
        private int layoutW = -1;
        private int baseColor = Color.WHITE;
        private float unsung = 1f;
        private int active = ALL;               // ALL, NONE, or index of the word being sung
        private float prog;
        private LinearGradient grad;
        private int gradA, gradB;
        private final Matrix mx = new Matrix();

        LineView(Context c, String text, LrcParser.Syl[] syl, float textPx, int padV, float edge) {
            super(c);
            this.text = text;
            this.padV = padV;
            this.edge = edge;
            paint.setTextSize(textPx);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            if (syl != null && syl.length > 0 && text.length() > 0) {
                sStart = new int[syl.length];
                sEnd = new int[syl.length];
                gFirst = new int[syl.length];
                gLast = new int[syl.length];
                wGrow = new float[syl.length];
                sGrow = new float[syl.length];
                for (int k = 0; k < syl.length; k++) {
                    int s = Math.max(0, Math.min(text.length() - 1, syl[k].start));
                    int e = Math.max(s, Math.min(text.length(), syl[k].end));
                    while (e > s + 1 && Character.isWhitespace(text.charAt(e - 1))) e--;
                    sStart[k] = s;
                    sEnd[k] = Math.max(s + 1, e);
                }
                int g0 = 0;
                for (int k = 0; k < syl.length; k++) {
                    if (k > 0 && wordBreakBefore(text, sStart[k])) g0 = k;
                    gFirst[k] = g0;
                }
                int g1 = syl.length - 1;
                for (int k = syl.length - 1; k >= 0; k--) {
                    if (k < syl.length - 1 && wordBreakBefore(text, sStart[k + 1])) g1 = k;
                    gLast[k] = g1;
                }
            } else {
                sStart = null;
                sEnd = null;
                gFirst = null;
                gLast = null;
                wGrow = null;
                sGrow = null;
            }
        }

        /** True when a new whole word begins at character {@code at} (a space sits right before it or at it). */
        static boolean wordBreakBefore(String t, int at) {
            return at > 0 && at < t.length()
                    && (Character.isWhitespace(t.charAt(at - 1)) || Character.isWhitespace(t.charAt(at)));
        }

        boolean wordSynced() {
            return sStart != null;
        }

        void setBaseColor(int c) {
            baseColor = c;
            grad = null;
            invalidate();
        }

        void setKaraoke(int a, float p, float u) {
            if (a == active && Math.abs(p - prog) < 0.0005f
                    && Math.abs(u - unsung) < 0.004f) return;
            // (a/p changed or the brightness moved: recompute below)
            active = a;
            prog = p;
            unsung = u;
            tgtSyl = (gFirst != null && a >= 0 && a < gFirst.length) ? a : -1;
            invalidate();
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;   // alpha without an offscreen layer: much cheaper while animating
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            int tw = Math.max(1, w);
            if (layout == null || layoutW != tw) {
                layoutW = tw;
                layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, tw)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                        .setLineSpacing(0f, 1.08f)
                        .setIncludePad(false)
                        .build();
            }
            setMeasuredDimension(w, layout.getHeight() + 2 * padV);
        }


        @Override
        protected void onDraw(Canvas canvas) {
            if (layout == null) return;
            canvas.save();
            canvas.translate(0, padV);
            stepGrow();
            if (sStart == null) {
                solid(canvas, 1f);
            } else if (active == ALL) {
                solid(canvas, 1f);
            } else if (active == NONE) {
                solid(canvas, unsung);
            } else {
                partial(canvas);
            }
            canvas.restore();
            if (growBusy) postInvalidateOnAnimation();
        }


        private void solid(Canvas canvas, float a) {
            paint.setShader(null);
            paint.setColor(withAlpha(baseColor, a));
            swell(canvas, layoutW, 0, layout.getHeight());
        }

        private static float approach(float v, float t, float dt) {
            float tau = t > v ? 0.10f : 0.18f;          // swell up quickly, settle back slowly
            v += (t - v) * (1f - (float) Math.exp(-dt / tau));
            return Math.abs(t - v) < 0.004f ? t : v;
        }

        /** Eases every word / syllable swell toward "on" (being sung) or "off". Smooth, not linear. */
        private void stepGrow() {
            growBusy = false;
            if (gFirst == null) return;
            long now = System.nanoTime();
            float dt = growNs == 0 ? 0f : Math.min(0.05f, (now - growNs) / 1e9f);
            growNs = now;
            final int n = gFirst.length;
            final int tw = tgtSyl >= 0 ? gFirst[tgtSyl] : -1;
            for (int k = 0; k < n; k++) {
                if (gFirst[k] == k) {
                    float t = k == tw ? 1f : 0f;
                    wGrow[k] = approach(wGrow[k], t, dt);
                    if (wGrow[k] != t) growBusy = true;
                }
                float ts = (k == tgtSyl && gLast[k] > gFirst[k]) ? 1f : 0f;
                sGrow[k] = approach(sGrow[k], ts, dt);
                if (sGrow[k] != ts) growBusy = true;
            }
            if (tgtSyl < 0) { growNs = growBusy ? growNs : 0; }
        }

        private static float ease(float v) { return v * v * (3f - 2f * v); }

        /** {left, right, rowTop, rowBottom, baseline} of chars [s,e) when they sit on one row, else null. */
        private float[] box(int s, int e) {
            final int j = layout.getLineForOffset(s);
            if (layout.getLineForOffset(Math.max(s, e - 1)) != j) return null;
            final boolean rtl = layout.getParagraphDirection(j) == Layout.DIR_RIGHT_TO_LEFT;
            float x0 = layout.getPrimaryHorizontal(s);
            float x1 = (e <= text.length() && layout.getLineForOffset(e) == j)
                    ? layout.getPrimaryHorizontal(e) : (rtl ? layout.getLineLeft(j) : layout.getLineRight(j));
            float l = Math.min(x0, x1), r = Math.max(x0, x1);
            if (r - l < 1f) return null;
            return new float[]{l, r, layout.getLineTop(j), layout.getLineBottom(j), layout.getLineBaseline(j)};
        }

        /**
         * Draws the text (paint already set) inside the rows [clipTop, clipBottom]. Words that are
         * being sung are drawn a little larger: the whole word swells, and the syllable being sung
         * swells a bit more inside it. Scaling is about the word's centre on its baseline.
         */
        private void swell(Canvas canvas, int w, int clipTop, int clipBottom) {
            int words = 0;
            if (gFirst != null) {
                for (int k = 0; k < gFirst.length; k++) if (gFirst[k] == k && wGrow[k] > 0.003f) words++;
            }
            if (words == 0) {
                canvas.save();
                canvas.clipRect(0, clipTop, w, clipBottom);
                layout.draw(canvas);
                canvas.restore();
                return;
            }
            final float[][] wb = new float[gFirst.length][];
            canvas.save();
            canvas.clipRect(0, clipTop, w, clipBottom);
            for (int k = 0; k < gFirst.length; k++) {
                if (gFirst[k] != k || wGrow[k] <= 0.003f) continue;
                float[] b = box(sStart[k], sEnd[gLast[k]]);
                if (b == null || b[2] < clipTop || b[3] > clipBottom) continue;
                wb[k] = b;
                canvas.clipOutRect(b[0], b[2], b[1], b[3]);
            }
            layout.draw(canvas);
            canvas.restore();
            for (int k = 0; k < gFirst.length; k++) {
                final float[] b = wb[k];
                if (b == null) continue;
                final float gw = 1f + WORD_GROW * ease(wGrow[k]);
                final float cx = (b[0] + b[1]) / 2f;
                // keep the swollen word inside the line: the first / last word of a row would
                // otherwise grow past the view edge and get its first or last letter cut off
                final float lo = b[0] - (cx - b[0]) * (gw - 1f), hi = b[1] + (b[1] - cx) * (gw - 1f);
                final float dx = lo < 0f ? -lo : (hi > w ? w - hi : 0f);
                canvas.save();
                canvas.translate(dx, 0f);
                canvas.scale(gw, gw, cx, b[4]);
                canvas.clipRect(b[0], b[2], b[1], b[3]);
                for (int q = k; q <= gLast[k]; q++) {
                    if (sGrow[q] <= 0.003f) continue;
                    float[] sb = box(sStart[q], sEnd[q]);
                    if (sb != null) canvas.clipOutRect(sb[0], sb[2], sb[1], sb[3]);
                }
                layout.draw(canvas);
                canvas.restore();
                for (int q = k; q <= gLast[k]; q++) {
                    if (sGrow[q] <= 0.003f) continue;
                    float[] sb = box(sStart[q], sEnd[q]);
                    if (sb == null) continue;
                    final float gs = 1f + SYL_GROW * ease(sGrow[q]);
                    canvas.save();
                    canvas.translate(dx, 0f);
                    canvas.scale(gw, gw, cx, b[4]);
                    canvas.scale(gs, gs, (sb[0] + sb[1]) / 2f, sb[4]);
                    canvas.clipRect(sb[0], sb[2], sb[1], sb[3]);
                    layout.draw(canvas);
                    canvas.restore();
                }
            }
        }

        private void partial(Canvas canvas) {
            final int a = Math.min(active, sStart.length - 1);
            final int s = sStart[a], e = sEnd[a], len = text.length();
            final int j = layout.getLineForOffset(s);
            final boolean rtl = layout.getParagraphDirection(j) == Layout.DIR_RIGHT_TO_LEFT;
            final float xs = layout.getPrimaryHorizontal(s);
            float xe;
            if (e <= len && layout.getLineForOffset(e) == j) {
                xe = layout.getPrimaryHorizontal(e);
            } else {
                xe = rtl ? layout.getLineLeft(j) : layout.getLineRight(j);
            }

            // Keep the word-synced karaoke sweep, but draw every glyph in one pass.
            // No clipping, per-letter alpha, scaling, or entrance animation is applied.
            final float dir = rtl ? -1f : 1f;
            // The front of the sweep must move continuously from word to word. It used to end every
            // word at (end + soft edge) and restart the next one at its start, which is a bit
            // BEHIND that point, so the front jumped back at every word change (the stutter).
            // Now a word ends exactly where the next word on the same row begins.
            float to = xe + dir * edge;
            if (a + 1 < sStart.length && layout.getLineForOffset(sStart[a + 1]) == j) {
                to = layout.getPrimaryHorizontal(sStart[a + 1]);
            }
            final float lead = xs + (to - xs) * prog;
            final int sung = baseColor;
            final int dim = withAlpha(baseColor, unsung);
            if (grad == null || gradA != sung || gradB != dim) {
                gradA = sung;
                gradB = dim;
                grad = new LinearGradient(0, 0, edge, 0, sung, dim, Shader.TileMode.CLAMP);
            }
            if (rtl) {
                mx.setScale(-1f, 1f);
                mx.postTranslate(lead + edge, 0f);
            } else {
                mx.setTranslate(lead - edge, 0f);
            }
            grad.setLocalMatrix(mx);

            final int w = layoutW;
            final int h = layout.getHeight();
            final int top = layout.getLineTop(j);
            final int bottom = layout.getLineBottom(j);

            if (top > 0) {
                canvas.save();
                canvas.clipRect(0, 0, w, top);
                solid(canvas, 1f);
                canvas.restore();
            }

            paint.setColor(Color.BLACK);
            paint.setAlpha(255);
            paint.setShader(grad);
            swell(canvas, w, top, bottom);
            paint.setShader(null);
            paint.setAlpha(255);

            if (bottom < h) {
                canvas.save();
                canvas.clipRect(0, bottom, w, h);
                solid(canvas, unsung);
                canvas.restore();
            }
        }
    }

    private final TextView status;
    private final TextView resume;
    private boolean resumeShown, snapOnLayout;
    private float gapV;                       // 0..1 how much the 'long gap' look is applied
    private float dotsP, dotsTop;
    private boolean dotsRtl;
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<LineView> views = new ArrayList<>();
    private final Object[] blurCache = new Object[40];   // RenderEffect (typed Object: API 31+)

    private List<LrcParser.Line> lines = new ArrayList<>();
    private long[][] eff = new long[0][];    // per line: smoothed {start, duration} per syllable (null = as given)
    private boolean[] rtl = new boolean[0];
    private int[] tops = new int[0];
    private int[] blurLevel = new int[0];
    private float[] ls = new float[0];       // each line's own scroll (cascade)
    private float[] lv = new float[0];       // ...and its velocity (spring)
    private float[] drawnTop = new float[0]; // final non-overlapping on-screen top for each line
    private float[] curA = new float[0], curS = new float[0], curB = new float[0];
    private float[] curU = new float[0];     // brightness of the not-yet-sung part of each line
    private int karaokeIdx = -1;
    // Stabilizes the active line against tiny playback-clock reversals at LRC boundaries.
    private int stableLineIdx = -1;
    private double stableLinePos = Double.NaN;
    private double clockPos, clockT;
    private boolean clockValid;

    private final float density;
    private final int touchSlop;
    private int padH, anchorY, contentH;
    private int textColor = Color.WHITE;

    private boolean ready, running, snap;
    private int laidW = -1, laidH = -1;
    private boolean synced, userMode;
    private long offsetMs;
    private float target;

    // touch
    private boolean touching, moved, longFired;
    private float downY, lastY, flingV;
    private long lastTouchMs;
    @Nullable private VelocityTracker vt;

    @Nullable private Callbacks cb;
    private long lastFrameNanos;

    private final Choreographer.FrameCallback frame = this::onFrame;
    private final Runnable idleTick = () -> {
        running = false;
        kick();
    };
    private final Runnable longPress = () -> {
        if (touching && !moved) {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (cb != null) cb.onLongPress();
        }
    };

    public LyricsView(Context c) {
        this(c, null);
    }

    public LyricsView(Context c, @Nullable AttributeSet attrs) {
        super(c, attrs);
        density = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        padH = dp(4);

        status = new TextView(c);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(0f, 1.15f);
        status.setAlpha(0.7f);
        status.setTextColor(textColor);
        addView(status);

        resume = new TextView(c);
        resume.setText("Resume");
        resume.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        resume.setTypeface(Typeface.DEFAULT_BOLD);
        resume.setTextColor(textColor);
        resume.setGravity(Gravity.CENTER);
        resume.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(24));
        pill.setColor(0xCC1C1B22);
        pill.setStroke(dp(1), 0x44FFFFFF);
        resume.setBackground(pill);
        resume.setZ(dp(6));
        resume.setAlpha(0f);
        resume.setTranslationY(dp(8));
        resume.setVisibility(INVISIBLE);
        resume.setOnClickListener(v -> {
            userMode = false;
            flingV = 0f;
            kick();
        });
        addView(resume);
        setWillNotDraw(false);

        // soft fade at the top and bottom edge
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(dp(44));
        ready = true;
    }

    // ---------- public API ----------

    public void setCallbacks(@Nullable Callbacks callbacks) {
        cb = callbacks;
        kick();
    }

    public void setTextColor(int color) {
        textColor = color;
        status.setTextColor(color);
        resume.setTextColor(color);
        for (LineView v : views) v.setBaseColor(color);
    }

    public long getOffsetMs() {
        return offsetMs;
    }

    public void setOffsetMs(long ms) {
        offsetMs = ms;
        kick();
    }

    public boolean isSynced() {
        return synced;
    }

    public void showLoading() {
        clearLines();
        showStatus("Loading lyrics…");
    }

    public void setLyrics(Lyrics l) {
        clearLines();
        switch (l.state) {
            case SYNCED:
            case PLAIN:
                lines = l.lines;
                synced = l.state == Lyrics.State.SYNCED;
                offsetMs = l.offsetMs;
                buildLines();
                status.setVisibility(GONE);
                break;
            case INSTRUMENTAL:
                showStatus("Instrumental ♪");
                break;
            case ERROR:
                showStatus("Couldn't load lyrics.\n" + (l.message.isEmpty() ? "" : l.message + "\n")
                        + "Hold to try again.");
                break;
            default:
                showStatus("No lyrics found.\nHold for options.");
                break;
        }
        snap = true;
        stableLineIdx = -1;
        stableLinePos = Double.NaN;
        snapOnLayout = true;   // and again once the new lines are measured
        requestLayout();
        kick();
    }

    // ---------- building ----------

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(VISIBLE);
    }

    private void clearLines() {
        stop();
        if (getChildCount() > 2) removeViews(2, getChildCount() - 2);
        showResume(false);
        gapV = 0f;
        views.clear();
        lines = new ArrayList<>();
        synced = false;
        userMode = false;
        target = 0f;
        flingV = 0f;
        offsetMs = 0;
        rtl = new boolean[0];
        tops = new int[0];
        blurLevel = new int[0];
        ls = new float[0];
        lv = new float[0];
        drawnTop = new float[0];
        curA = new float[0];
        curS = new float[0];
        curB = new float[0];
        curU = new float[0];
        eff = new long[0][];
        karaokeIdx = -1;
        clockValid = false;
    }

    private void buildLines() {
        final int n = lines.size();
        rtl = new boolean[n];
        tops = new int[n];
        blurLevel = new int[n];
        ls = new float[n];
        lv = new float[n];
        drawnTop = new float[n];
        curA = new float[n];
        curS = new float[n];
        curB = new float[n];
        curU = new float[n];
        eff = new long[n][];
        final float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                getResources().getDisplayMetrics());
        for (int i = 0; i < n; i++) {
            String text = lines.get(i).text;
            LineView tv = new LineView(getContext(), text.isEmpty() ? "♪" : text,
                    text.isEmpty() ? null : lines.get(i).syl, px, dp(9), dp(16));
            tv.setBaseColor(textColor);
            if (!text.isEmpty()) eff[i] = smoothTimes(text, lines.get(i).syl);
            rtl[i] = startsRtl(text);
            curU[i] = 1f;
            addView(tv);
            views.add(tv);
        }
    }

    /**
     * Makes the word sweep look right on real-world word timings:
     * 1. Parts of a word that are only a few milliseconds long (Flow 25 ms + ers 2.9 s) are spread out
     *    half by their given length and half by their letter count, so the sweep doesn't zip.
     * 2. A held word (a 3 second "er") no longer crawls across its letters the whole time: it
     *    sweeps in a time that fits its length, then waits, and the next word carries on from there.
     * 3. A last word with a tiny duration still gets a short, visible sweep.
     * Returns {start0, dur0, start1, dur1, ...}, or null when nothing needs changing.
     */
    @Nullable
    private static long[] smoothTimes(String text, LrcParser.Syl[] syl) {
        if (syl == null || syl.length < 1) return null;
        final int n = syl.length;
        long[] out = new long[n * 2];
        int[] len = new int[n];
        for (int k = 0; k < n; k++) {
            out[2 * k] = syl[k].timeMs;
            out[2 * k + 1] = syl[k].durMs;
            int a = Math.max(0, syl[k].start), b = Math.min(text.length(), syl[k].end);
            while (b > a + 1 && Character.isWhitespace(text.charAt(b - 1))) b--;
            len[k] = Math.max(1, b - a);
        }
        boolean changed = false;
        int g0 = 0;
        for (int k = 1; k <= n; k++) {
            if (k < n && !LineView.wordBreakBefore(text, Math.max(0, Math.min(text.length() - 1, syl[k].start)))) continue;
            if (k - g0 > 1) {
                long t0 = syl[g0].timeMs, t1 = syl[k - 1].timeMs + syl[k - 1].durMs;
                long total = Math.max(1, t1 - t0);
                int letters = 0;
                boolean tiny = false;
                for (int q = g0; q < k; q++) {
                    letters += len[q];
                    if (syl[q].durMs < 120 || syl[q].durMs < total * 0.12) tiny = true;
                }
                if (tiny) {
                    changed = true;
                    double cum = 0;
                    for (int q = g0; q < k; q++) {
                        double w = 0.5 * syl[q].durMs / total + 0.5 * len[q] / (double) letters;
                        out[2 * q] = t0 + Math.round(cum * total);
                        out[2 * q + 1] = Math.max(1, Math.round(w * total));
                        cum += w;
                    }
                }
            }
            g0 = k;
        }
        for (int q = 0; q < n; q++) {
            long cap = Math.max(450L, 260L * len[q]);
            if (out[2 * q + 1] > cap) { out[2 * q + 1] = cap; changed = true; }
            if (q == n - 1 && out[2 * q + 1] < 300L) { out[2 * q + 1] = 300L; changed = true; }
        }
        return changed ? out : null;
    }

    private static boolean startsRtl(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.isLetter(cp)) {
                byte d = Character.getDirectionality(cp);
                return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                        || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    // ---------- layout ----------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        // Only take an exact height (the parent re-measures match_parent children that way);
        // otherwise report 0 so this view never makes its parent taller than the artwork.
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightSpec) : 0;
        setMeasuredDimension(w, h);

        anchorY = (int) (h * 0.28f);
        int textW = Math.max(0, w - 2 * padH);
        int y = anchorY;
        for (int i = 0; i < views.size(); i++) {
            LineView v = views.get(i);
            v.measure(MeasureSpec.makeMeasureSpec(textW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            tops[i] = y;
            y += v.getMeasuredHeight();
        }
        contentH = y + (int) (h * 0.72f);
        status.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w - dp(32)), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        resume.measure(MeasureSpec.makeMeasureSpec(Math.max(0, w - dp(32)), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int sw = status.getMeasuredWidth(), sh = status.getMeasuredHeight();
        status.layout((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
        int rw = resume.getMeasuredWidth(), rh = resume.getMeasuredHeight();
        resume.layout((w - rw) / 2, h - rh - dp(14), (w + rw) / 2, h - dp(14));
        for (int i = 0; i < views.size(); i++) {
            LineView v = views.get(i);
            int vw = v.getMeasuredWidth(), vh = v.getMeasuredHeight();
            v.layout(padH, tops[i], padH + vw, tops[i] + vh);
            v.setPivotX(rtl[i] ? vw : 0f);
            v.setPivotY(vh / 2f);
        }
        // Only jump (no animation) when the size really changed. Layout passes also happen
        // whenever a sibling changes (e.g. the elapsed-time label each second), and snapping
        // then made the whole lyric stack leap mid-scroll.
        if (w != laidW || h != laidH || snapOnLayout) {
            laidW = w;
            laidH = h;
            snapOnLayout = false;
            snap = true;
        }
        kick();
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return 1f;
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return 1f;
    }

    // ---------- animation loop ----------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        snap = true;   // the song kept playing while we were away: don't animate from a stale state
        kick();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (!ready) return;   // can fire inside the View constructor
        if (visibility == VISIBLE) {
            snap = true;
            kick();
        } else {
            stop();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (!ready) return;
        if (visibility == VISIBLE) {
            snap = true;
            kick();
        } else {
            stop();
        }
    }

    /** Wake the loop (cheap if it's already running). Call after seeks / play-state changes. */
    public void kick() {
        if (!ready || running || lines.isEmpty() || cb == null) return;
        if (!isAttachedToWindow() || getVisibility() != VISIBLE) return;
        removeCallbacks(idleTick);
        running = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
        removeCallbacks(idleTick);
    }

    private void onFrame(long nanos) {
        if (!running) return;
        float dt = lastFrameNanos == 0 ? 16f : Math.min(64f, (nanos - lastFrameNanos) / 1_000_000f);
        lastFrameNanos = nanos;
        boolean animating = update(Math.max(1f, dt), nanos / 1_000_000.0);
        if (animating) {
            Choreographer.getInstance().postFrameCallback(frame);
        } else {
            running = false;
            // nothing is moving: just check the clock ~11x a second while music plays
            if (synced && cb != null && cb.isPlaying()) postDelayed(idleTick, 90);
        }
    }

    /** One step. Returns true while something is still moving. */
    private boolean update(float dt, double frameMs) {
        final int n = lines.size();
        final int h = getHeight();
        final Callbacks c = cb;
        if (n == 0 || h == 0 || c == null) return false;
        if (tops.length != n || tops[0] == 0) return false;   // new lines not measured yet: keep snap pending

        boolean animating = false;
        final long now = SystemClock.uptimeMillis();
        final float maxScroll = Math.max(0, contentH - h);

        final double pos = synced ? clock(c, frameMs) : 0;
        int idx = -1;
        if (synced) {
            idx = LrcParser.indexAt(lines, (long) pos + LEAD_MS - offsetMs);
            if (!Double.isNaN(stableLinePos)) {
                final double delta = pos - stableLinePos;
                // Permit deliberate backward seeks, but ignore brief clock jitter that would
                // otherwise alternate the active line (most visible on the second lyric row).
                if (delta < -260d) {
                    stableLineIdx = -1;
                } else if (idx >= 0 && stableLineIdx >= 0 && idx < stableLineIdx
                        && delta > -120d) {
                    idx = stableLineIdx;
                }
            }
            if (idx >= 0) stableLineIdx = idx;
            stableLinePos = pos;
        }

        if (userMode && !touching && Math.abs(flingV) < 20f && now - lastTouchMs > RESUME_MS) {
            userMode = false;
        }
        final boolean free = !synced || userMode;

        // ----- scroll -----
        if (free) {
            if (!touching && Math.abs(flingV) >= 20f) {
                target += flingV * dt / 1000f;
                flingV *= (float) Math.exp(-dt / 380f);
                animating = true;
            } else if (!touching) {
                flingV = 0f;
            }
            float clamped = Math.max(0f, Math.min(maxScroll, target));
            if (clamped != target) flingV = 0f;
            target = clamped;
            for (int i = 0; i < n; i++) {
                ls[i] = target;
                lv[i] = 0f;
            }
            if (touching || userMode) animating = true;
        } else {
            float want = idx < 0 ? 0f : tops[idx] - anchorY;
            target = Math.max(0f, Math.min(maxScroll, want));
            final float limit = 2.5f * h;
            for (int i = 0; i < n; i++) {
                if (snap) {
                    ls[i] = target;
                    lv[i] = 0f;
                    continue;
                }
                float diff = target - ls[i];
                if (diff > limit) {          // far jump: don't scroll through the whole song
                    ls[i] = target - limit;
                    lv[i] = 0f;
                    diff = limit;
                } else if (diff < -limit) {
                    ls[i] = target + limit;
                    lv[i] = 0f;
                    diff = -limit;
                }
                if (Math.abs(diff) > 0.3f || Math.abs(lv[i]) > 8f) {
                    // Critically damped spring: eases in and settles softly instead of starting
                    // at full speed. Lines further below the current one follow a little later.
                    float tau = 100f + 18f * Math.max(0, Math.min(6, i - idx));
                    float w = 1000f / tau;
                    int steps = Math.max(1, (int) Math.ceil(dt / 8f));
                    float hs = dt / 1000f / steps;
                    for (int st = 0; st < steps; st++) {
                        float acc = w * w * (target - ls[i]) - 2f * w * lv[i];
                        lv[i] += acc * hs;
                        ls[i] += lv[i] * hs;
                    }
                    animating = true;
                } else {
                    ls[i] = target;
                    lv[i] = 0f;
                }
            }
        }

        showResume(synced && userMode);

        // ----- long silence between lines: blur everything + a little countdown -----
        boolean gap = false;
        float gapProg = 0f;
        if (synced && !userMode && idx + 1 < n) {
            final double kp = pos - offsetMs;
            final long gs = idx < 0 ? 0 : lineEnd(idx);
            final long ge = lines.get(idx + 1).timeMs;
            final long from = gs + GAP_DELAY_MS, to = ge - 400;
            if (ge - gs >= GAP_MIN_MS && kp >= from && kp < to) {
                gap = true;
                gapProg = (float) ((kp - from) / Math.max(1.0, (double) (to - from)));
            }
        }
        final float tGap = gap ? 1f : 0f;
        gapV += (tGap - gapV) * (snap ? 1f : 1f - (float) Math.exp(-dt / 220f));
        if (Math.abs(tGap - gapV) > 0.01f) animating = true;
        if (gap && c.isPlaying()) animating = true;
        if (gapV > 0.01f && idx + 1 < n) {
            dotsP = gapProg;
        }

        // ----- look of each line -----
        final float k = snap ? 1f : 1f - (float) Math.exp(-dt / 130f);
        final float blurStep = dp(0.45f);
        final float margin = dp(90);
        float previousVisualBottom = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            final LineView v = views.get(i);
            final float rawTop = tops[i] - ls[i];
            float tA, tS, tB;
            if (!synced) {
                tA = 0.95f;
                tS = 1f;
                tB = 0f;
            } else if (userMode) {            // browsing: everything readable
                boolean cur = i == idx;
                tA = cur ? 1f : 0.8f;
                tS = cur ? 1f : 0.96f;
                tB = 0f;
            } else {
                int d = i - idx;
                if (d == 0) {
                    tA = 1f;
                    tS = 1f;
                    tB = 0f;
                } else if (d > 0) {
                    tA = Math.max(0.22f, 0.55f - 0.09f * (d - 1));
                    tS = 0.92f;
                    tB = Math.min(d, 5) * blurStep;
                } else {
                    // Completed lines trail above the active lyric, then gently disappear as
                    // they travel farther upward. Use their live scroll position (not only the
                    // line index), so the fade follows the same spring/cascade motion.
                    final float distanceAbove = anchorY - (rawTop + views.get(i).getHeight() * 0.5f);
                    final float fadeStart = dp(55f);
                    final float fadeEnd = dp(185f);
                    final float fadeT = Math.max(0f, Math.min(1f,
                            (distanceAbove - fadeStart) / Math.max(1f, fadeEnd - fadeStart)));
                    final float fadeSmooth = fadeT * fadeT * (3f - 2f * fadeT);
                    tA = 0.30f * (1f - fadeSmooth);
                    tS = 0.92f;
                    tB = Math.min(-d, 4) * blurStep;
                }
            }
            if (gapV > 0.001f) {              // long gap: soften and blur everything
                tA *= 1f - 0.35f * gapV;
                tB = Math.max(tB, gapV * 4f * blurStep);
            }
            final boolean rawOff = rawTop > h + margin || rawTop + v.getHeight() < -margin;
            final float kk = rawOff ? 1f : k;   // off-screen lines just jump to their target
            curA[i] += (tA - curA[i]) * kk;
            curS[i] += (tS - curS[i]) * kk;
            curB[i] += (tB - curB[i]) * kk;
            if (!rawOff && (Math.abs(tA - curA[i]) > 0.004f || Math.abs(tS - curS[i]) > 0.002f
                    || Math.abs(tB - curB[i]) > 0.05f)) {
                animating = true;
            }

            // word sweep: the sung part is full color, the rest sits at curU
            if (v.wordSynced()) {
                // A line is also "current" while its last words are still being sung after the next
                // line has started (overlapping lines): it keeps sweeping instead of snapping to full.
                final boolean cur = synced && (i == idx
                        || (i < idx && i >= idx - 2 && pos - offsetMs < lineEnd(i)));
                final float tU = cur ? UNSUNG_ALPHA : 1f;
                curU[i] += (tU - curU[i]) * kk;
                if (Math.abs(tU - curU[i]) > 0.004f) animating = true;
                int a = i < idx ? ALL : NONE;
                float p = 0f;
                if (cur) {
                    LrcParser.Syl[] syl = lines.get(i).syl;
                    double kpos = pos - offsetMs;
                    final long[] et = i < eff.length ? eff[i] : null;
                    a = NONE;
                    for (int w = 0; w < syl.length; w++) {
                        long st = et != null ? et[2 * w] : syl[w].timeMs;
                        if (kpos >= st) a = w;
                        else break;
                    }
                    if (a >= 0) {
                        long st = et != null ? et[2 * a] : syl[a].timeMs;
                        long du = et != null ? et[2 * a + 1] : syl[a].durMs;
                        p = (float) ((kpos - st) / Math.max(1, du));
                        p = Math.max(0f, Math.min(1f, p));
                        if (a == syl.length - 1 && p >= 1f) a = ALL;
                    }
                    if (c.isPlaying()) animating = true;   // keep the karaoke sweep moving at frame rate
                }
                v.setKaraoke(a, p, curU[i]);
            }

            // Constrain the actual transformed view bounds, not just the spring offsets. This
            // handles wrapped lyrics with different measured heights and prevents any two rows
            // from drawing over one another, while leaving ls/lv and all animation curves intact.
            final float scaleY = Math.max(0.01f, Math.min(1f, curS[i]));
            final float halfHeight = v.getHeight() * 0.5f;
            final float scaledTopInset = halfHeight * (1f - scaleY);
            float finalTop = rawTop;
            if (previousVisualBottom != Float.NEGATIVE_INFINITY) {
                finalTop = Math.max(finalTop, previousVisualBottom - scaledTopInset);
            }
            drawnTop[i] = finalTop;
            previousVisualBottom = finalTop + halfHeight * (1f + scaleY);
            final boolean off = finalTop > h + margin || finalTop + v.getHeight() < -margin;

            // Off-screen lines must still get their transform. Skipping them left lines that were
            // never on screen (e.g. the first lines when the song is already at 0:51) sitting at
            // their untransformed layout position, full size and full alpha, drawn right on top of
            // the real lines.
            v.setAlpha(curA[i]);
            v.setScaleX(curS[i]);
            v.setScaleY(curS[i]);
            v.setTranslationY(finalTop - tops[i]);
            if (!off) applyBlur(i, v, curB[i]);
        }
        if (gapV > 0.001f && idx + 1 < n) {
            dotsTop = drawnTop[idx + 1];
            dotsRtl = rtl[idx + 1];
            invalidate();
        }
        snap = false;
        karaokeIdx = idx;
        return animating;
    }

    /**
     * Playback position predicted from the frame clock. The player only reports its position in
     * small jumps; following those directly makes the word sweep stutter, so we extrapolate
     * smoothly and nudge toward the reported value (or jump if we're clearly off, e.g. a seek).
     */
    private double clock(Callbacks c, double frameMs) {
        final double raw = c.positionMs();
        if (!c.isPlaying()) {
            clockValid = false;
            return raw;
        }
        double pred = raw;
        if (clockValid && frameMs - clockT < 250) {
            pred = clockPos + (frameMs - clockT) * c.speed();
            double err = raw - pred;
            pred = Math.abs(err) > 150 ? raw : pred + err * 0.08;
        }
        clockPos = pred;
        clockT = frameMs;
        clockValid = true;
        return pred;
    }

    /** When the last word of line i is over (or a guess for lines without word timing). */
    private long lineEnd(int i) {
        LrcParser.Line l = lines.get(i);
        if (l.text.isEmpty()) return l.timeMs;
        if (l.syl != null && l.syl.length > 0) {
            LrcParser.Syl last = l.syl[l.syl.length - 1];
            return last.timeMs + last.durMs;
        }
        return l.timeMs + Math.max(2500L, l.text.length() * 90L);
    }

    private void showResume(boolean show) {
        if (show == resumeShown) return;
        resumeShown = show;
        resume.animate().cancel();
        if (show) {
            resume.setVisibility(VISIBLE);
            resume.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            resume.animate().alpha(0f).translationY(dp(8)).setDuration(160)
                    .withEndAction(() -> {
                        if (!resumeShown) resume.setVisibility(INVISIBLE);
                    }).start();
        }
    }

    /** Three dots above the upcoming line that fill up as the next line gets closer. */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (gapV < 0.02f) return;
        final float r = dp(3.2f), gapPx = dp(15);
        final float breathe = 1f + 0.07f * (float) Math.sin(SystemClock.uptimeMillis() / 330.0);
        final int w = getWidth();
        for (int k = 0; k < 3; k++) {
            float f = Math.max(0f, Math.min(1f, dotsP * 3f - k));
            float cx = dotsRtl ? w - padH - r - k * gapPx : padH + r + k * gapPx;
            dotPaint.setColor(withAlpha(textColor, gapV * (0.28f + 0.72f * f)));
            canvas.drawCircle(cx, dotsTop, r * breathe * (1f + 0.25f * f), dotPaint);
        }
    }

    private static int withAlpha(int color, float a) {
        return Color.argb(Math.round(Color.alpha(color) * a), Color.red(color),
                Color.green(color), Color.blue(color));
    }

    private void applyBlur(int i, View v, float px) {
        if (Build.VERSION.SDK_INT < 31) return;
        int level = Math.min(blurCache.length - 1, Math.round(px / BLUR_STEP));
        if (level == blurLevel[i]) return;
        blurLevel[i] = level;
        setBlurLevel(v, level);
    }

    @RequiresApi(31)
    private void setBlurLevel(View v, int level) {
        if (level == 0) {
            v.setRenderEffect(null);
            return;
        }
        Object e = blurCache[level];
        if (e == null) {
            float r = level * BLUR_STEP;
            e = RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL);
            blurCache[level] = e;
        }
        v.setRenderEffect((RenderEffect) e);
    }

    // ---------- touch ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                moved = false;
                longFired = false;
                downY = lastY = e.getY();
                flingV = 0f;
                lastTouchMs = SystemClock.uptimeMillis();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                postDelayed(longPress, 550);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (vt != null) vt.addMovement(e);
                if (!moved) {
                    if (Math.abs(e.getY() - downY) > touchSlop) {
                        moved = true;
                        removeCallbacks(longPress);
                        userMode = true;
                        lastY = e.getY();
                        kick();
                    }
                } else {
                    target += lastY - e.getY();
                    lastY = e.getY();
                    lastTouchMs = SystemClock.uptimeMillis();
                    kick();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                touching = false;
                lastTouchMs = SystemClock.uptimeMillis();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (moved && vt != null) {
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000);
                        flingV = -vt.getYVelocity();
                    } else if (!moved && !longFired) {
                        handleTap(e.getY());
                    }
                }
                if (vt != null) {
                    vt.recycle();
                    vt = null;
                }
                kick();
                return true;

            default:
                return true;
        }
    }

    private void handleTap(float y) {
        final Callbacks c = cb;
        if (c == null || !synced) return;
        for (int i = 0; i < views.size(); i++) {
            LineView v = views.get(i);
            float scaleY = Math.max(0.01f, Math.min(1f, curS[i]));
            float top = drawnTop[i] + v.getHeight() * (1f - scaleY) * 0.5f;
            float bottom = top + v.getHeight() * scaleY;
            if (y >= top && y <= bottom) {
                userMode = false;
                c.onSeek(Math.max(0, lines.get(i).timeMs + offsetMs));
                kick();
                return;
            }
        }
    }
}
