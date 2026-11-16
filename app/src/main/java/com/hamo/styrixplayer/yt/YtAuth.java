package com.hamo.styrixplayer.yt;

import android.content.Context;
import android.webkit.CookieManager;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * Keeps the YouTube Music session. Signing in happens in a WebView (YtLoginActivity); this class
 * stores the resulting cookie header and builds the SAPISIDHASH header YouTube's web API expects.
 * The cookies live in the app's no-backup folder so they never end up in a cloud backup.
 */
public final class YtAuth {

    public static final String ORIGIN = "https://music.youtube.com";
    private static final String FILE = "yt_session.txt";

    private static volatile String cached;
    private static volatile boolean loaded;

    private YtAuth() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getNoBackupFilesDir(), FILE);
    }

    @Nullable
    public static String cookies(Context c) {
        if (!loaded) {
            synchronized (YtAuth.class) {
                if (!loaded) {
                    try {
                        File f = file(c);
                        cached = f.exists()
                                ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim()
                                : null;
                    } catch (Exception e) {
                        cached = null;
                    }
                    loaded = true;
                }
            }
        }
        return cached == null || cached.isEmpty() ? null : cached;
    }

    public static boolean isSignedIn(Context c) {
        String ck = cookies(c);
        return ck != null && sapisid(ck) != null;
    }

    /** True when this cookie header carries a finished Google sign-in. */
    public static boolean looksSignedIn(@Nullable String cookieHeader) {
        return cookieHeader != null && sapisid(cookieHeader) != null;
    }

    public static void save(Context c, String cookieHeader) {
        try (FileOutputStream out = new FileOutputStream(file(c))) {
            out.write(cookieHeader.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        cached = cookieHeader;
        loaded = true;
    }

    // ---- account name + photo, shown in the YouTube page header ----

    private static android.content.SharedPreferences acct(Context c) {
        return c.getApplicationContext().getSharedPreferences("yt_account", Context.MODE_PRIVATE);
    }

    public static String accountName(Context c) { return acct(c).getString("name", ""); }

    public static String accountPhoto(Context c) { return acct(c).getString("photo", ""); }

    public static void saveAccount(Context c, String name, String photo) {
        acct(c).edit().putString("name", name).putString("photo", photo).apply();
    }

    public static void signOut(Context c) {
        acct(c).edit().clear().apply();
        file(c).delete();
        cached = null;
        loaded = true;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.removeAllCookies(null);
            cm.flush();
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static String cookie(String header, String name) {
        for (String part : header.split(";")) {
            String p = part.trim();
            if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
        }
        return null;
    }

    @Nullable
    private static String sapisid(String header) {
        String v = cookie(header, "SAPISID");
        if (v == null) v = cookie(header, "__Secure-3PAPISID");
        return v;
    }

    /** "SAPISIDHASH <time>_<sha1>" – proves to Google that the request comes from the signed-in session. */
    @Nullable
    public static String authorization(String cookieHeader) {
        String sid = sapisid(cookieHeader);
        if (sid == null) return null;
        try {
            long ts = System.currentTimeMillis() / 1000L;
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((ts + " " + sid + " " + ORIGIN).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return "SAPISIDHASH " + ts + "_" + sb;
        } catch (Exception e) {
            return null;
        }
    }
}
