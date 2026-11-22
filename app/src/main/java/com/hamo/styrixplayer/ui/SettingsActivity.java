package com.hamo.styrixplayer.ui;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.hamo.styrixplayer.I18n;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Prefs;
import com.hamo.styrixplayer.yt.YtAuth;

public class SettingsActivity extends BaseActivity {

    private static final String[] THEME_LABELS = {"System default", "Light", "Dark"};
    private static final int[] MIN_LEN_VALUES = {0, 15, 30, 60, 120};
    private static final String[] MIN_LEN_LABELS = {"Show everything", "15 seconds", "30 seconds",
            "1 minute", "2 minutes"};

    private LinearLayout container;
    private TextView ytValue;
    private View ytSignOutRow;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        padSystemBars(findViewById(R.id.settings_root), true, true);
        findViewById(R.id.settings_back).setOnClickListener(v -> finish());
        container = findViewById(R.id.settings_container);

        // ----- Appearance -----
        addSection("Appearance");

        final TextView themeValue = addValueRow("Theme", "Light, dark or follow the system",
                THEME_LABELS[Prefs.getInt(this, Prefs.THEME, 0)]);
        ((View) themeValue.getParent()).setOnClickListener(v ->
                new ThemedDialogBuilder(this)
                        .setTitle("Theme")
                        .setSingleChoiceItems(THEME_LABELS, Prefs.getInt(this, Prefs.THEME, 0),
                                (dialog, which) -> {
                                    Prefs.setInt(this, Prefs.THEME, which);
                                    themeValue.setText(THEME_LABELS[which]);
                                    dialog.dismiss();
                                    AmbientTheme.reset();
                                    Prefs.applyNightMode(this);
                                })
                        .show());

        final TextView modeValue = addValueRow("Color style",
                AppTheme.MODE_SUMMARIES[AppTheme.mode(this)], AppTheme.MODE_LABELS[AppTheme.mode(this)]);
        ((View) modeValue.getParent()).setOnClickListener(v ->
                new ThemedDialogBuilder(this)
                        .setTitle("Color style")
                        .setSingleChoiceItems(styleItems(), AppTheme.mode(this), (dialog, which) -> {
                            Prefs.setInt(this, Prefs.COLOR_MODE, which);
                            dialog.dismiss();
                            AmbientTheme.reset();
                            recreateForTheme();
                        })
                        .show());

        final TextView colorValue = addValueRow("Custom color",
                "Used by Custom color, and as the starting color for Ambient",
                hex(AppTheme.customColor(this)));
        ((View) colorValue.getParent()).setOnClickListener(v -> pickColor(colorValue));

        final TextView iconValue = addValueRow("App icon", "Change the icon on your home screen",
                com.hamo.styrixplayer.AppIcons.NAMES[com.hamo.styrixplayer.AppIcons.current(this)]);
        ((View) iconValue.getParent()).setOnClickListener(v -> pickIcon(iconValue));

        final TextView langValue = addValueRow("Language", "Choose language",
                I18n.NAMES[I18n.prefIndex(this)]);
        ((View) langValue.getParent()).setOnClickListener(v ->
                new ThemedDialogBuilder(this)
                        .setTitle("Language")
                        .setSingleChoiceItems(I18n.NAMES, I18n.prefIndex(this), (dialog, which) -> {
                            I18n.setPref(this, I18n.CODES[which]);
                            dialog.dismiss();
                            recreateForTheme();
                        })
                        .show());

        // ----- Library -----
        addSection("Library");

        final TextView minValue = addValueRow("Hide short audio",
                "Skip clips and sound effects shorter than this", minLenLabel());
        ((View) minValue.getParent()).setOnClickListener(v -> {
            int current = Prefs.getInt(this, Prefs.MIN_LEN, 30);
            int checked = 2;
            for (int i = 0; i < MIN_LEN_VALUES.length; i++) {
                if (MIN_LEN_VALUES[i] == current) checked = i;
            }
            new ThemedDialogBuilder(this)
                    .setTitle("Hide audio shorter than")
                    .setSingleChoiceItems(MIN_LEN_LABELS, checked, (dialog, which) -> {
                        Prefs.setInt(this, Prefs.MIN_LEN, MIN_LEN_VALUES[which]);
                        minValue.setText(minLenLabel());
                        dialog.dismiss();
                    })
                    .show();
        });

        addSwitch("Clean up song titles",
                "Moves (Slowed), [Remix] and similar into small labels. Applies after a rescan.",
                Prefs.SMART_TITLES, true);

