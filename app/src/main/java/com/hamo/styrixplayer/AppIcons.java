package com.hamo.styrixplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Alternate launcher icons. Each icon is an {@code <activity-alias>} in the manifest;
 * exactly one is enabled. To add an icon: add its mipmap + alias, then one line in each array.
 */
public final class AppIcons {
    private AppIcons() {}

    /** Alias class names, relative to the app package. Index 0 is the default. */
    public static final String[] ALIASES = {".LauncherDefault", ".LauncherKurdistan", ".LauncherChristmas", ".LauncherChrome"};
    public static final String[] NAMES = {"Default", "Kurdistan", "Christmas", "Chrome"};
    public static final int[] PREVIEWS = {R.mipmap.ic_launcher, R.mipmap.ic_launcher_kurdistan,
            R.mipmap.ic_launcher_xmas, R.mipmap.ic_launcher_chrome};

    /** Aliases live in the code namespace, which can differ from the applicationId. */
    private static final String NS = "com.hamo.styrixplayer";

    private static ComponentName comp(Context c, int i) {
        return new ComponentName(c.getPackageName(), NS + ALIASES[i]);
    }

    /** Index of the enabled icon (0 if none is explicitly enabled). */
    public static int current(Context c) {
        PackageManager pm = c.getPackageManager();
        for (int i = ALIASES.length - 1; i >= 0; i--) {
            int s = pm.getComponentEnabledSetting(comp(c, i));
            if (s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i;
        }
        return 0;
    }

    /** Enables icon {@code index} and disables the rest (new one first so the app never has no launcher entry). */
    public static void set(Context c, int index) {
        PackageManager pm = c.getPackageManager();
        pm.setComponentEnabledSetting(comp(c, index),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        for (int i = 0; i < ALIASES.length; i++) {
            if (i == index) continue;
            pm.setComponentEnabledSetting(comp(c, i),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }
}
