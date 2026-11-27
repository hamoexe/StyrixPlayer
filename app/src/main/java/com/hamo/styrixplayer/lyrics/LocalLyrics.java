package com.hamo.styrixplayer.lyrics;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.hamo.styrixplayer.data.Prefs;
import com.hamo.styrixplayer.data.Song;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Lyrics kept as a file next to the song: myMusic.m4a + myMusic.lrc (same name, other extension,
 * same folder). Off by default; reading files outside the media library needs "all files access".
 */
public final class LocalLyrics {
    public static final String PREF = "local_lyrics";

    private LocalLyrics() {}

    public static final class Result {
        @Nullable public String text;
        /** Why there is no text (shown in the Source tab). */
        @Nullable public String note;
        public boolean denied;
    }

    public static boolean enabled(Context c) {
        return Prefs.getBool(c, PREF, false);
    }

    public static boolean canRead() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    /** Opens the system page where the user allows all-files access for this app. */
    public static void askAccess(Activity a) {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                // no settings page: nothing more to do
            }
        }
    }

    public static Result find(Context c, @Nullable Song s) {
        Result r = new Result();
        if (!enabled(c)) { r.note = "Off in Settings"; return r; }
        if (s == null || s.id <= 0) { r.note = "Only for songs on the phone"; return r; }
        if (!canRead()) { r.denied = true; r.note = "Needs all-files access"; return r; }
        String path = null;
        try (Cursor q = c.getContentResolver().query(s.uri, new String[]{MediaStore.MediaColumns.DATA},
                null, null, null)) {
            if (q != null && q.moveToFirst()) path = q.getString(0);
        } catch (Exception ignored) {
            // falls through to "can't find the file"
        }
        if (path == null) { r.note = "Can't find the song's file"; return r; }
        File audio = new File(path);
        File dir = audio.getParentFile();
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File[] list = dir == null ? null : dir.listFiles();
        if (list == null) { r.denied = true; r.note = "Needs all-files access"; return r; }
        File hit = null;
        int best = 9;
        for (File f : list) {
            String n = f.getName();
            int d = n.lastIndexOf('.');
            if (d <= 0 || !n.substring(0, d).equalsIgnoreCase(base)) continue;
            String ext = n.substring(d + 1).toLowerCase(Locale.ROOT);
            int rank = ext.equals("lrc") ? 0 : ext.equals("txt") ? 1 : 9;
            if (rank < best) { best = rank; hit = f; }
        }
        if (hit == null) { r.note = "No .lrc file with this name"; return r; }
        try (InputStream in = new FileInputStream(hit)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 2_000_000) out.write(buf, 0, n);
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            text = text.trim();
            if (text.isEmpty()) { r.note = "The lyrics file is empty"; return r; }
            r.text = text;
        } catch (Exception e) {
            r.note = "Couldn't read the lyrics file";
        }
        return r;
    }
}
