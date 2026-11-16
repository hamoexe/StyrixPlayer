package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * A soft fade from the screen background to transparent, laid over the top of a list so rows
 * slide out of sight smoothly instead of being cut off flat. Never takes touches.
 */
public class EdgeFade extends View {

    private final Paint paint = new Paint();

    public EdgeFade(Context c) {
        this(c, null);
    }

    public EdgeFade(Context c, @Nullable AttributeSet a) {
        super(c, a);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        AmbientTheme.Palette p = AmbientTheme.active(getContext()) ? AmbientTheme.current() : null;
        int bg = p != null ? p.bg | 0xFF000000
                : MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface);
        paint.setShader(new LinearGradient(0, 0, 0, getHeight(), bg, bg & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
