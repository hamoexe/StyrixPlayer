package com.hamo.styrixplayer;

/**
 * The version shown in Settings > About. The build.gradle in the user's project is not part of the
 * source zip, so the number lives here: bump it (and PROJECT_INFO.md) with every change.
 * Keep build.gradle's versionName / versionCode in step if you want the APK to match.
 */
public final class AppInfo {
    public static final String VERSION_NAME = "1.22.2";
    public static final int VERSION_CODE = 79;

    private AppInfo() {}
}
