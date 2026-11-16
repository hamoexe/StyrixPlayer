package com.hamo.styrixplayer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.google.android.material.color.MaterialColors;

/**
 * Same use as PopupMenu (getMenu().add(...), setOnMenuItemClickListener, show) but drawn with the
 * live ambient colors, rounded, with comfortable rows. The system popup can't follow the cover.
 */
final class ThemedPopupMenu {

    private final Context context;
    private final View anchor;
    private final PopupMenu holder; // only used as a ready-made Menu
    private PopupMenu.OnMenuItemClickListener listener;

    ThemedPopupMenu(Context context, View anchor) {
        this.context = context;
        this.anchor = anchor;
        this.holder = new PopupMenu(context, anchor);
    }

    Menu getMenu() {
        return holder.getMenu();
    }

    void setOnMenuItemClickListener(PopupMenu.OnMenuItemClickListener l) {
        listener = l;
    }

    void show() {
        float d = context.getResources().getDisplayMetrics().density;
        AmbientTheme.Palette p = AmbientTheme.active(context) ? AmbientTheme.current() : null;
        int bg = p != null ? p.high | 0xFF000000
                : MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int fg = p != null ? p.text
                : MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(18 * d);
        box.setBackground(shape);
        box.setClipToOutline(true);
        box.setPadding(0, (int) (8 * d), 0, (int) (8 * d));

        final PopupWindow window = new PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setElevation(10 * d);
        window.setBackgroundDrawable(null);

        Menu menu = holder.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            final MenuItem item = menu.getItem(i);
            if (!item.isVisible()) continue;
            TextView row = new TextView(context);
            row.setText(com.hamo.styrixplayer.I18n.tc(item.getTitle()));
            row.setTextSize(16);
            row.setTextColor(fg);
            row.setSingleLine(true);
            row.setMinWidth((int) (200 * d));
            row.setMinHeight((int) (52 * d));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (22 * d), 0, (int) (22 * d), 0);
            if (item.isEnabled()) {
                android.util.TypedValue tv = new android.util.TypedValue();
                context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                row.setBackgroundResource(tv.resourceId);
                row.setOnClickListener(v -> {
                    window.dismiss();
                    if (listener != null) listener.onMenuItemClick(item);
                });
            } else {
                row.setAlpha(0.6f);
            }
            box.addView(row);
        }
        window.showAsDropDown(anchor, 0, -(int) (4 * d), Gravity.END);
    }
}
