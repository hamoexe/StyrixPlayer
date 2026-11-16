package com.hamo.styrixplayer.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import android.content.Intent;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.data.Song;
import com.hamo.styrixplayer.playback.PlayerConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlaylistActivity extends BaseActivity {

    public static final String EXTRA_ID = "playlist_id";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Song> songs = new ArrayList<>();

    private long playlistId = -1;
    private String playlistName = "";

    private SongAdapter adapter;
    private RecyclerView list;
    private TextView titleView, emptyView;

    private PlayerConnection connection;
    @Nullable private MediaController controller;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            updatePlaying();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_playlist);
        padSystemBars(findViewById(R.id.playlist_root), true, true);

        playlistId = getIntent().getLongExtra(EXTRA_ID, -1);
        Db.Playlist p = Db.get(this).playlist(playlistId);
        if (p == null) {
            finish();
            return;
        }
        playlistName = p.name;

        titleView = findViewById(R.id.pl_title);
        emptyView = findViewById(R.id.pl_empty);
        list = findViewById(R.id.pl_list);
        titleView.setText(playlistName);
        PlaylistStyle.applyColor((ImageView) findViewById(R.id.pl_icon), PlaylistStyle.color(playlistId));

        adapter = new SongAdapter(songs,
                pos -> play(pos, false),
                (pos, anchor) -> SongActions.showMenu(this, anchor, songs.get(pos),
                        controller, playlistId, this::reload));
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.pl_back).setOnClickListener(v -> finish());
        findViewById(R.id.pl_more).setOnClickListener(this::showMenu);
        findViewById(R.id.pl_play).setOnClickListener(v -> play(0, false));
        findViewById(R.id.pl_shuffle).setOnClickListener(v -> {
            if (!songs.isEmpty()) play(new Random().nextInt(songs.size()), true);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SongActions.handleActivityResult(requestCode, resultCode);
    }

    @Override
    protected void onStart() {
        super.onStart();
        connection = new PlayerConnection(this);
        connection.connect(c -> {
            controller = c;
            c.addListener(playerListener);
            updatePlaying();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playlistId >= 0) reload();
    }

    @Override
    protected void onStop() {
        if (controller != null) {
            controller.removeListener(playerListener);
            controller = null;
        }
        if (connection != null) connection.release();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        io.execute(() -> {
            Library.ensureLoaded(getApplicationContext());
            final List<Song> found = Library.resolve(Db.get(this).playlistSongIds(playlistId));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                boolean first = songs.isEmpty();
                songs.clear();
                songs.addAll(found);
                adapter.notifyDataSetChanged();
                if (first) list.scheduleLayoutAnimation();
                emptyView.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
                updatePlaying();
            });
        });
    }

    private void play(int index, boolean shuffle) {
        if (songs.isEmpty()) return;
        if (controller == null) return;
        SongActions.playList(controller, songs, index, shuffle);
    }

    private void updatePlaying() {
        String id = null;
        if (controller != null && controller.getCurrentMediaItem() != null) {
            id = controller.getCurrentMediaItem().mediaId;
        }
        adapter.setPlayingId(id);
    }

    private void showMenu(View anchor) {
        ThemedPopupMenu pm = new ThemedPopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Rename");
        pm.getMenu().add(0, 2, 1, "Delete playlist");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                SongActions.promptName(this, "Rename playlist", playlistName, name -> {
                    Db.get(this).renamePlaylist(playlistId, name);
                    playlistName = name;
                    titleView.setText(name);
                });
            } else {
                new ThemedDialogBuilder(this)
                        .setTitle("Delete \"" + playlistName + "\"?")
                        .setMessage("The songs themselves are not deleted.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> {
                            Db.get(this).deletePlaylist(playlistId);
                            finish();
                        })
                        .show();
            }
            return true;
        });
        pm.show();
    }
}
