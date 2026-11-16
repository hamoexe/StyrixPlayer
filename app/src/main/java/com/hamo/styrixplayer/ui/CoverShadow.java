package com.hamo.styrixplayer.ui;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * A soft drop shadow for the player cover: a blurred rounded square, drawn once and stretched
 * behind the cover. (Elevation shadows were clipped into a box by the parents; this one is just
 * a picture, so it can't be.)
 */
final class CoverShadow {
    private static Bitmap cached;

    private CoverShadow() {}

    static Bitmap bitmap() {
        if (cached == null) {
            final int size = 320;
            final float inset = 21f;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.argb(170, 0, 0, 0));
            p.setMaskFilter(new BlurMaskFilter(17f, BlurMaskFilter.Blur.NORMAL));
            c.drawRoundRect(new RectF(inset, inset, size - inset, size - inset), 22f, 22f, p);
            cached = b;
        }
        return cached;
    }
}
