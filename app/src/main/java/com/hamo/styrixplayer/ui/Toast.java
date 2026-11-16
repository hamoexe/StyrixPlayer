package com.hamo.styrixplayer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.hamo.styrixplayer.R;

/**
 * Drop-in replacement for android.widget.Toast. The system toast always shows the app icon and
 * ignores the app's colors, so messages are shown as a small themed bar above the bottom bars.
 * Outside an activity (services) it falls back to the system toast.
 */
public final class Toast {

    public static final int LENGTH_SHORT = android.widget.Toast.LENGTH_SHORT;
    public static final int LENGTH_LONG = android.widget.Toast.LENGTH_LONG;

    private final Context context;
    private final CharSequence text;
    private final int duration;

    private Toast(Context c, CharSequence t, int d) {
        context = c;
        text = com.hamo.styrixplayer.I18n.tc(t);
        duration = d;
    }

    public static Toast makeText(Context c, CharSequence t, int d) {
        return new Toast(c, t, d);
    }

    public static Toast makeText(Context c, int resId, int d) {
        return new Toast(c, c.getText(resId), d);
    }

    public void show() {
        new Handler(Looper.getMainLooper()).post(this::showNow);
    }

    private void showNow() {
        Activity a = activityOf(context);
        View root = a == null || a.isFinishing() || a.isDestroyed()
                ? null : a.findViewById(android.R.id.content);
        if (root == null) {
            Toast.makeText(context.getApplicationContext(), text,
                    duration == LENGTH_LONG ? android.widget.Toast.LENGTH_LONG
                            : android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Snackbar bar = Snackbar.make(root, text,
                duration == LENGTH_LONG ? Snackbar.LENGTH_LONG : Snackbar.LENGTH_SHORT);
        AmbientTheme.Palette p = AmbientTheme.active(a) ? AmbientTheme.current() : null;
        int bg = p != null ? p.high | 0xFF000000
                : MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int fg = p != null ? p.text
                : MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface);
        bar.setBackgroundTint(bg);
        bar.setTextColor(fg);
        bar.setAnimationMode(Snackbar.ANIMATION_MODE_FADE);
        // sit above the mini player / bottom bar when this screen has them
        View ref = a.findViewById(R.id.mini_player);
        if (ref == null || ref.getVisibility() != View.VISIBLE) ref = a.findViewById(R.id.bottom_nav);
        if (ref != null && ref.getVisibility() == View.VISIBLE && ref.getHeight() > 0) {
            int[] l = new int[2];
            int[] r = new int[2];
            ref.getLocationOnScreen(l);
            root.getLocationOnScreen(r);
            int lift = root.getHeight() - (l[1] - r[1]) + (int) (8 * a.getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams lp = bar.getView().getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams && lift > 0) {
                ((ViewGroup.MarginLayoutParams) lp).bottomMargin += lift;
            }
        }
        bar.show();
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
