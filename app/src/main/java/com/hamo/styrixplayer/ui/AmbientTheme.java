package com.hamo.styrixplayer.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.BaseProgressIndicator;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Prefs;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Live "ambient" colors for the whole app. The screens keep their normal theme; every color that
 * came from a theme role (primary, surface, containers, ...) is swapped for the matching color of a
 * palette built from the cover, and blended smoothly when the cover changes. Nothing is rebuilt.
 */
public final class AmbientTheme {

    // theme roles that get replaced (order matters: see rolesFor)
    private static final int[] ATTRS = {
            com.google.android.material.R.attr.colorPrimary,
            com.google.android.material.R.attr.colorOnPrimary,
            com.google.android.material.R.attr.colorPrimaryContainer,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            com.google.android.material.R.attr.colorSecondary,
            com.google.android.material.R.attr.colorSecondaryContainer,
            com.google.android.material.R.attr.colorOnSecondaryContainer,
            com.google.android.material.R.attr.colorTertiary,
            com.google.android.material.R.attr.colorSurface,
            com.google.android.material.R.attr.colorSurfaceContainerLowest,
            com.google.android.material.R.attr.colorSurfaceContainerLow,
            com.google.android.material.R.attr.colorSurfaceContainer,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
            com.google.android.material.R.attr.colorSurfaceVariant,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            com.google.android.material.R.attr.colorOutline,
            com.google.android.material.R.attr.colorOutlineVariant,
            // more roles, so text and containers that use them follow the cover live too
            com.google.android.material.R.attr.colorOnSurface,
            com.google.android.material.R.attr.colorOnBackground,
            com.google.android.material.R.attr.colorSurfaceBright,
            com.google.android.material.R.attr.colorSurfaceDim,
            com.google.android.material.R.attr.colorOnSecondary,
            com.google.android.material.R.attr.colorTertiaryContainer,
            com.google.android.material.R.attr.colorOnTertiaryContainer,
            com.google.android.material.R.attr.colorOnTertiary};
    private static final int PRIMARY = 0, PRIMARY_CONTAINER = 2, SURFACE = 8;

    /** What the player (and anything else that paints itself) needs from the current palette. */
    public static final class Palette {
        public final int bg, soft, accent;
        /** Everything the player buttons need, so they are colored directly and never depend on matching. */
        public final int onAccent, accentContainer, onAccentContainer, tonal, onTonal,
                panel, high, variant, onVariant, text;
        /** Fill of the round transport buttons and Queue/Sleep: a clearly colored, visible tone of the cover hue. */
        public final int control;

        Palette(int[] roles) {
            bg = roles[SURFACE];
            soft = roles[PRIMARY_CONTAINER];
            accent = roles[PRIMARY];
            onAccent = roles[1];
            accentContainer = rich(roles[PRIMARY_CONTAINER]);
            onAccentContainer = roles[3];
            onTonal = roles[6];
            panel = roles[11];
            high = roles[12];
            onVariant = roles[15];
            text = roles[3];
            float[] a = new float[3], sf = new float[3];
            Color.colorToHSV(roles[PRIMARY], a);
            Color.colorToHSV(roles[SURFACE], sf);
            if (sf[2] < 0.5f) {
                // dark: mid-value, clearly saturated tones (dark+low-sat read as grey and vanish);
                // grey (B&W) palettes keep their own tiny saturation
                float cs = a[1] < 0.06f ? a[1] : Math.min(0.62f, a[1] * 0.9f);
                control = hsv(a[0], cs, 0.46f);
                tonal = control;
                variant = hsv(a[0], cs * 0.5f, 0.56f); // slider / switch tracks
            } else {
                control = rich(roles[5]);
                tonal = control;
                variant = rich(roles[14]);
            }
        }
    }

