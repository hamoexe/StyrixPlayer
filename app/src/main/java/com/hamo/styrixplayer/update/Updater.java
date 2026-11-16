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
import com.hamo.styrixplayer.ui.ThemedDialogBuilder;

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

    private static void showDialog(Activity a, Release r) {
        StringBuilder msg = new StringBuilder();
        msg.append("Version ").append(r.version).append(" is out (you have ")
                .append(AppInfo.VERSION_NAME).append(").");
        if (r.apkSize > 0) msg.append("  ").append(r.apkSize / (1024 * 1024)).append(" MB");
        if (!r.notes.isEmpty()) {
            String notes = r.notes.replaceAll("(?m)^#+\\s*", "").replace("**", "").replace("`", "");
            if (notes.length() > 1200) notes = notes.substring(0, 1200) + "…";
            msg.append("\n\n").append(notes);
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder b = new ThemedDialogBuilder(a)
                .setTitle("Update available")
                .setMessage(msg.toString())
                .setNegativeButton("Later", null)
                .setNeutralButton("Skip this version",
                        (d, w) -> Prefs.setString(a, Prefs.UPDATE_SKIPPED, r.version));
        if (r.apkUrl != null && !r.apkUrl.isEmpty()) {
            b.setPositiveButton("Update", (d, w) -> download(a, r));
        } else if (!r.pageUrl.isEmpty()) {
            b.setPositiveButton("Open release page", (d, w) ->
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))));
        }
        b.show();
    }

    private static void download(Activity a, Release r) {
        // Android 8+: the user has to allow installs from this app once.
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow \"Install unknown apps\" for StyrixPlayer, then tap Update again",
                    Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
            return;
        }
        final Context app = a.getApplicationContext();
        String name = "StyrixPlayer-" + r.version + ".apk";
        File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            Toast.makeText(a, "No storage available for the download", Toast.LENGTH_LONG).show();
            return;
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
        Toast.makeText(a, "Downloading update… (see the notification)", Toast.LENGTH_SHORT).show();

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
