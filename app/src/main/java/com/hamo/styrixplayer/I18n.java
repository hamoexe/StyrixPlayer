package com.hamo.styrixplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.Locale;

/**
 * Languages: English, Arabic, Kurdish (Sorani) and Turkish.
 *
 * The app's text is written in English all over the code, so instead of touching every string the
 * translator works on what is about to be shown: {@link #install} runs over a window's views before
 * each draw and swaps known English texts for the chosen language ({@link I18nData}). Exact phrases
 * are looked up first, then fragments ("Added to ", " min", ...) for sentences with a number or name
 * in them. Song titles / artists are never touched. Layout direction (RTL) comes from the locale.
 */
public final class I18n {

    public static final String[] CODES = {"system", "en", "ar", "ckb", "tr"};
    /** Names shown in the picker; "System default" is translated, the others are written in their own language. */
    public static final String[] NAMES = {"System default", "English", "العربية", "کوردی (سۆرانی)", "Türkçe"};

    private static final String KEY = "lang";

    /** Language in effect: en, ar, ckb or tr. */
    private static volatile String lang = "en";
    private static int idx = 0;
    private static HashMap<String, String[]> exact;
    private static final HashMap<String, String> cache = new HashMap<>();

    private I18n() {}

    private static SharedPreferences sp(Context c) {
        Context a = c.getApplicationContext();
        return (a != null ? a : c).getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Stored choice: one of CODES. */
    public static String pref(Context c) {
        String p = sp(c).getString(KEY, "system");
        for (String x : CODES) if (x.equals(p)) return p;
        return "system";
    }

    public static int prefIndex(Context c) {
        String p = pref(c);
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(p)) return i;
        return 0;
    }

    public static void setPref(Context c, String code) {
        sp(c).edit().putString(KEY, code).apply();
        resolve(code);
    }

    /** For change detection (see AppTheme.key). */
    public static String key(Context c) {
        return pref(c);
    }

    private static void resolve(String pref) {
        String l = pref;
        if ("system".equals(pref)) {
            Locale sys = Resources.getSystem().getConfiguration().getLocales().get(0);
            String s = sys == null ? "en" : sys.getLanguage();
            if ("ku".equals(s) || "ckb".equals(s)) l = "ckb";
            else if ("ar".equals(s) || "tr".equals(s)) l = s;
            else l = "en";
        }
        lang = l;
        idx = "ar".equals(l) ? 1 : "ckb".equals(l) ? 2 : "tr".equals(l) ? 3 : 0;
        synchronized (cache) {
            cache.clear();
        }
    }

    /** Same language, but with Western digits (0-9) everywhere. */
    private static Locale latin(Locale l) {
        try {
            return new Locale.Builder().setLocale(l).setUnicodeLocaleKeyword("nu", "latn").build();
        } catch (Exception e) {
            return l;
        }
    }

    private static Locale localeFor(String l) {
        if ("ar".equals(l)) return latin(new Locale("ar"));
        if ("ckb".equals(l)) return latin(new Locale("ckb"));
        if ("tr".equals(l)) return latin(new Locale("tr"));
        return latin(Locale.ENGLISH);
    }

    /** The locale a screen should use, or null to keep the system's. */
    @Nullable
    public static Locale locale(Context c) {
        String p = pref(c);
        return "system".equals(p) ? null : localeFor(p);
    }

    /** Call from attachBaseContext: sets the language and returns a context with that locale. */
    public static Context wrap(Context base) {
        String p = pref(base);
        resolve(p);
        if ("system".equals(p)) {
            Locale sys = Resources.getSystem().getConfiguration().getLocales().get(0);
            Locale.setDefault(latin(sys == null ? Locale.ENGLISH : sys));
            return base;
        }
        Locale loc = localeFor(p);
        Locale.setDefault(loc);
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(loc);
        c.setLayoutDirection(loc);
        return base.createConfigurationContext(c);
    }

    // ---------- lookup ----------

    private static synchronized void load() {
        if (exact != null) return;
        HashMap<String, String[]> m = new HashMap<>();
        for (String[] r : I18nData.ROWS) m.put(r[0], r);
        exact = m;
    }

    /** Translates a text (exact phrase first, then fragments). Unknown text comes back unchanged. */
    public static String t(@Nullable String s) {
        if (s == null || s.isEmpty() || idx == 0) return s;
        synchronized (cache) {
            String c = cache.get(s);
            if (c != null) return c;
        }
        load();
        String r;
        String[] row = exact.get(s);
        if (row != null) {
            r = row[idx];
        } else {
            r = s;
            for (String[] f : I18nData.FRAGS) {
                if (r.contains(f[0])) r = r.replace(f[0], f[idx]);
            }
        }
        synchronized (cache) {
            if (cache.size() > 3000) cache.clear();
            cache.put(s, r);
        }
        return r;
    }

    /** Same for any CharSequence; returns the original object when nothing changed. */
    public static CharSequence tc(@Nullable CharSequence s) {
        if (s == null || idx == 0 || s.length() == 0) return s;
        String in = s.toString();
        String out = t(in);
        return out.equals(in) ? s : out;
    }

    // ---------- views ----------

    /** Translates the window now and again before every draw (new rows, dynamic texts, dialogs). */
    public static void install(final View decor) {
        if (decor == null || idx == 0) return;
        walk(decor);
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                walk(decor);
                return true;
            }
        });
    }

    /** One-off pass over a view tree (popup windows). */
    public static void apply(View root) {
        if (root != null && idx != 0) walk(root);
    }

    private static void walk(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (!(tv instanceof EditText) && !skip(tv)) {
                CharSequence cs = tv.getText();
                if (cs != null && cs.length() > 0) {
                    String s = cs.toString();
                    String r = t(s);
                    if (!r.equals(s)) tv.setText(r);
                }
            }
            CharSequence h = tv.getHint();
            if (h != null && h.length() > 0) {
                String s = h.toString();
                String r = t(s);
                if (!r.equals(s)) tv.setHint(r);
            }
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) {
            String s = cd.toString();
            String r = t(s);
            if (!r.equals(s)) v.setContentDescription(r);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    /** Song titles, artists and counts are the user's data, not interface text. */
    private static boolean skip(TextView tv) {
        int id = tv.getId();
        if (id == View.NO_ID) return false;
        if (id == R.id.queue_title || id == R.id.queue_artist || id == R.id.song_subtitle
                || id == R.id.song_tags || id == R.id.song_duration || id == R.id.playlist_name
                || id == R.id.pick_name || id == R.id.mini_title || id == R.id.mini_artist) {
            return true;
        }
        if (id == R.id.song_title) {
            // a section header is the row itself (child of the list); a song row's title is not
            return !(tv.getParent() instanceof RecyclerView);
        }
        if (id == R.id.title || id == R.id.artist) {
            android.view.ViewParent p = tv.getParent();
            while (p instanceof View) {
                if (((View) p).getId() == R.id.player_root) return true;
                p = p.getParent();
            }
        }
        return false;
    }
}
