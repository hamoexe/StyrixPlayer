package com.hamo.styrixplayer.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageView;

/** Gives every playlist its own color so they are easy to tell apart at a glance. */
public final class PlaylistStyle {
    private static final int[] COLORS = {
            0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF7986CB, 0xFF4FC3F7,
            0xFF4DB6AC, 0xFF81C784, 0xFFFFB74D, 0xFFFF8A65, 0xFFA1887F};

    public static final int NEUTRAL = 0xFF5C5C6E;

    private PlaylistStyle() {}

    public static int color(long playlistId) {
        return COLORS[(int) (Math.abs(playlistId) % COLORS.length)];
    }

    /** Rounded colored tile behind a white icon. */
    public static void applyColor(ImageView iv, int color) {
        // the rainbow clashed with the cover-based colors: tiles use the theme's container colors
        boolean neutral = color == NEUTRAL;
        int fill = com.google.android.material.color.MaterialColors.getColor(iv, neutral
                ? com.google.android.material.R.attr.colorSurfaceContainerHighest
                : com.google.android.material.R.attr.colorPrimaryContainer);
        int fg = com.google.android.material.color.MaterialColors.getColor(iv, neutral
                ? com.google.android.material.R.attr.colorOnSurfaceVariant
                : com.google.android.material.R.attr.colorOnPrimaryContainer);
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(14 * iv.getResources().getDisplayMetrics().density);
        iv.setBackground(d);
        iv.setImageTintList(ColorStateList.valueOf(fg));
    }
}
