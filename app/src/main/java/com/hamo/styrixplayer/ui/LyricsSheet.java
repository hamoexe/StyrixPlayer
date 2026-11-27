package com.hamo.styrixplayer.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.hamo.styrixplayer.I18n;
import com.hamo.styrixplayer.lyrics.LyricsRepo;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The lyrics options as one bottom sheet with three tabs (Sync / Source / Edit). The Source tab
 * asks every provider what it has for the song, live, and shows which ones have word-by-word,
 * line-by-line or plain lyrics, greying out the ones with nothing.
 */
final class LyricsSheet {

    interface Host {
        void onOffset(long ms);
        void onEdit();
        /** Automatic mode: save it as the default and search again. */
        void onAuto();
        /** Use this source's result for the song now. {@code forAll} also makes it the default. */
        void onApply(LyricsRepo.Source s, boolean forAll);
        void onSearchAgain();
        /** Start asking all sources; results arrive on any thread. */
        void probe(LyricsRepo.ProbeListener l);
    }

    private static final String[] NAMES = {"Local file", "LyricsPlus", "BiniLyrics", "Kugou", "QQ Music", "NetEase", "LRCLIB"};

    private final Activity act;
    private final Host host;
    private final boolean synced, custom;
    private final String pref;
    private String curSynced, curPlain;
    private boolean curInstr;
    private long offset;

    private int bg, text, sub, accent, onAccent, tonal, onTonal;
    private TextView readout, subtitle, summary;
    private MaterialButton resetBtn;
    private MaterialSwitch forAll;
    private BottomSheetDialog dlg;
    private final View[] panels = new View[3];
    private final MaterialButton[] tabs = new MaterialButton[3];
    private final Map<String, Row> rows = new HashMap<>();
    private boolean probed, autoPick;
    private int answered, usable;

    private static final class Row {
        LinearLayout card;
        TextView status;
        TextView chip;
        ProgressBar spin;
        RadioButton radio;
        LyricsRepo.Source src;
    }

    private LyricsSheet(Activity act, Host host, boolean synced, boolean custom, long offset, String pref,
                        String curSynced, String curPlain, boolean curInstr) {
        this.act = act;
        this.curInstr = curInstr;
        this.host = host;
        this.synced = synced;
        this.custom = custom;
        this.offset = offset;
        this.pref = pref == null ? "auto" : pref;
        this.curSynced = curSynced;
        this.curPlain = curPlain;
    }

    static void show(Activity act, Host host, boolean synced, boolean custom, long offset, String pref,
                     String curSynced, String curPlain, boolean curInstr) {
        new LyricsSheet(act, host, synced, custom, offset, pref, curSynced, curPlain, curInstr).open();
    }

    private static String nameOf(String key) {
        for (int i = 0; i < LyricsRepo.ALL_KEYS.length; i++) if (LyricsRepo.ALL_KEYS[i].equals(key)) return NAMES[i];
        return "Automatic";
    }

    // ---------------------------------------------------------------- build

    private void open() {
        loadColors();
        dlg = new BottomSheetDialog(act);
        final BottomSheetDialog d = dlg;

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(10), dp(20), dp(22));

        View handle = new View(act);
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(dp(3));
        hb.setColor((text & 0x00FFFFFF) | 0x40000000);
        handle.setBackground(hb);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(38), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(14);
        root.addView(handle, hp);

        root.addView(label("Lyrics", 22, text, true), lp(-1, -2, 0, 0, 0, 2));
        subtitle = label(pref.equals("auto") ? "Source: Automatic" : "Source: " + nameOf(pref), 13, sub, false);
        root.addView(subtitle, lp(-1, -2, 0, 0, 0, 14));

