package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.google.android.material.progressindicator.CircularProgressIndicator;

/** Pull down at the top of a list to refresh it. Wraps one scrolling child. */
public class PullRefreshLayout extends FrameLayout {

    public interface Listener {
        void onRefresh();
    }

    private final CircularProgressIndicator spinner;
    private final int slop;
    private final float threshold, maxPull;
    @Nullable private Listener listener;
    private float downY, startY, pull;
    private boolean dragging, refreshing;

    public PullRefreshLayout(Context c) {
        this(c, null);
    }

    public PullRefreshLayout(Context c, @Nullable AttributeSet attrs) {
        super(c, attrs);
        float d = c.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        threshold = 72 * d;
        maxPull = 120 * d;
        spinner = new CircularProgressIndicator(c);
        spinner.setIndeterminate(true);
        spinner.setAlpha(0f);
        LayoutParams lp = new LayoutParams((int) (40 * d), (int) (40 * d), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = (int) (16 * d);
        addView(spinner, 0, lp);
    }

    public void setOnRefreshListener(@Nullable Listener l) {
        listener = l;
    }

    @Nullable
    private View content() {
        return getChildCount() > 1 ? getChildAt(1) : null;
    }

    private boolean canScrollUp() {
        View v = content();
        return v != null && v.canScrollVertically(-1);
    }

    private void setPull(float p) {
        pull = p;
        View v = content();
        if (v != null) v.setTranslationY(p);
        if (!refreshing) spinner.setAlpha(Math.min(1f, p / threshold));
    }

    private void settle(float to, boolean showSpinner) {
        View v = content();
        pull = to;
        if (v != null) v.animate().translationY(to).setDuration(200).start();
        spinner.animate().alpha(showSpinner ? 1f : 0f).setDuration(200).start();
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    /** Call with false when the reload is done. */
    public void setRefreshing(boolean r) {
        if (r || !refreshing) {
            if (!r && pull > 0 && !dragging) settle(0, false);
            return;
        }
        refreshing = false;
        settle(0, false);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (refreshing) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && ev.getY() - downY > slop && !canScrollUp()) {
                    dragging = true;
                    startY = ev.getY();
                }
                break;
            default:
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!dragging) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                setPull(Math.max(0f, Math.min((ev.getY() - startY) * 0.5f, maxPull)));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (ev.getActionMasked() == MotionEvent.ACTION_UP && pull >= threshold) {
                    refreshing = true;
                    settle(threshold, true);
                    if (listener != null) listener.onRefresh();
                } else {
                    settle(0, false);
                }
                return true;
            default:
                return true;
        }
    }
}
