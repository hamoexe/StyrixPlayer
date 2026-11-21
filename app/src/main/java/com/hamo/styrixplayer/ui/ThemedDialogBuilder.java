package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.MaterialShapeDrawable;

/**
 * A Material dialog whose card takes the live ambient color (dialogs are separate windows, so the
 * normal recoloring never reaches them). Outside ambient mode it behaves like the normal dialog.
 */
public class ThemedDialogBuilder extends MaterialAlertDialogBuilder {

    public ThemedDialogBuilder(@NonNull Context context) {
        super(context);
    }

    @NonNull
    @Override
    public AlertDialog create() {
        AlertDialog d = super.create();
        try {
            AmbientTheme.Palette p = AmbientTheme.active(getContext()) ? AmbientTheme.current() : null;
            Window w = d.getWindow();
            if (p != null && w != null) {
                Drawable bg = w.getDecorView().getBackground();
                if (bg instanceof InsetDrawable) {
                    Drawable inner = ((InsetDrawable) bg).getDrawable();
                    if (inner instanceof MaterialShapeDrawable) {
                        ((MaterialShapeDrawable) inner).setFillColor(ColorStateList.valueOf(p.high | 0xFF000000));
                    }
                }
            }
            d.setOnShowListener(x -> {
                try {
                    AmbientTheme.recolorNow(d.getWindow().getDecorView());
                    paintControls(d);
                    com.hamo.styrixplayer.I18n.install(d.getWindow().getDecorView());
                } catch (Throwable ignored) {
                    // keep the normal colors
                }
            });
        } catch (Throwable ignored) {
            // the dialog just keeps its normal color
        }
        return d;
    }

    /**
     * Buttons, text fields and check marks are painted straight from the live palette. The generic
     * recolor only swaps colors it has seen before, so a dialog opened after the cover changed kept
     * the previous cover's accent on its buttons.
     */
    private static void paintControls(AlertDialog d) {
        if (!AmbientTheme.active(d.getContext())) return;
        AmbientTheme.Palette p = AmbientTheme.current();
        if (p == null) return;
        int[] which = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int w : which) {
            android.widget.Button b = d.getButton(w);
            if (b == null) continue;
            b.setTextColor(p.accent);
            if (b instanceof com.google.android.material.button.MaterialButton) {
                ((com.google.android.material.button.MaterialButton) b).setRippleColor(
                        ColorStateList.valueOf((p.accent & 0x00FFFFFF) | 0x30000000));
            }
        }
        paintFields(d.getWindow().getDecorView(), p.accent);
    }

    private static void paintFields(android.view.View v, int accent) {
        if (v instanceof android.widget.EditText) {
            android.widget.EditText et = (android.widget.EditText) v;
            v.setBackgroundTintList(ColorStateList.valueOf(accent));
            et.setHighlightColor((accent & 0x00FFFFFF) | 0x55000000);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // the blinking cursor kept the old theme's blue
                android.graphics.drawable.GradientDrawable cur = new android.graphics.drawable.GradientDrawable();
                cur.setColor(accent);
                float dn = et.getResources().getDisplayMetrics().density;
                cur.setSize(Math.round(2 * dn), Math.max(1, Math.round(et.getTextSize() * 1.25f)));
                et.setTextCursorDrawable(cur);
            }
        } else if (v instanceof android.widget.CheckBox || v instanceof android.widget.RadioButton) {
            ((android.widget.CompoundButton) v).setButtonTintList(ColorStateList.valueOf(accent));
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) paintFields(g.getChildAt(i), accent);
        }
    }
}
