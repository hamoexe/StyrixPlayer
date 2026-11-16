package com.hamo.styrixplayer;

import android.app.Application;

import com.hamo.styrixplayer.data.Prefs;

public class StyrixApp extends Application {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(I18n.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CrashReport.install(this);
        Prefs.applyNightMode(this);
        // Colors (wallpaper / custom / ambient) are applied per screen by BaseActivity via AppTheme.
    }
}
