package com.hamo.styrixplayer.ui;

import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/** Tiny animation helpers used across the app. */
public final class Anim {
    private Anim() {}

    /** Button squishes on press and springs back on release. */
    public static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(85).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(new OvershootInterpolator(2.2f)).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /** Quick "pop" used when toggling a heart or swapping an icon. */
    public static void pop(View v) {
        v.animate().cancel();
        v.setScaleX(0.72f);
        v.setScaleY(0.72f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(300)
                .setInterpolator(new OvershootInterpolator(2.5f)).start();
    }
}
