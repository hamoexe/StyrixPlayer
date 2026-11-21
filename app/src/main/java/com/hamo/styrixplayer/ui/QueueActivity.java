package com.hamo.styrixplayer.ui;

import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.common.util.concurrent.ListenableFuture;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.playback.PlaybackService;

import java.util.ArrayList;
import java.util.List;

public class QueueActivity extends BaseActivity {

    private final List<MediaItem> items = new ArrayList<>();
    private QueueAdapter adapter;
    private RecyclerView list;

    private ListenableFuture<MediaController> controllerFuture;
    @Nullable private MediaController controller;

    private boolean dragging;
    private int currentIndex = -1;
    private int activeColor, normalColor;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onTimelineChanged(Timeline timeline, int reason) {
            if (!dragging) reconcile();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            AppTheme.onTrack(QueueActivity.this, item);   // ambient colors follow the new cover while the queue is open
            refreshCurrent();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_queue);
        padSystemBars(findViewById(R.id.queue_root), true, true);

        activeColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary, Color.WHITE);
        normalColor = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface, Color.WHITE);

        findViewById(R.id.queue_back).setOnClickListener(v -> finish());

        list = findViewById(R.id.queue_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new QueueAdapter();
        list.setAdapter(adapter);

        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (from < 0 || to < 0 || controller == null) return false;
                items.add(to, items.remove(from));
                adapter.notifyItemMoved(from, to);
                controller.moveMediaItem(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getBindingAdapterPosition();
                if (pos < 0 || controller == null) return;
                items.remove(pos);
                adapter.notifyItemRemoved(pos);
                controller.removeMediaItem(pos);
                if (items.isEmpty()) {
                    finish();
                    return;
                }
                if (pos < currentIndex) {
                    // an earlier row was removed: the playing row just moved up by one
                    currentIndex--;
                } else if (pos == currentIndex && pos < items.size()) {
                    // the playing row was removed: the next song slid into its place and
                    // is now playing, so its row has to be redrawn as highlighted
                    adapter.notifyItemChanged(pos);
                }
                refreshCurrent();
            }

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder vh, int actionState) {
                super.onSelectedChanged(vh, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragging = true;
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                dragging = false;
                refreshCurrent();
            }
        });
        helper.attachToRecyclerView(list);
    }

    @Override
    protected void onStart() {
        super.onStart();
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(playerListener);
                AppTheme.onTrack(this, controller.getCurrentMediaItem());
                reconcile();
                if (currentIndex >= 0) list.scrollToPosition(currentIndex);
            } catch (Exception e) {
                controller = null;
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        super.onStop();
    }

    // ---------- sync with the player ----------

    /** Rebuilds the list only if the player's queue differs from what is shown. */
    private void reconcile() {
        if (controller == null) return;
        int count = controller.getMediaItemCount();
        boolean same = count == items.size();
        if (same) {
            for (int i = 0; i < count; i++) {
                if (!controller.getMediaItemAt(i).mediaId.equals(items.get(i).mediaId)) {
                    same = false;
                    break;
                }
            }
        }
        if (!same) {
            items.clear();
            for (int i = 0; i < count; i++) items.add(controller.getMediaItemAt(i));
            adapter.notifyDataSetChanged();
        }
        refreshCurrent();
    }

    private void refreshCurrent() {
        if (controller == null) return;
        int idx = controller.getCurrentMediaItemIndex();
        if (idx == currentIndex) return;
        int old = currentIndex;
        currentIndex = idx;
        if (old >= 0 && old < items.size()) adapter.notifyItemChanged(old);
        if (idx >= 0 && idx < items.size()) adapter.notifyItemChanged(idx);
    }

    // ---------- adapter ----------

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.stay_still, R.anim.slide_down);
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ImageView art;
        final TextView title, artist;

        Holder(View v) {
            super(v);
            art = v.findViewById(R.id.queue_art);
            title = v.findViewById(R.id.queue_title);
            artist = v.findViewById(R.id.queue_artist);
        }
    }

    private class QueueAdapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_queue, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            MediaMetadata md = items.get(position).mediaMetadata;
            boolean current = position == currentIndex;
            h.title.setText(md.title != null ? md.title : "");
            h.artist.setText(md.artist != null ? md.artist : "");
            ArtLoader.loadItem(QueueActivity.this, items.get(position), h.art, 144);
            h.title.setTypeface(Typeface.create("sans-serif-medium", current ? Typeface.BOLD : Typeface.NORMAL));
            // the colors read at creation can be one cover behind: map them to the live palette
            boolean amb = AmbientTheme.active(QueueActivity.this);
            h.title.setTextColor(current ? (amb ? AmbientTheme.map(activeColor) : activeColor)
                    : (amb ? AmbientTheme.map(normalColor) : normalColor));
            // same "colored row" marker as the song lists
            h.itemView.setBackgroundResource(current ? R.drawable.bg_song_row_playing : R.drawable.bg_song_row);
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || controller == null) return;
                controller.seekTo(pos, 0);
                controller.play();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
