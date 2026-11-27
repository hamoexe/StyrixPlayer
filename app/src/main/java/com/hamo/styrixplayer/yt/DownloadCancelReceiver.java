package com.hamo.styrixplayer.yt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The "Cancel" button in a download's notification. */
public final class DownloadCancelReceiver extends BroadcastReceiver {
    public static final String EXTRA_KEY = "key";

    @Override
    public void onReceive(Context context, Intent intent) {
        String key = intent == null ? null : intent.getStringExtra(EXTRA_KEY);
        if (key != null) YtDownloads.cancel(key);
    }
}
