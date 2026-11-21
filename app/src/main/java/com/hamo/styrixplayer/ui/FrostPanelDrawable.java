package com.hamo.styrixplayer.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Frosted-glass background for a panel that floats over the cover: it draws the (soft-blurred) cover
 * exactly where the panel sits over the real cover, then a tint on top. So the picture behind
 * shows through as a blur, and the text on the panel stays readable.
 */
final class FrostPanelDrawable extends Drawable {
    private final View panel;
    private final View cover;
    private final float radius;
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rf = new RectF();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    @Nullable private Bitmap bmp;
    private int tint;
    private int fallback;

    FrostPanelDrawable(View panel, View cover, float radiusPx) {
        this.panel = panel;
        this.cover = cover;
        this.radius = radiusPx;
    }

    void setBitmap(@Nullable Bitmap b) {
        bmp = b;
        invalidateSelf();
    }

    /** @param tint laid over the blur, @param fallback solid color used when there is no cover. */
    void setColors(int tint, int fallback) {
        this.tint = tint;
        this.fallback = fallback;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        rf.set(b);
        clip.reset();
        clip.addRoundRect(rf, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(clip);
        boolean blur = false;
        if (bmp != null && !bmp.isRecycled() && cover != null && cover.getWidth() > 0 && cover.getHeight() > 0
                && cover.getVisibility() == View.VISIBLE && cover.getAlpha() > 0.5f) {
            // the cover's rectangle, seen from the panel's own corner
            dst.set(cover.getLeft() - panel.getLeft(), cover.getTop() - panel.getTop(),
                    cover.getRight() - panel.getLeft(), cover.getBottom() - panel.getTop());
            // only blur the cover when it really sits behind the whole panel (not with lyrics or video on)
            blur = dst.left <= 0 && dst.top <= 0 && dst.right >= b.width() && dst.bottom >= b.height();
        }
        if (blur) {
            int bw = bmp.getWidth(), bh = bmp.getHeight();
            float dw = dst.width(), dh = dst.height();
            if (dw >= dh) {                       // centerCrop: wide frame shows the middle band
                int sh = Math.round(bw * dh / dw);
                src.set(0, (bh - sh) / 2, bw, (bh - sh) / 2 + sh);
            } else {
                int sw = Math.round(bh * dw / dh);
                src.set((bw - sw) / 2, 0, (bw - sw) / 2 + sw, bh);
            }
            canvas.drawBitmap(bmp, src, dst, bmpPaint);
            tintPaint.setColor(tint);
        } else {
            tintPaint.setColor(fallback);
        }
        canvas.drawRect(rf, tintPaint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        bmpPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        bmpPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