    /**
     * Player controls sit on a saturated blurred-cover background. The theme's dark container tones
     * are low in saturation (they read as grey next to it), so the controls get a richer version of
     * the same hue. Grey (B&W) palettes stay grey: the boost scales with the saturation they have.
     */
    static int rich(int c) {
        float[] y = new float[3];
        Color.colorToHSV(c, y);
        boolean dark = y[2] < 0.5f;
        float sat = y[1] * 1.6f + (y[1] > 0.05f ? 0.06f : 0f);
        y[1] = Math.min(dark ? 0.82f : 0.55f, sat);
        if (dark) y[2] = Math.min(1f, y[2] + 0.04f);
        return Color.HSVToColor(y);
    }

    public interface Listener {
        void onPalette(Palette p);
    }

    private static final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private static final List<WeakReference<Activity>> screens = new ArrayList<>();
    @Nullable private static int[] base, cur, prev;
    /** Every color this palette has ever shown (or the plain theme has) -> its role, so stale colors still map. */
    /** Colors that stay known: the plain theme, every screen's own theme colors, finished palettes. */
    private static final java.util.HashMap<Integer, Integer> roleOf = new java.util.HashMap<>();
    private static final java.util.ArrayList<int[]> stable = new java.util.ArrayList<>();
    private static final java.util.ArrayList<int[]> keepSets = new java.util.ArrayList<>();
    /** Colors of the palette frames of the blend that is running; dropped when it finishes. */
    private static final java.util.HashMap<Integer, Integer> frameOf = new java.util.HashMap<>();
    private static boolean baseNight;
    @Nullable private static ValueAnimator anim;

    private AmbientTheme() {}

    public static boolean active(Context c) {
        return AppTheme.mode(c) == AppTheme.MODE_AMBIENT;
    }

    public static void register(Listener l) { listeners.add(l); }

    public static void unregister(Listener l) { listeners.remove(l); }

    /** The palette on screen right now, or null when ambient is off or not started yet. */
    @Nullable
    public static Palette current() {
        return cur == null ? null : new Palette(cur);
    }

    /** Maps a color that came from the normal theme to its ambient counterpart. */
    public static int map(int c) {
        if (cur == null || base == null) return c;
        Integer role = frameOf.get(c);
        if (role == null) role = roleOf.get(c);
        return role == null ? c : cur[role];
    }

    // ---------- palette ----------

