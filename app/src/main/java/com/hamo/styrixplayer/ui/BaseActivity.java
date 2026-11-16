package com.hamo.styrixplayer.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Edge-to-edge screens: content draws behind the system bars and pads itself clear of them. */
public abstract class BaseActivity extends AppCompatActivity {

    private String themeKey = "";

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(com.hamo.styrixplayer.I18n.wrap(newBase));
    }

    @Override
    public void applyOverrideConfiguration(android.content.res.Configuration override) {
        // AppCompat rebuilds the configuration for night mode; keep the chosen language in it
        java.util.Locale l = override == null ? null : com.hamo.styrixplayer.I18n.locale(this);
        if (l != null) {
            override.setLocale(l);
            override.setLayoutDirection(l);
        }
        super.applyOverrideConfiguration(override);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        AppTheme.apply(this);
        themeKey = AppTheme.key(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // colors were changed while this screen was in the background
        if (!AppTheme.key(this).equals(themeKey)) recreateForTheme();
    }

    @Override
    protected void onDestroy() {
        AmbientTheme.detach(this);
        super.onDestroy();
    }

    /** Called just before the screen is rebuilt with new colors; save what you want to keep. */
    protected void onThemeChanging() { }

    /** Snapshot of the old screen, faded out over the rebuilt one so colors change softly. */
    private static Bitmap fadeShot;

    public final void recreateForTheme() {
        onThemeChanging();
        fadeShot = null;
        try {
            View decor = getWindow().getDecorView();
            if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                decor.draw(new Canvas(b));
                fadeShot = b;
            }
        } catch (Throwable ignored) {
            fadeShot = null;
        }
        recreate();
        overridePendingTransition(0, 0); // no system flash between the two screens
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        AmbientTheme.attach(this);
        com.hamo.styrixplayer.I18n.install(getWindow().getDecorView());
        final Bitmap shot = fadeShot;
        fadeShot = null;
        if (shot == null) return;
        final ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        if (decor.getWidth() != 0 && (decor.getWidth() != shot.getWidth()
                || decor.getHeight() != shot.getHeight())) return; // rotated: skip the fade
        final ImageView cover = new ImageView(this);
        cover.setImageBitmap(shot);
        cover.setScaleType(ImageView.ScaleType.FIT_XY);
        cover.setClickable(false);
        decor.addView(cover, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cover.animate()
                .alpha(0f)
                .setStartDelay(120)
                .setDuration(550)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> decor.removeView(cover))
                .start();
    }

    /** Adds the status/navigation bar sizes on top of the view's own padding. */
    protected static void padSystemBars(View v, boolean top, boolean bottom) {
        final int l = v.getPaddingLeft();
        final int t = v.getPaddingTop();
        final int r = v.getPaddingRight();
        final int b = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(l + bars.left, t + (top ? bars.top : 0),
                    r + bars.right, b + (bottom ? bars.bottom : 0));
            return insets;
        });
    }
}
