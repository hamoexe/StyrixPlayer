package com.hamo.styrixplayer.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * A 3x3 grid of colors stretched smoothly over the whole area: every corner (and the edges and the
 * middle) has its own color and they melt into each other. Used behind the player to continue the
 * video's picture corner to corner.
 */
final class CornerGlow extends Drawable {
    private static final int W = 18, H = 40;
    private static final float SIGMA = 0.36f;
    /** Weight of each of the 9 colors at every pixel (Gaussian, so there are no creases or bands). */
    private static final float[][] WT = new float[W * H][9];

    static {
        float k = 1f / (2 * SIGMA * SIGMA);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float u = x / (float) (W - 1), v = y / (float) (H - 1);
                float sum = 0;
                for (int i = 0; i < 9; i++) {
                    float dx = u - (i % 3) * 0.5f, dy = v - (i / 3) * 0.5f;
                    float w = (float) Math.exp(-(dx * dx + dy * dy) * k);
                    WT[y * W + x][i] = w;
                    sum += w;
                }
                for (int i = 0; i < 9; i++) WT[y * W + x][i] /= sum;
            }
        }
    }

    private final Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final int[] px = new int[W * H];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final int[] cur = new int[9];

    void set(int[] colors) {
        System.arraycopy(colors, 0, cur, 0, 9);
        for (int p = 0; p < W * H; p++) {
            float r = 0, g = 0, b = 0;
            float[] w = WT[p];
            for (int i = 0; i < 9; i++) {
                int c = colors[i];
                r += w[i] * ((c >> 16) & 0xFF);
                g += w[i] * ((c >> 8) & 0xFF);
                b += w[i] * (c & 0xFF);
            }
            px[p] = 0xFF000000 | ((int) (r + 0.5f) << 16) | ((int) (g + 0.5f) << 8) | (int) (b + 0.5f);
        }
        bmp.setPixels(px, 0, W, 0, 0, W, H);
        invalidateSelf();
    }

    /** The mesh color at a spot of the screen (u, v in 0..1), same blend as what is drawn. */
    int colorAt(float u, float v) {
        float k = 1f / (2 * SIGMA * SIGMA), sum = 0, r = 0, g = 0, b = 0;
        for (int i = 0; i < 9; i++) {
            float dx = u - (i % 3) * 0.5f, dy = v - (i / 3) * 0.5f;
            float w = (float) Math.exp(-(dx * dx + dy * dy) * k);
            sum += w;
            r += w * ((cur[i] >> 16) & 0xFF);
            g += w * ((cur[i] >> 8) & 0xFF);
            b += w * (cur[i] & 0xFF);
        }
        return 0xFF000000 | ((int) (r / sum + 0.5f) << 16) | ((int) (g / sum + 0.5f) << 8) | (int) (b / sum + 0.5f);
    }

    int[] colors() {
        return cur.clone();
    }

    @Override
    public void draw(Canvas c) {
        if (getBounds().isEmpty()) return;
        c.drawBitmap(bmp, null, getBounds(), paint);
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
    @Override public int getAlpha() { return paint.getAlpha(); }
    @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
