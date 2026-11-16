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
}
