package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;

/**
 * A thumb on the right edge of a long list. It shows while scrolling, can be dragged to jump
 * through the list, and shows a bubble with the letter you are at. Touches outside the thumb go
 * straight through to the list below.
 */
public class FastScroller extends View {

    public interface Labeler {
        /** Short text for the row at this position (a letter), or null for no bubble. */
        @Nullable String label(int position);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;

    @Nullable private RecyclerView list;
    @Nullable private Labeler labeler;
    private boolean dragging;
    private float shown; // 0..1 visibility
    private String bubble;
    private final Runnable hide = this::fadeOut;

    public FastScroller(Context c) {
        this(c, null);
    }

    public FastScroller(Context c, @Nullable AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(26 * dp);
        textPaint.setFakeBoldText(true);
        setWillNotDraw(false);
    }

    public void attach(RecyclerView rv, @Nullable Labeler l) {
        list = rv;
        labeler = l;
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView r, int dx, int dy) {
                if (dy != 0 && contentTallerThanList()) reveal();
            }
        });
    }

    private boolean contentTallerThanList() {
        return list != null && list.computeVerticalScrollRange() > list.getHeight() * 2;
    }

    private void reveal() {
        removeCallbacks(hide);
        shown = 1f;
        invalidate();
        postDelayed(hide, 1400);
    }

    private void fadeOut() {
        if (dragging) return;
        shown = 0f;
        invalidate();
    }

    private float thumbTop() {
        if (list == null) return 0;
        int range = list.computeVerticalScrollRange() - list.getHeight();
        float f = range <= 0 ? 0 : (float) list.computeVerticalScrollOffset() / range;
        return f * (getHeight() - thumbHeight());
    }

    private float thumbHeight() {
        return 56 * dp;
    }

    private int accent() {
        AmbientTheme.Palette p = AmbientTheme.active(getContext()) ? AmbientTheme.current() : null;
        return p != null ? p.accent : MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (shown <= 0f || list == null) return;
        float top = thumbTop();
        float w = getWidth();
        int color = accent();
        paint.setColor(color);
        paint.setAlpha(dragging ? 255 : 200);
        rect.set(w - 10 * dp, top + 6 * dp, w - 4 * dp, top + thumbHeight() - 6 * dp);
        c.drawRoundRect(rect, 3 * dp, 3 * dp, paint);
        if (dragging && bubble != null && !bubble.isEmpty()) {
            float size = 64 * dp;
            float cy = top + thumbHeight() / 2f;
            rect.set(w - 22 * dp - size, cy - size / 2f, w - 22 * dp, cy + size / 2f);
            paint.setColor(color);
            paint.setAlpha(255);
            c.drawRoundRect(rect, size / 2f, size / 2f, paint);
            textPaint.setColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary));
            c.drawText(bubble, rect.centerX(), rect.centerY() + 9 * dp, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (list == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (shown <= 0f || e.getX() < getWidth() - 36 * dp) return false;
                float top = thumbTop();
                if (e.getY() < top - 12 * dp || e.getY() > top + thumbHeight() + 12 * dp) return false;
                dragging = true;
                removeCallbacks(hide);
                getParent().requestDisallowInterceptTouchEvent(true);
                jump(e.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    jump(e.getY());
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    postDelayed(hide, 900);
                    invalidate();
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    private void jump(float y) {
        RecyclerView rv = list;
        if (rv == null || rv.getAdapter() == null) return;
        int count = rv.getAdapter().getItemCount();
        if (count == 0) return;
        float f = Math.max(0f, Math.min(1f, (y - thumbHeight() / 2f) / (getHeight() - thumbHeight())));
        int pos = Math.round(f * (count - 1));
        if (rv.getLayoutManager() instanceof LinearLayoutManager) {
            ((LinearLayoutManager) rv.getLayoutManager()).scrollToPositionWithOffset(pos, 0);
        }
        bubble = labeler == null ? null : labeler.label(pos);
        invalidate();
    }
}