        // segmented tabs on a soft track
        LinearLayout track = new LinearLayout(act);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setPadding(dp(4), dp(4), dp(4), dp(4));
        track.setBackground(round((text & 0x00FFFFFF) | 0x14000000, 28, 0, 0));
        String[] tn = {"Sync", "Source", "Edit"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            MaterialButton b = new MaterialButton(act);
            b.setText(tn[i]);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setCornerRadius(dp(24));
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setElevation(0f);
            b.setStateListAnimator(null);
            b.setOnClickListener(v -> select(idx));
            tabs[i] = b;
            track.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        root.addView(track, lp(-1, -2, 0, 0, 0, 16));

        FrameLayout body = new FrameLayout(act);
        panels[0] = syncPanel();
        panels[1] = sourcePanel();
        panels[2] = editPanel();
        for (View p : panels) body.addView(p, new FrameLayout.LayoutParams(-1, -2));
        root.addView(body, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(act);
        sv.addView(root);
        d.setContentView(sv);

        select(synced ? 0 : 1);

        d.setOnShowListener(x -> {
            try {
                View sheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (sheet != null) {
                    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{mix(bg, text, 0.07f), bg});
                    float r = dp(30);
                    g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
                    g.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x33000000);
                    sheet.setBackground(g);
                    BottomSheetBehavior<View> bb = BottomSheetBehavior.from(sheet);
                    bb.setSkipCollapsed(true);
                    bb.setState(BottomSheetBehavior.STATE_EXPANDED);
                }
                I18n.install(d.getWindow().getDecorView());
            } catch (Throwable ignored) {
                // keep defaults
            }
        });
        d.show();
    }

    private View syncPanel() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);
        if (!synced) {
            p.addView(label("These lyrics are not time-synced, so there is nothing to shift.", 14, sub, false),
                    lp(-1, -2, 0, 8, 0, 8));
            return p;
        }
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(16), dp(18), dp(16), dp(16));
        GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{(accent & 0x00FFFFFF) | 0x40000000, (accent & 0x00FFFFFF) | 0x12000000});
        cg.setCornerRadius(dp(28));
        cg.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x4D000000);
        card.setBackground(cg);
        card.setElevation(dp(2));

        readout = label("", 56, text, false);
        readout.setGravity(Gravity.CENTER);
        readout.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        card.addView(readout, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = label("Lyrics too late? Move them earlier. Too early? Move them later.", 13, sub, false);
        hint.setGravity(Gravity.CENTER);
        card.addView(hint, lp(-1, -2, 0, 2, 0, 0));
        p.addView(card, lp(-1, -2, 0, 0, 0, 14));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        long[] steps = {-500, -100, 100, 500};
        String[] lab = {"−0.5", "−0.1", "+0.1", "+0.5"};
        for (int i = 0; i < 4; i++) {
            final long st = steps[i];
            MaterialButton b = pill(lab[i], 17);
            b.setOnClickListener(v -> setOffset(offset + st));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
            lp.rightMargin = i < 3 ? dp(8) : 0;
            row.addView(b, lp);
        }
        p.addView(row, lp(-1, -2, 0, 0, 0, 10));

        resetBtn = pill("Reset", 15);
        resetBtn.setOnClickListener(v -> setOffset(0));
        p.addView(resetBtn, lp(-1, 48, 0, 0, 0, 0));
        refreshOffset();
        return p;
    }

    private View sourcePanel() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);

        summary = label("Checking the sources…", 13, sub, false);
        p.addView(summary, lp(-1, -2, 2, 0, 0, 10));

        // Automatic
        LinearLayout auto = card(pref.equals("auto"));
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label("Automatic", 16, text, true));
        col.addView(label("Best match from all sources", 12, sub, false));
        auto.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        if (pref.equals("auto")) auto.addView(chip("Default", 3));
        auto.setOnClickListener(v -> { host.onAuto(); dlg.dismiss(); });
        p.addView(auto, lp(-1, -2, 0, 0, 0, 8));

        for (int i = 0; i < LyricsRepo.ALL_KEYS.length; i++) {
            final String key = LyricsRepo.ALL_KEYS[i];
            final Row r = new Row();
            r.card = card(false);
            LinearLayout c2 = new LinearLayout(act);
            c2.setOrientation(LinearLayout.VERTICAL);
            c2.addView(label(NAMES[i], 16, text, true));
            r.status = label("Searching…", 12, sub, false);
            c2.addView(r.status);
            r.card.addView(c2, new LinearLayout.LayoutParams(0, -2, 1f));
            r.spin = new ProgressBar(act);
            r.spin.setIndeterminate(true);
            r.spin.setIndeterminateTintList(ColorStateList.valueOf(accent));
            r.card.addView(r.spin, new LinearLayout.LayoutParams(dp(22), dp(22)));
            r.chip = chip("", 3);
            r.chip.setVisibility(View.GONE);
            r.card.addView(r.chip);
            r.radio = new RadioButton(act);
            r.radio.setClickable(false);
            r.radio.setFocusable(false);
            r.radio.setButtonTintList(ColorStateList.valueOf(accent));
            r.radio.setVisibility(View.GONE);
            r.card.addView(r.radio);
            r.card.setAlpha(0.6f);
            r.card.setOnClickListener(v -> {
                if (r.src == null || !r.src.usable()) return;
                apply(r.src, forAll != null && forAll.isChecked());
            });
            rows.put(key, r);
            p.addView(r.card, lp(-1, -2, 0, 0, 0, 8));
        }

        LinearLayout sw = new LinearLayout(act);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.setPadding(dp(4), dp(2), dp(4), dp(2));
        TextView st = label("Use the chosen source for every song", 14, text, false);
        sw.addView(st, new LinearLayout.LayoutParams(0, -2, 1f));
        forAll = new MaterialSwitch(act);
        int[][] on = {{android.R.attr.state_checked}, {}};
        forAll.setThumbTintList(new ColorStateList(on, new int[]{onAccent, sub}));
        forAll.setTrackTintList(new ColorStateList(on, new int[]{accent, (text & 0x00FFFFFF) | 0x33000000}));
        forAll.setTrackDecorationTintList(new ColorStateList(on, new int[]{accent, sub}));
        sw.addView(forAll);
        p.addView(sw, lp(-1, -2, 0, 4, 0, 4));

        MaterialButton again = pill(custom ? "Discard my version and search online" : "Search again", 15);
        again.setOnClickListener(v -> {
            host.onSearchAgain();
            curSynced = null;
            curPlain = null;
            curInstr = false;
            autoPick = true;
            startProbe();
        });
        p.addView(again, lp(-1, 48, 0, 4, 0, 0));
        return p;
    }

    private View editPanel() {
        LinearLayout p = new LinearLayout(act);
        p.setOrientation(LinearLayout.VERTICAL);
        p.addView(label("Write your own lyrics, fix a line, add timing or translate. Your version is kept for this song.",
                14, sub, false), lp(-1, -2, 0, 4, 0, 16));
        MaterialButton b = pill("Edit / translate lyrics", 16);
        b.setBackgroundTintList(ColorStateList.valueOf(accent));
        b.setTextColor(onAccent);
        b.setOnClickListener(v -> { dlg.dismiss(); host.onEdit(); });
        p.addView(b, lp(-1, 54, 0, 0, 0, 0));
        return p;
    }

    // ---------------------------------------------------------------- behaviour

    private void select(int idx) {
        for (int i = 0; i < 3; i++) {
            panels[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            boolean on = i == idx;
            tabs[i].setBackgroundTintList(ColorStateList.valueOf(on ? accent : Color.TRANSPARENT));
            tabs[i].setTextColor(on ? onAccent : sub);
        }
        if (idx == 1 && !probed) startProbe();
    }

    /** Resets every row to "Searching…" and asks all sources again. */
    private void startProbe() {
        probed = true;
        answered = 0;
        usable = 0;
        final int token = ++probeToken;
        for (Row r : rows.values()) {
            r.src = null;
            r.spin.setVisibility(View.VISIBLE);
            r.chip.setVisibility(View.GONE);
            r.radio.setVisibility(View.GONE);
            r.radio.setChecked(false);
            r.status.setVisibility(View.VISIBLE);
            r.status.setText(I18n.t("Searching…"));
            r.card.setBackground(cardBg(false));
            r.card.setClickable(false);
            r.card.animate().cancel();
            r.card.setAlpha(0.6f);
        }
        summary.setText(I18n.t("Checking the sources…"));
        summary.postDelayed(() -> {
            // don't make the user wait for the slowest source once a word-by-word result is in
            if (token == probeToken && autoPick && hasKind(LyricsRepo.P_WORD)) {
                autoPick = false;
                autoSelect();
            }
        }, 6000);
        host.probe(s -> act.runOnUiThread(() -> { if (token == probeToken) bind(s); }));
    }

    private int probeToken;

    private boolean hasKind(int kind) {
        for (Row r : rows.values()) if (r.src != null && r.src.kind == kind) return true;
        return false;
    }

    /** Switches to a source's lyrics and keeps the sheet open. */
    private void apply(LyricsRepo.Source s, boolean all) {
        host.onApply(s, all);
        curSynced = s.synced;
        curPlain = s.plain;
        curInstr = s.kind == LyricsRepo.P_INSTR;
        refreshMarks();
    }

    private void refreshMarks() {
        for (Map.Entry<String, Row> e : rows.entrySet()) {
            Row r = e.getValue();
            if (r.src == null || !r.src.usable()) continue;
            boolean cur = sameAsCurrent(r.src);
            r.radio.setChecked(cur);
            r.card.setBackground(cardBg(cur));
            if (cur) subtitle.setText(I18n.t("Showing") + " " + nameOf(r.src.key) + " · "
                    + kindText(r.src.kind).toLowerCase());
        }
    }

    private static String kindText(int kind) {
        switch (kind) {
            case LyricsRepo.P_WORD: return "Word by word";
            case LyricsRepo.P_LINE: return "Line by line";
            case LyricsRepo.P_TEXT: return "Text only";
            case LyricsRepo.P_ERROR: return "Couldn't reach";
            case LyricsRepo.P_SLOW: return "Too slow";
            case LyricsRepo.P_INSTR: return "Instrumental";
            default: return "Nothing found";
        }
    }

    /** After a fresh search: word-by-word if any source has it, else line-by-line, else plain text. */
    private void autoSelect() {
        LyricsRepo.Source best = null;
        for (int want : new int[]{LyricsRepo.P_WORD, LyricsRepo.P_LINE, LyricsRepo.P_TEXT, LyricsRepo.P_INSTR}) {
            for (String k : LyricsRepo.ALL_KEYS) {
                Row r = rows.get(k);
                if (r != null && r.src != null && r.src.kind == want) { best = r.src; break; }
            }
            if (best != null) break;
        }
        if (best != null) apply(best, false);
    }

    private void bind(LyricsRepo.Source s) {
        if (dlg == null || !dlg.isShowing()) return;
        Row r = rows.get(s.key);
        if (r == null) return;
        r.src = s;
        answered++;
        r.spin.setVisibility(View.GONE);
        boolean ok = s.usable();
        if (ok) usable++;
        String t = kindText(s.kind);
        String note = s.note == null ? "" : (s.note.length() > 60 ? s.note.substring(0, 60) + "…" : s.note);
        if (note.isEmpty()) r.status.setText(I18n.t(t));
        else if (s.kind == LyricsRepo.P_NONE) r.status.setText(I18n.t(note));
        else r.status.setText(I18n.t(t) + " · " + I18n.t(note));
        if (ok) {
            r.chip.setText(I18n.t(t));
            r.chip.setBackground(chipBg(s.kind));
            r.chip.setTextColor(s.kind == LyricsRepo.P_WORD ? onAccent : s.kind == LyricsRepo.P_LINE ? onTonal : sub);
            r.chip.setVisibility(View.VISIBLE);
            r.status.setVisibility(View.GONE);
            boolean current = sameAsCurrent(s);
            r.radio.setChecked(current);
            r.radio.setVisibility(View.VISIBLE);
            r.card.setBackground(cardBg(current));
            if (current) subtitle.setText(I18n.t("Showing") + " " + nameOf(s.key) + " · " + I18n.t(t).toLowerCase());
        }
        r.card.animate().alpha(ok ? 1f : 0.38f).setDuration(220).start();
        r.card.setClickable(ok);
        summary.setText(answered < LyricsRepo.ALL_KEYS.length
                ? I18n.t("Checking the sources…") + " " + answered + "/" + LyricsRepo.ALL_KEYS.length
                : usable + " / " + LyricsRepo.ALL_KEYS.length + " " + I18n.t("sources have these lyrics"));
        if (answered == LyricsRepo.ALL_KEYS.length && autoPick) {
            autoPick = false;
            autoSelect();
        }
    }

    private boolean sameAsCurrent(LyricsRepo.Source s) {
        if (s.kind == LyricsRepo.P_INSTR) return curInstr;
        if (curInstr) return false;
        String a = norm(curSynced), b = norm(curPlain);
        if (!a.isEmpty()) return a.equals(norm(s.synced));
        return !b.isEmpty() && b.equals(norm(s.plain)) && norm(s.synced).isEmpty();
    }

    private static String norm(String x) { return x == null ? "" : x.trim(); }

    private void setOffset(long ms) {
        offset = Math.max(-30000, Math.min(30000, ms));
        host.onOffset(offset);
        refreshOffset();
    }

    private void refreshOffset() {
        if (readout != null) readout.setText(String.format(Locale.US, "%+.1f s", offset / 1000f));
        if (resetBtn != null) resetBtn.setAlpha(offset == 0 ? 0.45f : 1f);
    }

    // ---------------------------------------------------------------- helpers

    private void loadColors() {
        AmbientTheme.Palette p = null;
        try { p = AmbientTheme.active(act) ? AmbientTheme.current() : null; } catch (Throwable ignored) { }
        if (p != null) {
            bg = p.high | 0xFF000000;
            text = p.text | 0xFF000000;
            sub = p.onVariant | 0xFF000000;
            accent = p.accent | 0xFF000000;
            onAccent = p.onAccent | 0xFF000000;
            tonal = p.tonal | 0xFF000000;
            onTonal = p.onTonal | 0xFF000000;
        } else {
            View v = act.getWindow().getDecorView();
            bg = MaterialColors.getColor(v, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.DKGRAY);
            text = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
            sub = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY);
            accent = MaterialColors.getColor(v, com.google.android.material.R.attr.colorPrimary, Color.CYAN);
            onAccent = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnPrimary, Color.BLACK);
            tonal = MaterialColors.getColor(v, com.google.android.material.R.attr.colorSecondaryContainer, Color.GRAY);
            onTonal = MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.WHITE);
        }
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
        int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    private GradientDrawable round(int fill, float radiusDp, int strokeDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(radiusDp));
        g.setColor(fill);
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private GradientDrawable cardBg(boolean selected) {
        return selected
                ? round((accent & 0x00FFFFFF) | 0x2E000000, 20, 1, accent)
                : round((text & 0x00FFFFFF) | 0x0F000000, 20, 1, (text & 0x00FFFFFF) | 0x1F000000);
    }

    private LinearLayout card(boolean selected) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        row.setBackground(cardBg(selected));
        row.setMinimumHeight(dp(60));
        return row;
    }

    private GradientDrawable chipBg(int kind) {
        if (kind == LyricsRepo.P_WORD) return round(accent, 12, 0, 0);
        if (kind == LyricsRepo.P_LINE) return round(tonal, 12, 0, 0);
        return round(Color.TRANSPARENT, 12, 1, sub);
    }

    private TextView chip(String s, int kind) {
        TextView t = label(s, 12, kind == LyricsRepo.P_WORD ? onAccent : sub, true);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setBackground(chipBg(kind));
        return t;
    }

    private MaterialButton pill(String s, float sp) {
        MaterialButton b = new MaterialButton(act);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setCornerRadius(dp(26));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setElevation(0f);
        b.setStateListAnimator(null);
        b.setBackgroundTintList(ColorStateList.valueOf(tonal));
        b.setTextColor(onTonal);
        b.setStrokeColor(ColorStateList.valueOf((accent & 0x00FFFFFF) | 0x40000000));
        b.setStrokeWidth(dp(1));
        return b;
    }

    private TextView label(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }
}
