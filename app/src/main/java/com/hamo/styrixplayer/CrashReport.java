package com.hamo.styrixplayer;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.hamo.styrixplayer.ui.ThemedDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Writes the stack trace of a crash to a file and shows it the next time the app opens. */
public final class CrashReport {
    private static final String FILE = "last_crash.txt";

    private CrashReport() {}

    public static void install(final Context app) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                String text = "Thread: " + t.getName() + "\nVersion: " + AppInfo.VERSION_NAME
                        + " (" + AppInfo.VERSION_CODE + ")\n\n" + Log.getStackTraceString(e);
                try (FileOutputStream o = new FileOutputStream(new File(app.getFilesDir(), FILE))) {
                    o.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(t, e);
        });
    }

    @Nullable
    private static String read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /** If the app crashed last time, shows what happened with a Copy button. */
    public static void showIfAny(final android.app.Activity a) {
        final String text = read(a);
        if (text == null) return;
        new File(a.getFilesDir(), FILE).delete();
        String shown = text.length() > 3500 ? text.substring(0, 3500) + "\n…" : text;
        new ThemedDialogBuilder(a)
                .setTitle("The app crashed last time")
                .setMessage(shown)
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("crash", text));
                    Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