        // ----- Playback -----
        addSection("Playback");
        addSwitch("Resume where you left off",
                "Restore your queue and position when the app starts", Prefs.RESUME, true);
        addSwitch("Remember speed per song",
                "Each song keeps the playback speed you set for it", Prefs.SPEED_PER_TRACK, false);
        addSwitch("Most played part",
                "Shows a small graph above the seek bar of the parts of a song you replay most.",
                Prefs.HEATMAP, true);
        addSwitch("Color mesh background",
                "The player background takes a different color in every corner of the cover. Off: blurred cover.",
                Prefs.BG_MESH, true);
        final TextView ambientValue = addValueRow("Video ambient speed",
                "How closely the player colors follow the video. Faster looks more alive but uses more battery.",
                ambientLabel());
        ((View) ambientValue.getParent()).setOnClickListener(v ->
                new ThemedDialogBuilder(this)
                        .setTitle("Video ambient speed")
                        .setSingleChoiceItems(AMBIENT_LABELS, Prefs.videoAmbient(this), (dialog, which) -> {
                            Prefs.setInt(this, Prefs.VIDEO_AMBIENT, which);
                            ambientValue.setText(ambientLabel());
                            dialog.dismiss();
                        })
                        .show());
        addInvertedSwitch("Pitch changes with speed",
                "On: classic slowed / sped-up sound. Off: original pitch.", Prefs.KEEP_PITCH, true);