    private static boolean night(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int hsv(float h, float s, float v) {
        return Color.HSVToColor(new float[]{((h % 360f) + 360f) % 360f,
                Math.max(0f, Math.min(1f, s)), Math.max(0f, Math.min(1f, v))});
    }

    private static int storedSeed(Context c) {
        int s = Prefs.getInt(c, Prefs.AMBIENT_SEED, 0);
        return s != 0 ? s : AppTheme.customColor(c);
    }

    /** A grey (black-and-white cover) seed: the palette keeps no hue, so the UI stays B&W. */
    private static boolean isGrey(int seed) {
        float[] x = new float[3];
        Color.colorToHSV(seed, x);
        return x[1] < 0.1f;
    }

    private static int[] rolesFor(Context c, int seed) {
        return rolesFor(night(c), seed);
    }

    private static int[] rolesFor(boolean night, int seed) {
        int[] r = rolesForHue(night, seed);
        if (isGrey(seed)) {
            float[] y = new float[3];
            for (int i = 0; i < r.length; i++) {
                Color.colorToHSV(r[i], y);
                y[1] = Math.min(y[1], 0.015f);
                r[i] = Color.HSVToColor(y);
            }
        }
        return r;
    }

    private static int[] rolesForHue(boolean night, int seed) {
        float[] x = new float[3];
        Color.colorToHSV(seed, x);
        float h = x[0], s = x[1];
        float t = Math.max(0.22f, Math.min(0.5f, s * 0.55f));
        float acc = Math.max(0.35f, Math.min(0.7f, s));
        if (night) {
            return new int[]{
                    hsv(h, acc, 0.96f), hsv(h, 0.8f, 0.18f), hsv(h, 0.5f, 0.36f), hsv(h, 0.15f, 0.93f),
                    hsv(h, 0.25f, 0.82f), hsv(h, 0.3f, 0.30f), hsv(h, 0.12f, 0.92f), hsv(h + 40, 0.45f, 0.88f),
                    hsv(h, t * 0.85f, 0.09f), hsv(h, t * 0.85f, 0.07f), hsv(h, t, 0.15f),
                    hsv(h, t, 0.19f), hsv(h, t, 0.23f), hsv(h, t, 0.27f),
                    hsv(h, t * 0.8f, 0.26f), hsv(h, 0.12f, 0.80f), hsv(h, 0.15f, 0.55f), hsv(h, 0.2f, 0.30f),
                    hsv(h, 0.06f, 0.93f), hsv(h, 0.06f, 0.93f), hsv(h, t, 0.27f), hsv(h, t * 0.85f, 0.07f),
                    hsv(h, 0.5f, 0.18f), hsv(h + 40, 0.4f, 0.34f), hsv(h + 40, 0.15f, 0.92f), hsv(h + 40, 0.6f, 0.16f)};
        }
        float lt = t * 0.45f;
        return new int[]{
                hsv(h, Math.max(0.6f, s), 0.50f), hsv(h, 0.05f, 1f), hsv(h, 0.28f, 0.90f), hsv(h, 0.9f, 0.20f),
                hsv(h, 0.35f, 0.45f), hsv(h, 0.2f, 0.88f), hsv(h, 0.6f, 0.20f), hsv(h + 40, 0.6f, 0.50f),
                hsv(h, lt * 0.5f, 0.985f), hsv(h, 0f, 1f), hsv(h, lt, 0.96f),
                hsv(h, lt, 0.94f), hsv(h, lt, 0.92f), hsv(h, lt, 0.90f),
                hsv(h, lt, 0.90f), hsv(h, 0.2f, 0.35f), hsv(h, 0.15f, 0.50f), hsv(h, 0.1f, 0.80f),
                hsv(h, 0.25f, 0.12f), hsv(h, 0.25f, 0.12f), hsv(h, lt * 0.5f, 0.985f), hsv(h, lt, 0.88f),
                hsv(h, 0.05f, 1f), hsv(h + 40, 0.25f, 0.90f), hsv(h + 40, 0.8f, 0.20f), hsv(h + 40, 0.05f, 1f)};
    }

    /** The dark palette for a color (the player uses it while a video plays, in any theme). */
    static int[] darkRoles(int seed) {
        return rolesFor(true, seed);
    }

    /** The colors of the plain theme, as a palette (what the player shows outside Ambient mode). */
    static Palette themePalette(Context c) {
        ensure(c);
        return new Palette(base);
    }

    static Palette paletteOf(int[] roles) {
        return new Palette(roles);
    }

    private static void ensure(Context c) {
        boolean n = night(c);
        if (base == null || baseNight != n) {
            int[] b = new int[ATTRS.length];
            for (int i = 0; i < b.length; i++) b[i] = MaterialColors.getColor(c, ATTRS[i], 0);
            base = b;
            baseNight = n;
            cur = null;
            roleOf.clear();
            stable.clear();
            keepSets.clear();
            frameOf.clear();
            remember(b, true);
        }
        if (cur == null) {
            cur = rolesFor(c, storedSeed(c));
            prev = cur;
            remember(cur, false);
        }
    }

    /**
     * Keeps a set of colors known. "keep" sets (the plain theme and each screen's own theme colors)
     * are dropped last; finished palettes are dropped oldest first.
     */
    private static void remember(int[] colors, boolean keep) {
        for (int[] k : stable) if (java.util.Arrays.equals(k, colors)) return;
        int[] copy = colors.clone();
        if (keep) keepSets.add(copy);
        stable.add(copy);
        if (stable.size() > 60) {
            for (int i = 0; i < stable.size() && stable.size() > 48; i++) {
                if (!keepSets.contains(stable.get(i))) stable.remove(i--);
            }
            while (keepSets.size() > 24) keepSets.remove(1);
            roleOf.clear();
            for (int[] k : stable) for (int i = 0; i < k.length; i++) roleOf.put(k[i], i);
        }
        for (int i = 0; i < colors.length; i++) roleOf.put(colors[i], i);
    }

    /** Back to the plain theme (mode or light/dark changed). */
    public static void reset() {
        if (anim != null) anim.cancel();
        base = null;
        cur = null;
        prev = null;
        roleOf.clear();
        stable.clear();
        keepSets.clear();
        frameOf.clear();
    }

    // ---------- screens ----------

    /** Called when a screen has been built. Recolors it and keeps it in sync from now on. */
    public static void attach(Activity a) {
        if (!active(a)) return;
        ensure(a);
        // every screen's own theme colors map to the live palette, whichever seed it was built with
        int[] own = new int[ATTRS.length];
        for (int i = 0; i < own.length; i++) own[i] = MaterialColors.getColor(a, ATTRS[i], 0);
        remember(own, true);
        screens.add(new WeakReference<>(a));
        paint(a);
    }

    public static void detach(Activity a) {
        for (int i = screens.size() - 1; i >= 0; i--) {
            Activity s = screens.get(i).get();
            if (s == null || s == a) screens.remove(i);
        }
    }

    private static void paint(Activity a) {
        if (cur == null) return;
        a.getWindow().setBackgroundDrawable(new ColorDrawable(cur[SURFACE]));
        recolor(a.getWindow().getDecorView());
    }

    /** Blend to the colors of a new cover over about a second. */
    public static void animateTo(Context c, int seed) {
        ensure(c);
        final int[] from = cur.clone();
        final int[] to = rolesFor(c, seed);
        if (anim == null || !anim.isRunning()) frameOf.clear();
        if (anim != null) anim.cancel();
        final ArgbEvaluator e = new ArgbEvaluator();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(380);
        a.setInterpolator(new AccelerateDecelerateInterpolator());
        a.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            int[] next = new int[from.length];
            for (int i = 0; i < next.length; i++) next[i] = (int) e.evaluate(f, from[i], to[i]);
            prev = cur;
            cur = next;
            for (int i = 0; i < next.length; i++) frameOf.put(next[i], i);
            for (int i = screens.size() - 1; i >= 0; i--) {
                Activity s = screens.get(i).get();
                if (s == null || s.isDestroyed()) screens.remove(i);
                else paint(s);
            }
            Palette p = new Palette(cur);
            for (Listener l : listeners) l.onPalette(p);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator x) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator x) {
                if (cancelled) return;
                remember(to, false);      // the finished palette stays known, the in-between frames go
                frameOf.clear();
            }
        });
        a.start();
        anim = a;
    }

    /** Recolors a view tree that lives outside the screens (a dialog window). */
    public static void recolorNow(View root) {
        if (cur != null && root != null) recolor(root);
    }

    // ---------- the walker ----------

    private static final int[][] STATES = {
            {-android.R.attr.state_enabled}, {android.R.attr.state_checked},
            {android.R.attr.state_selected}, {android.R.attr.state_pressed}, {}};

    @Nullable
    private static ColorStateList mapCsl(@Nullable ColorStateList c) {
        if (c == null) return null;
        int def = c.getDefaultColor();
        if (!c.isStateful()) {
            int n = map(def);
            return n == def ? c : ColorStateList.valueOf(n);
        }
        int[] cols = new int[STATES.length];
        boolean changed = false;
        for (int i = 0; i < STATES.length; i++) {
            int o = c.getColorForState(STATES[i], def);
            cols[i] = map(o);
            if (cols[i] != o) changed = true;
        }
        return changed ? new ColorStateList(STATES, cols) : c;
    }

    private static void drawable(@Nullable Drawable d) {
        if (d == null) return;
        if (d instanceof MaterialShapeDrawable) {
            MaterialShapeDrawable m = (MaterialShapeDrawable) d;
            ColorStateList f = m.getFillColor();
            ColorStateList n = mapCsl(f);
            if (n != f) m.setFillColor(n);
        } else if (d instanceof GradientDrawable) {
            GradientDrawable g = (GradientDrawable) d;
            ColorStateList f = g.getColor();
            ColorStateList n = mapCsl(f);
            if (n != f && n != null) {
                g.mutate();
                g.setColor(n);
            }
        } else if (d instanceof ColorDrawable) {
            ColorDrawable cd = (ColorDrawable) d;
            int n = map(cd.getColor());
            if (n != cd.getColor()) {
                cd.mutate();
                cd.setColor(n);
            }
        } else if (d instanceof LayerDrawable) { // includes ripples
            LayerDrawable l = (LayerDrawable) d;
            for (int i = 0; i < l.getNumberOfLayers(); i++) drawable(l.getDrawable(i));
        } else if (d instanceof InsetDrawable) {
            drawable(((InsetDrawable) d).getDrawable());
        }
    }

    private static void recolorOne(View v) {
        if (v instanceof EdgeFade || v instanceof HeatBar) {
            v.invalidate();
            return;
        }
        if (v.getClass() == android.widget.ProgressBar.class && cur != null) {
            // the plain spinner keeps its creation-time color otherwise
            ((android.widget.ProgressBar) v).setIndeterminateTintList(
                    ColorStateList.valueOf(cur[PRIMARY]));
            return;
        }
        drawable(v.getBackground());
        ColorStateList bt = v.getBackgroundTintList();
        ColorStateList nbt = mapCsl(bt);
        if (nbt != bt) v.setBackgroundTintList(nbt);

        if (v instanceof TextView) {
            TextView t = (TextView) v;
            ColorStateList c = t.getTextColors();
            ColorStateList n = mapCsl(c);
            if (n != c) t.setTextColor(n);
        }
        if (v instanceof ImageView) {
            ImageView i = (ImageView) v;
            ColorStateList c = i.getImageTintList();
            ColorStateList n = mapCsl(c);
            if (n != c) i.setImageTintList(n);
        }
        if (v instanceof com.google.android.material.button.MaterialButton) {
            com.google.android.material.button.MaterialButton b = (com.google.android.material.button.MaterialButton) v;
            ColorStateList c = b.getIconTint();
            ColorStateList n = mapCsl(c);
            if (n != c) b.setIconTint(n);
            ColorStateList sc = b.getStrokeColor();
            ColorStateList nsc = mapCsl(sc);
            if (nsc != sc) b.setStrokeColor(nsc);
            ColorStateList rc = b.getRippleColor();
            ColorStateList nrc = mapCsl(rc);
            if (nrc != rc) b.setRippleColor(nrc);
        }
        if (v instanceof SwitchCompat) {
            SwitchCompat s = (SwitchCompat) v;
            ColorStateList th = s.getThumbTintList(), tr = s.getTrackTintList();
            ColorStateList nth = mapCsl(th), ntr = mapCsl(tr);
            if (nth != th) s.setThumbTintList(nth);
            if (ntr != tr) s.setTrackTintList(ntr);
        }
        if (v instanceof Slider) {
            Slider s = (Slider) v;
            ColorStateList a = s.getThumbTintList(), b = s.getTrackActiveTintList(),
                    c = s.getTrackInactiveTintList(), d = s.getTickActiveTintList(),
                    e = s.getTickInactiveTintList();
            ColorStateList na = mapCsl(a), nb = mapCsl(b), nc = mapCsl(c), nd = mapCsl(d), ne = mapCsl(e);
            if (na != a) s.setThumbTintList(na);
            if (nb != b) s.setTrackActiveTintList(nb);
            if (nc != c) s.setTrackInactiveTintList(nc);
            if (nd != d) s.setTickActiveTintList(nd);
            if (ne != e) s.setTickInactiveTintList(ne);
        }
        if (v instanceof BottomNavigationView) {
            BottomNavigationView n = (BottomNavigationView) v;
            ColorStateList a = n.getItemIconTintList(), b = n.getItemTextColor(),
                    c = n.getItemActiveIndicatorColor();
            ColorStateList na = mapCsl(a), nb = mapCsl(b), nc = mapCsl(c);
            if (na != a) n.setItemIconTintList(na);
            if (nb != b) n.setItemTextColor(nb);
            if (nc != c) n.setItemActiveIndicatorColor(nc);
        }
        if (v instanceof TabLayout) {
            TabLayout t = (TabLayout) v;
            // set straight from the live palette: selected = accent, others = on-surface-variant
            t.setTabTextColors(cur[15], cur[PRIMARY]);
            t.setSelectedTabIndicatorColor(cur[PRIMARY]);
            t.setTabRippleColor(ColorStateList.valueOf((cur[PRIMARY] & 0x00FFFFFF) | 0x33000000));
        }
        if (v instanceof BaseProgressIndicator) {
            BaseProgressIndicator<?> p = (BaseProgressIndicator<?>) v;
            int[] ic = p.getIndicatorColor();
            int[] nic = new int[ic.length];
            boolean ch = false;
            for (int i = 0; i < ic.length; i++) {
                nic[i] = map(ic[i]);
                if (nic[i] != ic[i]) ch = true;
            }
            if (ch) p.setIndicatorColor(nic);
            int tc = p.getTrackColor();
            if (map(tc) != tc) p.setTrackColor(map(tc));
        }
        if (v instanceof MaterialCardView) {
            MaterialCardView m = (MaterialCardView) v;
            ColorStateList c = m.getCardBackgroundColor();
            ColorStateList n = mapCsl(c);
            if (n != c) m.setCardBackgroundColor(n);
        }
        if (v instanceof RecyclerView) watchAdapter((RecyclerView) v);
        if (v instanceof RecyclerView && v.getTag(R.id.ambient_watch) == null) {
            v.setTag(R.id.ambient_watch, Boolean.TRUE);
            // rows are created and recycled while scrolling: color each one as it appears
            ((RecyclerView) v).addOnChildAttachStateChangeListener(
                    new RecyclerView.OnChildAttachStateChangeListener() {
                        @Override
                        public void onChildViewAttachedToWindow(View child) {
                            if (cur != null) recolor(child);
                        }

                        @Override
                        public void onChildViewDetachedFromWindow(View child) { }
                    });
        }
    }

    /** Rows that are re-bound in place (e.g. the "now playing" highlight) get recolored after layout. */
    private static void watchAdapter(final RecyclerView rv) {
        final RecyclerView.Adapter<?> ad = rv.getAdapter();
        if (ad == null || rv.getTag(R.id.ambient_adapter) == ad) return;
        rv.setTag(R.id.ambient_adapter, ad);
        ad.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            private boolean pending;

            private void changed() {
                if (pending) return;
                pending = true;
                rv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        pending = false;
                        rv.getViewTreeObserver().removeOnPreDrawListener(this);
                        if (cur != null) recolor(rv);
                        return true;
                    }
                });
            }

            @Override public void onChanged() { changed(); }
            @Override public void onItemRangeChanged(int s, int c) { changed(); }
            @Override public void onItemRangeChanged(int s, int c, @Nullable Object p) { changed(); }
            @Override public void onItemRangeInserted(int s, int c) { changed(); }
            @Override public void onItemRangeRemoved(int s, int c) { changed(); }
            @Override public void onItemRangeMoved(int a, int b, int c) { changed(); }
        });
    }

    /** Recolors a view and everything inside it. Safe to call repeatedly. */
    public static void recolor(View v) {
        if (cur == null || base == null) return;
        recolorOne(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolor(g.getChildAt(i));
        }
    }
}
