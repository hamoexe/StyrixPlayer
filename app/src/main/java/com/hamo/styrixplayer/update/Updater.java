package com.hamo.styrixplayer.update;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.hamo.styrixplayer.AppInfo;
import com.hamo.styrixplayer.data.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app updater. Looks at the newest GitHub release of the project, and when it is newer than
 * {@link AppInfo#VERSION_NAME} shows a dialog with the release notes. "Update" downloads the .apk
 * (system download notification with progress) and opens the installer when it is done.
 *
 * Release rules: tag = "v1.18.0" (or "1.18.0"), and the release has an .apk file attached.
 */
public final class Updater {
    public static final String REPO = "hamoexe/StyrixPlayer";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long AUTO_EVERY_MS = 12L * 60 * 60 * 1000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater() {}

    private static final class Release {
        String version;
        String notes;
        String apkUrl;
        long apkSize;
        String pageUrl;
    }

    /** Called when the app opens: at most one check every 12 hours, silent when up to date. */
    public static void autoCheck(Activity a) {
        if (!Prefs.getBool(a, Prefs.UPDATE_CHECK, true)) return;
        long last = Prefs.getLong(a, Prefs.UPDATE_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < AUTO_EVERY_MS) return;
        check(a, false);
    }

    /** @param manual true from the Settings row: also tells the user when there is nothing new. */
    public static void check(Activity a, boolean manual) {
        if (manual) Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Release r = null;
            boolean failed = false;
            try {
                r = fetch();
            } catch (Exception e) {
                failed = true;
            }
            final Release rel = r;
            final boolean fail = failed;
            MAIN.post(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (!fail) Prefs.setLong(a, Prefs.UPDATE_LAST_CHECK, System.currentTimeMillis());
                if (fail) {
                    if (manual) Toast.makeText(a, "Couldn't reach GitHub. Check your connection.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (rel == null || compare(rel.version, AppInfo.VERSION_NAME) <= 0) {
                    if (manual) Toast.makeText(a, "You're on the latest version (" + AppInfo.VERSION_NAME + ")",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (!manual && rel.version.equals(Prefs.getString(a, Prefs.UPDATE_SKIPPED, ""))) return;
                showDialog(a, rel);
            });
        }, "styrix-update-check").start();
    }

    private static Release fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "StyrixPlayer/" + AppInfo.VERSION_NAME);
        try {
            int code = c.getResponseCode();
            if (code == 404) return null; // no releases yet
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream();
                 BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
            JSONObject o = new JSONObject(sb.toString());
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null;
            Release r = new Release();
            String tag = o.optString("tag_name", "");
            r.version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            r.notes = o.optString("body", "").trim();
            r.pageUrl = o.optString("html_url", "");
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject as = assets.getJSONObject(i);
                    if (as.optString("name", "").toLowerCase().endsWith(".apk")) {
                        r.apkUrl = as.optString("browser_download_url", "");
                        r.apkSize = as.optLong("size", 0);
                        break;
                    }
                }
            }
            return r.version.isEmpty() ? null : r;
        } finally {
            c.disconnect();
        }
    }

    /** Compares "1.17.2" style versions numerically. Returns >0 when a is newer. */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+");
        String[] y = b.split("[^0-9]+");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int p = i < x.length ? num(x[i]) : 0;
            int q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return s.isEmpty() ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------- dialog ----------

    /** A ScrollView that never grows past a maximum height, so long release notes scroll inside their card. */
    private static final class MaxHeightScroll extends android.widget.ScrollView {
        private final int maxPx;

        MaxHeightScroll(Context c, int maxPx) {
            super(c);
            this.maxPx = maxPx;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, android.view.View.MeasureSpec.makeMeasureSpec(maxPx,
                    android.view.View.MeasureSpec.AT_MOST));
        }
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** Release notes from GitHub are markdown: turn them into plain bullets. */
    private static String cleanNotes(String raw) {
        String s = raw.replace("\r", "")
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "\u2022 ")
                .replace("**", "").replace("__", "").replace("`", "");
        s = s.replaceAll("\\n{3,}", "\n\n").trim();
        if (s.length() > 1500) s = s.substring(0, 1500).trim() + "\u2026";
        return s;
    }

    private static void showDialog(Activity a, Release r) {
        // colors: the live ambient palette when it is on, the Material theme otherwise
        final int surface, text, sub, accent, onAccent;
        com.hamo.styrixplayer.ui.AmbientTheme.Palette p = com.hamo.styrixplayer.ui.AmbientTheme.active(a)
                ? com.hamo.styrixplayer.ui.AmbientTheme.current() : null;
        if (p != null) {
            surface = p.high | 0xFF000000;
            text = p.text;
            sub = p.onVariant;
            accent = p.accent;
            onAccent = p.onAccent;
        } else {
            surface = com.google.android.material.color.MaterialColors.getColor(a,
                    com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2B2B2B);
            text = com.google.android.material.color.MaterialColors.getColor(a,
                    com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);
            sub = com.google.android.material.color.MaterialColors.getColor(a,
                    com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFBBBBBB);
            accent = com.google.android.material.color.MaterialColors.getColor(a,
                    com.google.android.material.R.attr.colorPrimary, 0xFF6750A4);
            onAccent = com.google.android.material.color.MaterialColors.getColor(a,
                    com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF);
        }

        final com.google.android.material.bottomsheet.BottomSheetDialog dlg =
                new com.google.android.material.bottomsheet.BottomSheetDialog(a);

        android.widget.LinearLayout root = new android.widget.LinearLayout(a);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dp(a, 24), dp(a, 12), dp(a, 24), dp(a, 20));

        // drag handle
        android.view.View handle = new android.view.View(a);
        android.graphics.drawable.GradientDrawable hg = new android.graphics.drawable.GradientDrawable();
        hg.setCornerRadius(dp(a, 3));
        hg.setColor(withAlpha(text, 0x40));
        handle.setBackground(hg);
        android.widget.LinearLayout.LayoutParams hp = new android.widget.LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
        hp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(a, 18);
        root.addView(handle, hp);

        // icon + title
        android.widget.LinearLayout head = new android.widget.LinearLayout(a);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.ImageView icon = new android.widget.ImageView(a);
        icon.setImageResource(com.hamo.styrixplayer.R.drawable.ic_download);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
        icon.setPadding(dp(a, 14), dp(a, 14), dp(a, 14), dp(a, 14));
        android.graphics.drawable.GradientDrawable ig = new android.graphics.drawable.GradientDrawable();
        ig.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ig.setColor(withAlpha(accent, 0x2E));
        icon.setBackground(ig);
        head.addView(icon, new android.widget.LinearLayout.LayoutParams(dp(a, 52), dp(a, 52)));

        android.widget.LinearLayout titles = new android.widget.LinearLayout(a);
        titles.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(a, 14);
        android.widget.TextView title = new android.widget.TextView(a);
        title.setText("Update available");
        title.setTextColor(text);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        titles.addView(title);
        android.widget.TextView subtitle = new android.widget.TextView(a);
        subtitle.setText("A new version of StyrixPlayer is ready");
        subtitle.setTextColor(sub);
        subtitle.setTextSize(14);
        titles.addView(subtitle);
        head.addView(titles, tp);
        root.addView(head);

        // version chips: current -> new, and the download size
        android.widget.LinearLayout chips = new android.widget.LinearLayout(a);
        chips.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        chips.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams cp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(a, 20);
        chips.addView(chip(a, AppInfo.VERSION_NAME, withAlpha(text, 0x18), sub));
        android.widget.TextView arrow = new android.widget.TextView(a);
        arrow.setText("  \u2192  ");
        arrow.setTextColor(sub);
        chips.addView(arrow);
        chips.addView(chip(a, r.version, accent, onAccent));
        if (r.apkSize > 0) {
            android.widget.TextView size = new android.widget.TextView(a);
            size.setText("   " + Math.max(1, Math.round(r.apkSize / (1024f * 1024f))) + " MB");
            size.setTextColor(sub);
            size.setTextSize(13);
            chips.addView(size);
        }
        root.addView(chips, cp);

        // release notes
        if (!r.notes.isEmpty()) {
            android.widget.TextView label = new android.widget.TextView(a);
            label.setText("What's new");
            label.setTextColor(accent);
            label.setTextSize(13);
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(a, 20);
            lp.bottomMargin = dp(a, 8);
            root.addView(label, lp);

            MaxHeightScroll scroll = new MaxHeightScroll(a, dp(a, 220));
            android.graphics.drawable.GradientDrawable ng = new android.graphics.drawable.GradientDrawable();
            ng.setCornerRadius(dp(a, 16));
            ng.setColor(withAlpha(text, 0x12));
            scroll.setBackground(ng);
            scroll.setClipToOutline(true);
            android.widget.TextView notes = new android.widget.TextView(a);
            notes.setText(cleanNotes(r.notes));
            notes.setTextColor(text);
            notes.setTextSize(14);
            notes.setLineSpacing(0, 1.15f);
            notes.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
            scroll.addView(notes);
            root.addView(scroll);
        }

        // download progress (hidden until Update is tapped)
        final com.google.android.material.progressindicator.LinearProgressIndicator bar =
                new com.google.android.material.progressindicator.LinearProgressIndicator(a);
        bar.setIndicatorColor(accent);
        bar.setTrackColor(withAlpha(text, 0x22));
        bar.setTrackCornerRadius(dp(a, 4));
        bar.setMax(100);
        bar.setVisibility(android.view.View.GONE);
        android.widget.LinearLayout.LayoutParams bp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(a, 20);
        root.addView(bar, bp);
        final android.widget.TextView status = new android.widget.TextView(a);
        status.setTextColor(sub);
        status.setTextSize(13);
        status.setVisibility(android.view.View.GONE);
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(a, 8);
        root.addView(status, sp);

        // buttons
        android.widget.LinearLayout buttons = new android.widget.LinearLayout(a);
        buttons.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams bl = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(a, 22);

        final com.google.android.material.button.MaterialButton go =
                new com.google.android.material.button.MaterialButton(a);
        boolean hasApk = r.apkUrl != null && !r.apkUrl.isEmpty();
        go.setText(hasApk ? "Update" : "Open release page");
        go.setTextSize(16);
        go.setCornerRadius(dp(a, 28));
        go.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        go.setTextColor(onAccent);
        buttons.addView(go, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 54)));

        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        final com.google.android.material.button.MaterialButton skip = textButton(a, "Skip this version", sub);
        final com.google.android.material.button.MaterialButton later = textButton(a, "Later", accent);
        row.addView(skip, new android.widget.LinearLayout.LayoutParams(0, dp(a, 48), 1f));
        row.addView(later, new android.widget.LinearLayout.LayoutParams(0, dp(a, 48), 1f));
        android.widget.LinearLayout.LayoutParams rp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(a, 6);
        buttons.addView(row, rp);
        root.addView(buttons, bl);

        later.setOnClickListener(v -> dlg.dismiss());
        skip.setOnClickListener(v -> {
            Prefs.setString(a, Prefs.UPDATE_SKIPPED, r.version);
            dlg.dismiss();
        });
        go.setOnClickListener(v -> {
            if (!hasApk) {
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl)));
                dlg.dismiss();
                return;
            }
            if (!canInstall(a)) return;
            long id = download(a, r);
            if (id < 0) return;
            go.setEnabled(false);
            go.setText("Downloading\u2026");
            skip.setEnabled(false);
            later.setText("Hide");
            bar.setVisibility(android.view.View.VISIBLE);
            status.setVisibility(android.view.View.VISIBLE);
            bar.setIndeterminate(true);
            status.setText("Starting\u2026");
            watchProgress(a, id, dlg, bar, status, go);
        });

        dlg.setContentView(root);
        android.widget.FrameLayout sheet = dlg.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable();
            float rad = dp(a, 28);
            sg.setCornerRadii(new float[]{rad, rad, rad, rad, 0, 0, 0, 0});
            sg.setColor(surface);
            sheet.setBackground(sg);
        }
        dlg.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        dlg.getBehavior().setSkipCollapsed(true);
        dlg.show();
    }

    private static android.widget.TextView chip(Context c, String text, int bg, int fg) {
        android.widget.TextView t = new android.widget.TextView(c);
        t.setText(text);
        t.setTextColor(fg);
        t.setTextSize(14);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(c, 20));
        g.setColor(bg);
        t.setBackground(g);
        return t;
    }

    private static com.google.android.material.button.MaterialButton textButton(Context c, String label, int color) {
        com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(
                c, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setTextColor(color);
        b.setAllCaps(false);
        b.setRippleColor(android.content.res.ColorStateList.valueOf(withAlpha(color, 0x30)));
        return b;
    }

    /** Android 8+: the user has to allow installs from this app once. Returns false (and opens the screen) when not allowed. */
    private static boolean canInstall(Activity a) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow \"Install unknown apps\" for StyrixPlayer, then tap Update again",
                    Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return false;
        }
        return true;
    }

    /** Follows the download and fills the bar. The install itself is started by the receiver in {@link #download}. */
    private static void watchProgress(Activity a, long id,
                                      com.google.android.material.bottomsheet.BottomSheetDialog dlg,
                                      com.google.android.material.progressindicator.LinearProgressIndicator bar,
                                      android.widget.TextView status,
                                      com.google.android.material.button.MaterialButton go) {
        final DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (!dlg.isShowing()) return;
            try (android.database.Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
                if (c == null || !c.moveToFirst()) {
                    MAIN.postDelayed(tick[0], 500);
                    return;
                }
                int st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                if (st == DownloadManager.STATUS_SUCCESSFUL) {
                    bar.setIndeterminate(false);
                    bar.setProgress(100);
                    status.setText("Installing\u2026");
                    go.setText("Installing\u2026");
                    MAIN.postDelayed(dlg::dismiss, 1500);
                    return;
                }
                if (st == DownloadManager.STATUS_FAILED) {
                    bar.setVisibility(android.view.View.GONE);
                    status.setText("Update download failed");
                    go.setEnabled(true);
                    go.setText("Try again");
                    return;
                }
                if (total > 0) {
                    int pct = (int) (done * 100 / total);
                    bar.setIndeterminate(false);
                    bar.setProgress(pct);
                    status.setText("Downloading\u2026 " + pct + "%  ("
                            + done / (1024 * 1024) + " / " + total / (1024 * 1024) + " MB)");
                }
            }
            MAIN.postDelayed(tick[0], 400);
        };
        MAIN.post(tick[0]);
    }

    /** Starts the download and registers the install step. Returns the DownloadManager id, or -1. */
    private static long download(Activity a, Release r) {
        final Context app = a.getApplicationContext();
        String name = "StyrixPlayer-" + r.version + ".apk";
        File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            Toast.makeText(a, "No storage available for the download", Toast.LENGTH_LONG).show();
            return -1;
        }
        File out = new File(dir, name);
        if (out.exists()) out.delete();

        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("StyrixPlayer " + r.version)
                .setDescription("Downloading update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(out));
        final long id = dm.enqueue(req);

        BroadcastReceiver rec = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                try {
                    app.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
                try (android.database.Cursor cur = dm.query(q)) {
                    if (cur != null && cur.moveToFirst()
                            && cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                            == DownloadManager.STATUS_SUCCESSFUL) {
                        install(app, out);
                    } else {
                        Toast.makeText(app, "Update download failed", Toast.LENGTH_LONG).show();
                    }
                }
            }
        };
        ContextCompat.registerReceiver(app, rec,
                new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED);
        return id;
    }

    private static void install(Context c, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".updates", apk);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "Downloaded. Open it from your Downloads to install.", Toast.LENGTH_LONG).show();
        }
    }
}