        // ----- YouTube -----
        addSection("YouTube");
        ytValue = addValueRow("YouTube account",
                "Sign in to browse and stream your YouTube Music library", ytLabel());
        ((View) ytValue.getParent()).setOnClickListener(v -> {
            if (!YtAuth.isSignedIn(this)) {
                startActivity(new android.content.Intent(this, YtLoginActivity.class));
            }
        });
        ytSignOutRow = inflateRow("Sign out of YouTube",
                "Removes your account from this app. Streaming still works.");
        ytSignOutRow.setOnClickListener(v ->
                new ThemedDialogBuilder(this)
                        .setTitle("Sign out of YouTube?")
                        .setPositiveButton("Sign out", (d, w) -> {
                            YtAuth.signOut(this);
                            updateYtRows();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
        updateYtRows();

        // ----- Cache -----
        addSection("Cache");
        final TextView cacheValue = addValueRow("Music cache",
                "Keeps songs you stream so they replay instantly, even offline. Bigger sizes apply "
                        + "the next time the app starts.", cacheLabel());
        ((View) cacheValue.getParent()).setOnClickListener(v -> {
            int current = Prefs.getInt(this, Prefs.MUSIC_CACHE_MB, com.hamo.styrixplayer.playback.MusicCache.DEFAULT_MB);
            int checked = 2;
            for (int i = 0; i < CACHE_MB.length; i++) if (CACHE_MB[i] == current) checked = i;
            new ThemedDialogBuilder(this)
                    .setTitle("Music cache size")
                    .setSingleChoiceItems(CACHE_LABELS, checked, (dialog, which) -> {
                        Prefs.setInt(this, Prefs.MUSIC_CACHE_MB, CACHE_MB[which]);
                        cacheValue.setText(cacheLabel());
                        dialog.dismiss();
                    })
                    .show();
        });
        final View clearRow = inflateRow("Clear cache", cacheUsage());
        clearRow.setOnClickListener(v ->
                new ThemedDialogBuilder(this)
                        .setTitle("Clear cached songs and covers?")
                        .setPositiveButton("Clear", (d, w) -> {
                            com.hamo.styrixplayer.playback.MusicCache.clear(this);
                            ArtLoader.clearArtCache(this);
                            ((TextView) clearRow.findViewById(R.id.row_summary)).setText(cacheUsage());
                        })
                        .setNegativeButton("Cancel", null)
                        .show());

        // ----- About -----
        addSection("Updates");
        addSwitch("Check for updates", "Looks for a new version when the app opens (at most twice a day).",
                Prefs.UPDATE_CHECK, true);
        View checkRow = inflateRow("Check now", "See if a newer version is available");
        checkRow.setOnClickListener(v -> com.hamo.styrixplayer.update.Updater.check(this, true));

        addSection("About");
        addInfoRow("StyrixPlayer", "Version " + com.hamo.styrixplayer.AppInfo.VERSION_NAME
                + " (build " + com.hamo.styrixplayer.AppInfo.VERSION_CODE + ")");
    }

    private static final int[] CACHE_MB = {0, 256, 512, 1024, 2048, 4096};
    private static final String[] CACHE_LABELS = {"Off", "256 MB", "512 MB", "1 GB", "2 GB", "4 GB"};

    private static final String[] AMBIENT_LABELS = {"Calm", "Normal", "Fast", "Live (with the video)"};

    private String ambientLabel() {
        return AMBIENT_LABELS[Prefs.videoAmbient(this)];
    }

    private String cacheLabel() {
        int cur = Prefs.getInt(this, Prefs.MUSIC_CACHE_MB, com.hamo.styrixplayer.playback.MusicCache.DEFAULT_MB);
        for (int i = 0; i < CACHE_MB.length; i++) if (CACHE_MB[i] == cur) return CACHE_LABELS[i];
        return cur + " MB";
    }

    private String cacheUsage() {
        long songs = com.hamo.styrixplayer.playback.MusicCache.usedBytes(this);
        long covers = ArtLoader.artCacheBytes(this);
        return "Using " + mb(songs) + " for songs and " + mb(covers) + " for covers";
    }

    private static String mb(long bytes) {
        return bytes < 1024 * 1024 ? "0 MB" : (bytes / (1024 * 1024)) + " MB";
    }

    private CharSequence[] styleItems() {
        CharSequence[] items = new CharSequence[AppTheme.MODE_LABELS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = AppTheme.MODE_LABELS[i] + "\n" + AppTheme.MODE_SUMMARIES[i];
        }
        return items;
    }

    private static String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }

    /** Swatches plus a hex field. Picking a color switches to Custom unless Ambient is on. */
    private void pickIcon(final TextView valueView) {
        final int cur = com.hamo.styrixplayer.AppIcons.current(this);
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), (int) (4 * d));
        final androidx.appcompat.app.AlertDialog[] box = new androidx.appcompat.app.AlertDialog[1];
        LinearLayout tiles = null;
        final int perRow = 3;
        final int count = com.hamo.styrixplayer.AppIcons.NAMES.length;
        for (int i = 0; i < count; i++) {
            final int idx = i;
            if (i % perRow == 0) {
                tiles = new LinearLayout(this);
                tiles.setOrientation(LinearLayout.HORIZONTAL);
                row.addView(tiles);
            }
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            tile.setPadding((int) (8 * d), (int) (8 * d), (int) (8 * d), (int) (8 * d));
            android.widget.ImageView img = new android.widget.ImageView(this);
            img.setImageResource(com.hamo.styrixplayer.AppIcons.PREVIEWS[i]);
            tile.addView(img, new LinearLayout.LayoutParams((int) (64 * d), (int) (64 * d)));
            TextView name = new TextView(this);
            name.setText(I18n.t(com.hamo.styrixplayer.AppIcons.NAMES[i]));
            name.setTextSize(13);
            name.setGravity(android.view.Gravity.CENTER);
            name.setPadding(0, (int) (6 * d), 0, 0);
            name.setTextColor(MaterialColors.getColor(this,
                    i == cur ? com.google.android.material.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorOnSurface, 0));
            if (i == cur) name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
            tile.addView(name);
            tile.setOnClickListener(v -> {
                if (idx != cur) {
                    com.hamo.styrixplayer.AppIcons.set(this, idx);
                    valueView.setText(com.hamo.styrixplayer.AppIcons.NAMES[idx]);
                    android.widget.Toast.makeText(this,
                            I18n.t("Icon changed. Your launcher may take a moment to update."),
                            android.widget.Toast.LENGTH_LONG).show();
                }
                if (box[0] != null) box[0].dismiss();
            });
            tiles.addView(tile, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        for (int k = count % perRow; k != 0 && k < perRow; k++) {
            tiles.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        box[0] = new ThemedDialogBuilder(this).setTitle("App icon").setView(row).show();
    }

    private void pickColor(final TextView valueView) {
        final float d = getResources().getDisplayMetrics().density;
        final int[] chosen = {AppTheme.customColor(this)};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        box.setPadding(pad, (int) (8 * d), pad, 0);

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setText(hex(chosen[0]));
        input.setHint("#RRGGBB");

        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(6);
        for (final int c : AppTheme.SWATCHES) {
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(c);
            dot.setBackground(g);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = (int) (42 * d);
            lp.height = (int) (42 * d);
            lp.setMargins((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
            dot.setOnClickListener(v -> {
                chosen[0] = c;
                input.setText(hex(c));
            });
            grid.addView(dot, lp);
        }
        box.addView(grid);
        box.addView(input);

        new ThemedDialogBuilder(this)
                .setTitle("Custom color")
                .setView(box)
                .setPositiveButton("Apply", (dialog, w) -> {
                    int color;
                    try {
                        String t = input.getText().toString().trim();
                        color = android.graphics.Color.parseColor(t.startsWith("#") ? t : "#" + t);
                    } catch (IllegalArgumentException e) {
                        Toast.makeText(this, "Use a color like #7C4DFF",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Prefs.setInt(this, Prefs.CUSTOM_COLOR, color | 0xFF000000);
                    if (AppTheme.mode(this) != AppTheme.MODE_AMBIENT) {
                        Prefs.setInt(this, Prefs.COLOR_MODE, AppTheme.MODE_CUSTOM);
                    }
                    valueView.setText(hex(color));
                    recreateForTheme();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String ytLabel() {
        return YtAuth.isSignedIn(this) ? "Signed in" : "Signed out";
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ytValue != null) updateYtRows();
    }

    private void updateYtRows() {
        ytValue.setText(ytLabel());
        ytSignOutRow.setVisibility(YtAuth.isSignedIn(this) ? View.VISIBLE : View.GONE);
    }

    private String minLenLabel() {
        int current = Prefs.getInt(this, Prefs.MIN_LEN, 30);
        for (int i = 0; i < MIN_LEN_VALUES.length; i++) {
            if (MIN_LEN_VALUES[i] == current) return i == 0 ? "Off" : MIN_LEN_LABELS[i];
        }
        return current + " sec";
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v != null ? v : "";
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---------- row builders ----------

    /** The card the current section's rows go into. */
    private LinearLayout card;

    private static int sectionIcon(String text) {
        switch (text) {
            case "Appearance": return R.drawable.ic_tune;
            case "Library": return R.drawable.ic_music_note;
            case "Playback": return R.drawable.ic_play;
            case "YouTube": return R.drawable.ic_youtube;
            case "Cache": return R.drawable.ic_download;
            case "Updates": return R.drawable.ic_upload;
            default: return R.drawable.ic_check;
        }
    }

    /** A bold heading with a colored icon, then a rounded card that holds the section's rows. */
    private void addSection(String text) {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setBackgroundResource(R.drawable.bg_section_icon);
        icon.setImageResource(sectionIcon(text));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(
                icon, com.google.android.material.R.attr.colorOnPrimaryContainer)));
        int ip = (int) (8 * d);
        icon.setPadding(ip, ip, ip, ip);
        head.addView(icon, new LinearLayout.LayoutParams((int) (36 * d), (int) (36 * d)));

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        t.setTextColor(MaterialColors.getColor(t, com.google.android.material.R.attr.colorOnSurface));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginStart((int) (12 * d));
        head.addView(t, tl);

        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.setMargins((int) (6 * d), (int) (24 * d), 0, (int) (10 * d));
        container.addView(head, hl);

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_settings_card);
        card.setClipToOutline(true);
        card.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        container.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View inflateRow(String title, String summary) {
        LinearLayout into = card != null ? card : container;
        View row = LayoutInflater.from(this).inflate(R.layout.row_setting, into, false);
        ((TextView) row.findViewById(R.id.row_title)).setText(title);
        ((TextView) row.findViewById(R.id.row_summary)).setText(summary);
        into.addView(row);
        return row;
    }

    private TextView addValueRow(String title, String summary, String value) {
        View row = inflateRow(title, summary);
        TextView v = row.findViewById(R.id.row_value);
        v.setText(value);
        v.setVisibility(View.VISIBLE);
        return v;
    }

    private void addSwitch(String title, String summary, String key, boolean def) {
        View row = inflateRow(title, summary);
        MaterialSwitch sw = row.findViewById(R.id.row_switch);
        sw.setVisibility(View.VISIBLE);
        AppTheme.tintSwitch(sw);
        sw.setChecked(Prefs.getBool(this, key, def));
        sw.setOnCheckedChangeListener((b, checked) -> Prefs.setBool(this, key, checked));
        row.setOnClickListener(v -> sw.toggle());
    }

    /** A switch whose position is the opposite of the stored value (stored: "keep pitch"). */
    private void addInvertedSwitch(String title, String summary, String key, boolean storedDefault) {
        View row = inflateRow(title, summary);
        MaterialSwitch sw = row.findViewById(R.id.row_switch);
        sw.setVisibility(View.VISIBLE);
        AppTheme.tintSwitch(sw);
        sw.setChecked(!Prefs.getBool(this, key, storedDefault));
        sw.setOnCheckedChangeListener((b, checked) -> Prefs.setBool(this, key, !checked));
        row.setOnClickListener(v -> sw.toggle());
    }

    private void addInfoRow(String title, String summary) {
        View row = inflateRow(title, summary);
        row.setBackground(null);
    }
}
