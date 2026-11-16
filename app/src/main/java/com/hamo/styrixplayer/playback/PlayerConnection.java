package com.hamo.styrixplayer.playback;

import android.content.ComponentName;
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

/** Small wrapper so screens don't repeat the MediaController connect/release code. */
public final class PlayerConnection {

    public interface OnReady {
        void ready(MediaController controller);
    }

    private final Context context;
    private ListenableFuture<MediaController> future;
    @Nullable private MediaController controller;

    public PlayerConnection(Context context) {
        this.context = context;
    }

    @Nullable
    public MediaController get() {
        return controller;
    }

    public void connect(OnReady callback) {
        SessionToken token = new SessionToken(context, new ComponentName(context, PlaybackService.class));
        final ListenableFuture<MediaController> f = new MediaController.Builder(context, token).buildAsync();
        future = f;
        f.addListener(() -> {
            try {
                controller = f.get();
                callback.ready(controller);
            } catch (Exception e) {
                controller = null;
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public void release() {
        controller = null;
        if (future != null) {
            MediaController.releaseFuture(future);
            future = null;
        }
    }
}
