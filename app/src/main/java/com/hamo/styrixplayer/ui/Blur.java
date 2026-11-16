package com.hamo.styrixplayer.ui;

import android.graphics.Bitmap;

/** Tiny, dependency-free blur: shrink the cover to 40x40 and box-blur it a few times. */
final class Blur {
    private Blur() {}

    static Bitmap make(Bitmap src) {
        final int w = 64, h = 64;
        // square center crop first, so a wide image is never squashed
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = side == src.getWidth() && side == src.getHeight() ? src
                : Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap s = Bitmap.createScaledBitmap(sq, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < 3; i++) {
            box(px, w, h, 6, true);
            box(px, w, h, 6, false);
        }
        // richer colors so the blur reads as the cover's mood, not as grey mush
        float[] hsv = new float[3];
        float mean = 0f;
        for (int i = 0; i < px.length; i++) {
            android.graphics.Color.colorToHSV(px[i], hsv);
            mean += hsv[1];
        }
        mean /= px.length;
        boolean grey = mean < 0.15f; // black-and-white cover: stay neutral, don't boost a faint tint
        for (int i = 0; i < px.length; i++) {
            android.graphics.Color.colorToHSV(px[i], hsv);
            hsv[1] = grey ? 0f : Math.min(1f, hsv[1] * 1.4f);
            px[i] = android.graphics.Color.HSVToColor(hsv);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        if (s != src) s.recycle();
        if (sq != src && sq != s) sq.recycle();
        return out;
    }

    /** A gentler blur than {@link #make}: the cover is still recognisable, just frosted (for glass panels). */
    static Bitmap soft(Bitmap src) {
        final int w = 96, h = 96;
        int side = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = side == src.getWidth() && side == src.getHeight() ? src
                : Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
        Bitmap s = Bitmap.createScaledBitmap(sq, w, h, true);
        int[] px = new int[w * h];
        s.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < 2; i++) {
            box(px, w, h, 3, true);
            box(px, w, h, 3, false);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        if (s != src) s.recycle();
        if (sq != src && sq != s) sq.recycle();
        return out;
    }

    private static void box(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h;
        int lines = horizontal ? h : w;
        int[] line = new int[len];
        int[] out = new int[len];
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = horizontal ? px[l * w + i] : px[i * w + l];
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int c = line[Math.max(0, Math.min(len - 1, i + k))];
                    a += c >>> 24;
                    rr += (c >> 16) & 255;
                    g += (c >> 8) & 255;
                    b += c & 255;
                    n++;
                }
                out[i] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
            for (int i = 0; i < len; i++) {
                if (horizontal) px[l * w + i] = out[i];
                else px[i * w + l] = out[i];
            }
        }
    }

    /** Draws a bitmap scaled to cover the bounds and cropped from the middle, so it is never stretched. */
    static final class Backdrop extends android.graphics.drawable.Drawable {
        private final Bitmap bmp;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);

        Backdrop(Bitmap b) { bmp = b; }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect r = getBounds();
            if (r.width() <= 0 || r.height() <= 0) return;
            float s = Math.max(r.width() / (float) bmp.getWidth(), r.height() / (float) bmp.getHeight());
            float dw = bmp.getWidth() * s, dh = bmp.getHeight() * s;
            float l = r.left + (r.width() - dw) / 2f, t = r.top + (r.height() - dh) / 2f;
            c.save();
            c.clipRect(r);
            c.drawBitmap(bmp, null, new android.graphics.RectF(l, t, l + dw, t + dh), paint);
            c.restore();
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); invalidateSelf(); }
        @Override public int getAlpha() { return paint.getAlpha(); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
