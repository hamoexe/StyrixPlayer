package com.hamo.styrixplayer.ui;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionToken;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.common.util.concurrent.ListenableFuture;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.data.Prefs;
import com.hamo.styrixplayer.data.Song;
import com.hamo.styrixplayer.playback.PlaybackService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerActivity extends BaseActivity {

    private static final int[] SLEEP_MINUTES = {0, 5, 10, 15, 30, 45, 60};
    private static final String[] SLEEP_LABELS = {
            "Off", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes"};
    private static final String PREFS = "player";
    private static final String KEY_SLEEP_END = "sleep_end";
    private static final String KEY_QUICK_OPEN = "quick_open";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ListenableFuture<MediaController> controllerFuture;
    @Nullable private MediaController controller;

    private View root, quickPanel;
    private ViewGroup content;
    private ImageView art;
    private TextView title, artist, elapsed, total, speedBadge, speedLabel;
    private Slider seek, speedSlider;
    private ImageButton btnShuffle, btnRepeat, btnFav, btnPlaylist, btnTune;
    private FloatingActionButton btnPlay;
    private MaterialButton btnQueue, btnSleep;
    private MaterialSwitch switchPitch;
    private ChipGroup tagGroup;
    private SharedPreferences prefs;

    private boolean dragging, speedDragging, quickOpen;

    // metadata editor: cover photo being edited (picked, not saved yet)
    private ActivityResultLauncher<String> photoPicker;
    @Nullable private ImageView metaArtView;
    @Nullable private Uri pickedPhoto;
    private boolean photoRemoved;
    @Nullable private Bitmap pickedPreview;
    private String artForId;
    private int activeColor, inactiveColor, normalColor, surfaceColor;
    private GradientDrawable background;
    private int backgroundTop;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 500);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            updateTrack();
        }

        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata metadata) {
            updateTrack();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayPause();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            updateProgress();
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean enabled) {
            updateShuffleRepeat();
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
            updateShuffleRepeat();
        }

        @Override
        public void onPlaybackParametersChanged(@NonNull PlaybackParameters params) {
            updateSpeed();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        photoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null || metaArtView == null) return;
            pickedPhoto = uri;
            photoRemoved = false;
            final ImageView target = metaArtView;
            io.execute(() -> {
                final Bitmap b = decodeSquare(uri, 256);
                runOnUiThread(() -> {
                    if (isDestroyed() || b == null) return;
                    pickedPreview = b;
                    target.setImageBitmap(b);
                });
            });
        });

        root = findViewById(R.id.player_root);
        padSystemBars(root, true, true);

        activeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.WHITE);
        inactiveColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        normalColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        surfaceColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface, Color.BLACK);

        background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{surfaceColor, surfaceColor});
        backgroundTop = surfaceColor;
        root.setBackground(background);

        content = findViewById(R.id.player_content);
        quickPanel = findViewById(R.id.quick_panel);
        art = findViewById(R.id.art);
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        tagGroup = findViewById(R.id.tag_group);
        elapsed = findViewById(R.id.elapsed);
        total = findViewById(R.id.total);
        seek = findViewById(R.id.seek);
        speedBadge = findViewById(R.id.speed_badge);
        speedLabel = findViewById(R.id.speed_label);
        speedSlider = findViewById(R.id.speed_slider);
        switchPitch = findViewById(R.id.switch_pitch);
        btnShuffle = findViewById(R.id.btn_shuffle);
        btnRepeat = findViewById(R.id.btn_repeat);
        btnFav = findViewById(R.id.btn_fav);
        btnPlaylist = findViewById(R.id.btn_playlist);
        btnTune = findViewById(R.id.btn_tune);
        btnPlay = findViewById(R.id.btn_play);
        btnQueue = findViewById(R.id.btn_queue);
        btnSleep = findViewById(R.id.btn_sleep);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // Make the artwork square
        art.post(() -> {
            ViewGroup.LayoutParams lp = art.getLayoutParams();
            lp.height = art.getWidth();
            art.setLayoutParams(lp);
        });

        View prev = findViewById(R.id.btn_prev);
        View next = findViewById(R.id.btn_next);
        for (View v : new View[]{prev, next, btnShuffle, btnRepeat, btnFav, btnPlaylist, btnTune, btnPlay}) {
            Anim.pressable(v);
        }

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        prev.setOnClickListener(v -> {
            if (controller != null) controller.seekToPrevious();
        });
        next.setOnClickListener(v -> {
            if (controller != null) controller.seekToNext();
        });
        btnPlay.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause();
            else controller.play();
        });
        btnShuffle.setOnClickListener(v -> {
            if (controller != null) {
                controller.setShuffleModeEnabled(!controller.getShuffleModeEnabled());
            }
        });
        btnRepeat.setOnClickListener(v -> {
            if (controller == null) return;
            int mode = controller.getRepeatMode();
            if (mode == Player.REPEAT_MODE_OFF) controller.setRepeatMode(Player.REPEAT_MODE_ALL);
            else if (mode == Player.REPEAT_MODE_ALL) controller.setRepeatMode(Player.REPEAT_MODE_ONE);
            else controller.setRepeatMode(Player.REPEAT_MODE_OFF);
        });
        btnFav.setOnClickListener(v -> {
            long id = currentSongId();
            if (id < 0) return;
            Db.get(this).toggleFavorite(id);
            updateFavorite();
            Anim.pop(btnFav);
        });
        btnPlaylist.setOnClickListener(v -> {
            long id = currentSongId();
            if (id >= 0) SongActions.addToPlaylistDialog(this, id, this::updatePlaylistIndicator);
        });
        btnQueue.setOnClickListener(v -> {
            startActivity(new Intent(this, QueueActivity.class));
            overridePendingTransition(R.anim.slide_up, R.anim.stay_still);
        });
        btnSleep.setOnClickListener(v -> showSleepDialog());

        // ----- quick settings -----
        btnTune.setOnClickListener(v -> setQuickOpen(!quickOpen, true));
        setQuickOpen(prefs.getBoolean(KEY_QUICK_OPEN, false), false);

        speedSlider.setLabelBehavior(LabelFormatter.LABEL_GONE);
        speedSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) return;
            speedLabel.setText("Speed " + formatSpeed(value));
            applySpeedLive(value);   // change the sound while the slider moves
        });
        speedSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                speedDragging = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                speedDragging = false;
                applySpeed(slider.getValue());
            }
        });
        findViewById(R.id.btn_speed_reset).setOnClickListener(v -> applySpeed(1f));

        switchPitch.setChecked(!Prefs.getBool(this, Prefs.KEEP_PITCH, true));
        switchPitch.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBool(this, Prefs.KEEP_PITCH, !checked);
            if (controller != null) applySpeed(controller.getPlaybackParameters().speed);
        });
        findViewById(R.id.btn_metadata).setOnClickListener(v -> openMetadataEditor());

        // ----- seek bar -----
        seek.setLabelBehavior(LabelFormatter.LABEL_GONE);
        seek.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) elapsed.setText(SongAdapter.formatTime((long) value));
        });
        seek.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                dragging = false;
                if (controller != null) controller.seekTo((long) slider.getValue());
                updateProgress();
            }
        });
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
                if (controller.getMediaItemCount() == 0) {
                    finish();
                    return;
                }
                updateTrack();
                updatePlayPause();
                updateShuffleRepeat();
                updateSpeed();
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            } catch (Exception e) {
                controller = null;
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(ticker);
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

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.stay_still, R.anim.slide_down);
    }

    // ---------- quick settings panel ----------

    private void setQuickOpen(boolean open, boolean animate) {
        quickOpen = open;
        prefs.edit().putBoolean(KEY_QUICK_OPEN, open).apply();
        if (animate) TransitionManager.beginDelayedTransition(content);
        quickPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        btnTune.setImageTintList(ColorStateList.valueOf(open ? activeColor : normalColor));
    }

    // ---------- UI updates ----------

    private long currentSongId() {
        if (controller == null || controller.getCurrentMediaItem() == null) return -1;
        try {
            return Long.parseLong(controller.getCurrentMediaItem().mediaId);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void updateTrack() {
        if (controller == null) return;
        MediaItem item = controller.getCurrentMediaItem();
        if (item == null) return;
        MediaMetadata md = item.mediaMetadata;

        title.setText(md.title != null ? md.title : "");
        artist.setText(md.artist != null ? md.artist : "");
        // marquee only scrolls while the view is "selected"; short text simply stays still
        title.setSelected(true);
        artist.setSelected(true);
        bindTags(md);

        loadArt(item);
        updateFavorite();
        updatePlaylistIndicator();
        updateProgress();
    }

    private void bindTags(MediaMetadata md) {
        tagGroup.removeAllViews();
        String raw = md.extras != null ? md.extras.getString(Song.EXTRA_TAGS, "") : "";
        if (raw == null || raw.isEmpty()) {
            tagGroup.setVisibility(View.GONE);
            return;
        }
        for (String t : raw.split(Song.TAG_SEPARATOR)) {
            if (t.isEmpty()) continue;
            Chip chip = new Chip(this);
            chip.setText(t);
            chip.setClickable(false);
            chip.setCheckable(false);
            chip.setFocusable(false);
            chip.setEnsureMinTouchTargetSize(false);
            tagGroup.addView(chip);
        }
        tagGroup.setVisibility(tagGroup.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void updateFavorite() {
        long id = currentSongId();
        if (id < 0) {
            btnFav.setImageResource(R.drawable.ic_favorite_border);
            btnFav.setImageTintList(ColorStateList.valueOf(normalColor));
            return;
        }
        boolean fav = Db.get(this).isFavorite(id);
        btnFav.setImageResource(fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        btnFav.setImageTintList(ColorStateList.valueOf(fav ? activeColor : normalColor));
    }

    /** The playlist button shows a check (in the accent color) when the song is already in a playlist. */
    private void updatePlaylistIndicator() {
        long id = currentSongId();
        if (id < 0) {
            btnPlaylist.setImageResource(R.drawable.ic_playlist_add);
            btnPlaylist.setImageTintList(ColorStateList.valueOf(normalColor));
            return;
        }
        boolean inAny = !Db.get(this).playlistsContaining(id).isEmpty();
        btnPlaylist.setImageResource(inAny ? R.drawable.ic_playlist_add_check : R.drawable.ic_playlist_add);
        btnPlaylist.setImageTintList(ColorStateList.valueOf(inAny ? activeColor : normalColor));
    }

    private void updatePlayPause() {
        if (controller == null) return;
        boolean playing = controller.isPlaying();
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        // artwork "breathes": full size while playing, slightly smaller when paused
        float scale = playing ? 1f : 0.9f;
        art.animate().scaleX(scale).scaleY(scale).setDuration(300).start();
    }

    private void updateShuffleRepeat() {
        if (controller == null) return;
        boolean shuffle = controller.getShuffleModeEnabled();
        btnShuffle.setImageTintList(ColorStateList.valueOf(shuffle ? activeColor : inactiveColor));

        int mode = controller.getRepeatMode();
        btnRepeat.setImageResource(mode == Player.REPEAT_MODE_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        btnRepeat.setImageTintList(ColorStateList.valueOf(
                mode == Player.REPEAT_MODE_OFF ? inactiveColor : activeColor));
    }

    private void updateProgress() {
        if (controller == null) return;
        long duration = controller.getDuration();
        boolean known = duration != C.TIME_UNSET && duration > 0;
        float max = known ? (float) duration : 1f;
        seek.setValueTo(max);
        total.setText(known ? SongAdapter.formatTime(duration) : "0:00");

        if (!dragging) {
            long pos = Math.max(0, controller.getCurrentPosition());
            seek.setValue(Math.min((float) pos, max));
            elapsed.setText(SongAdapter.formatTime(pos));
        }
        updateSleepLabel();
    }

    // ---------- speed ----------

    private static float snapSpeed(float speed) {
        float v = Math.round(speed * 20f) / 20f;      // steps of 0.05
        return Math.max(0.5f, Math.min(2f, v));
    }

    private static String formatSpeed(float speed) {
        float tenths = speed * 10f;
        if (Math.abs(tenths - Math.round(tenths)) < 0.001f) {
            return String.format(Locale.US, "%.1fx", speed);
        }
        return String.format(Locale.US, "%.2fx", speed);
    }

    private void updateSpeed() {
        if (controller == null) return;
        float speed = controller.getPlaybackParameters().speed;
        boolean changed = Math.abs(speed - 1f) > 0.001f;
        speedBadge.setVisibility(changed ? View.VISIBLE : View.GONE);
        speedBadge.setText(formatSpeed(speed));
        if (!speedDragging) {
            speedSlider.setValue(snapSpeed(speed));
            speedLabel.setText("Speed " + formatSpeed(speed));
        }
    }

    /** Called on every slider step while dragging; only touches the player when the step changes. */
    private void applySpeedLive(float value) {
        if (controller == null) return;
        float s = snapSpeed(value);
        if (Math.abs(controller.getPlaybackParameters().speed - s) < 0.001f) return;
        boolean keepPitch = Prefs.getBool(this, Prefs.KEEP_PITCH, true);
        controller.setPlaybackParameters(new PlaybackParameters(s, keepPitch ? 1f : s));
    }

    private void applySpeed(float speed) {
        if (controller == null) return;
        float s = snapSpeed(speed);
        boolean keepPitch = Prefs.getBool(this, Prefs.KEEP_PITCH, true);
        controller.setPlaybackParameters(new PlaybackParameters(s, keepPitch ? 1f : s));
        if (Prefs.getBool(this, Prefs.SPEED_PER_TRACK, false)) {
            long id = currentSongId();
            if (id >= 0) Db.get(this).setSpeed(id, s);
        }
    }

    // ---------- metadata editor ----------

    private void openMetadataEditor() {
        final long id = currentSongId();
        if (id < 0) return;
        Song s = Library.get(id);
        if (s != null) {
            showMetadataDialog(s);
            return;
        }
        // library not in memory yet (e.g. opened from the notification): load it first
        io.execute(() -> {
            Library.ensureLoaded(getApplicationContext());
            final Song loaded = Library.get(id);
            runOnUiThread(() -> {
                if (!isDestroyed() && loaded != null) showMetadataDialog(loaded);
            });
        });
    }

    private void showMetadataDialog(Song s) {
        View form = getLayoutInflater().inflate(R.layout.dialog_metadata, null);
        final EditText editTitle = form.findViewById(R.id.edit_title);
        final EditText editArtist = form.findViewById(R.id.edit_artist);
        final EditText editAlbum = form.findViewById(R.id.edit_album);
        final ImageView editArt = form.findViewById(R.id.edit_art);
        editTitle.setText(s.title);
        editArtist.setText(s.artist);
        editAlbum.setText(s.album == null ? "" : s.album);

        pickedPhoto = null;
        pickedPreview = null;
        photoRemoved = false;
        metaArtView = editArt;
        ArtLoader.load(this, s, editArt, 256);

        form.findViewById(R.id.btn_change_photo).setOnClickListener(v -> photoPicker.launch("image/*"));
        form.findViewById(R.id.btn_remove_photo).setOnClickListener(v -> {
            pickedPhoto = null;
            pickedPreview = null;
            photoRemoved = true;
            // show the file's own art again
            ArtLoader.load(this, s.id, s.uri, s.rawArtUri, editArt, 256 + 1,
                    R.drawable.ic_art_placeholder);
        });

        new ThemedDialogBuilder(this)
                .setTitle("Edit metadata")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", (d, w) -> {
                    deleteCustomArt(s.id);
                    Db.get(this).clearMeta(s.id);
                    finishMetadataChange(s.id);
                })
                .setPositiveButton("Save", (d, w) -> saveMetadata(s,
                        editTitle.getText().toString().trim(),
                        editArtist.getText().toString().trim(),
                        editAlbum.getText().toString().trim()))
                .setOnDismissListener(d -> metaArtView = null)
                .show();
    }

    private void saveMetadata(Song s, String newTitle, String newArtist, String newAlbum) {
        final Uri picked = pickedPhoto;
        final boolean removed = photoRemoved;
        io.execute(() -> {
            String[] old = Db.get(this).getMeta(s.id);
            String artPath = old == null ? "" : old[3];
            if (removed) {
                deleteCustomArt(s.id);
                artPath = "";
            } else if (picked != null) {
                String saved = storeCustomArt(s.id, picked);
                if (saved != null) artPath = saved;
                else runOnUiThread(() -> Toast.makeText(this, "Couldn't read that photo",
                        Toast.LENGTH_SHORT).show());
            }
            Db.get(this).setMeta(s.id, newTitle, newArtist, newAlbum, artPath);
            runOnUiThread(() -> {
                if (!isDestroyed()) finishMetadataChange(s.id);
            });
        });
    }

    private File artDir() {
        File dir = new File(getFilesDir(), "art");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private void deleteCustomArt(long id) {
        File[] files = artDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.getName().startsWith(id + "_")) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /** Crops the picked photo to a square, shrinks it and saves it privately. Off the main thread. */
    @Nullable
    private String storeCustomArt(long id, Uri source) {
        Bitmap b = decodeSquare(source, 1024);
        if (b == null) return null;
        deleteCustomArt(id);
        // new file name each time so nothing keeps showing the old picture
        File out = new File(artDir(), id + "_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Decodes an image, center-crops it to a square no bigger than maxPx. Off the main thread. */
    @Nullable
    private Bitmap decodeSquare(Uri uri, int maxPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            int smallest = Math.min(bounds.outWidth, bounds.outHeight);
            while (smallest / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap full;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                full = BitmapFactory.decodeStream(in, null, opts);
            }
            if (full == null) return null;
            int side = Math.min(full.getWidth(), full.getHeight());
            Bitmap square = Bitmap.createBitmap(full, (full.getWidth() - side) / 2,
                    (full.getHeight() - side) / 2, side, side);
            if (side > maxPx) square = Bitmap.createScaledBitmap(square, maxPx, maxPx, true);
            return square;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void finishMetadataChange(long id) {
        ArtLoader.invalidate(id);
        Song updated = Library.applyMeta(getApplicationContext(), id);
        if (updated == null || controller == null) return;
        MediaItem cur = controller.getCurrentMediaItem();
        if (cur != null && cur.mediaId.equals(String.valueOf(id))) {
            // swaps only the text and picture; the song keeps playing
            artForId = null;
            controller.replaceMediaItem(controller.getCurrentMediaItemIndex(), updated.toMediaItem());
            loadArt(updated.toMediaItem());
        }
        Toast.makeText(this, "Metadata updated", Toast.LENGTH_SHORT).show();
    }

    // ---------- sleep timer ----------

    private long sleepEnd() {
        long end = prefs.getLong(KEY_SLEEP_END, 0);
        return end > System.currentTimeMillis() ? end : 0;
    }

    private void updateSleepLabel() {
        long end = sleepEnd();
        if (end == 0) {
            btnSleep.setText("Sleep timer");
        } else {
            long minutes = (end - System.currentTimeMillis() + 59_999) / 60_000;
            btnSleep.setText("Sleep " + minutes + "m");
        }
    }

    private void showSleepDialog() {
        if (controller == null) return;
        new ThemedDialogBuilder(this)
                .setTitle("Sleep timer")
                .setItems(SLEEP_LABELS, (dialog, which) -> setSleepTimer(SLEEP_MINUTES[which]))
                .show();
    }

    private void setSleepTimer(int minutes) {
        if (controller == null) return;
        Bundle args = new Bundle();
        args.putLong(PlaybackService.EXTRA_MINUTES, minutes);
        controller.sendCustomCommand(
                new SessionCommand(PlaybackService.ACTION_SLEEP_TIMER, Bundle.EMPTY), args);
        prefs.edit().putLong(KEY_SLEEP_END,
                minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0).apply();
        updateSleepLabel();
        Toast.makeText(this, minutes > 0 ? "Music will pause in " + minutes + " min"
                : "Sleep timer off", Toast.LENGTH_SHORT).show();
    }

    // ---------- album art + background tint ----------

    private void loadArt(MediaItem item) {
        final String id = item.mediaId;
        if (id.equals(artForId)) return;
        artForId = id;

        final Uri songUri = item.localConfiguration != null ? item.localConfiguration.uri : null;
        final Uri artUri = item.mediaMetadata.artworkUri;

        io.execute(() -> {
            final Bitmap bmp = ArtLoader.decode(getApplicationContext(), songUri, artUri, 768);
            final int tint = bmp != null ? tintFrom(bmp) : 0;
            runOnUiThread(() -> {
                if (isDestroyed() || !id.equals(artForId)) return;
                if (bmp != null) art.setImageBitmap(bmp);
                else art.setImageResource(R.drawable.ic_art_placeholder);
                art.setAlpha(0f);
                art.animate().alpha(1f).setDuration(250).start();
                animateBackground(tint != 0 ? tint : surfaceColor);
            });
        });
    }

    /** Average color of the artwork, darkened (or lightened in light mode) to sit behind text. */
    private int tintFrom(Bitmap bmp) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(bmp, 1, 1, true);
            int c = one.getPixel(0, 0);
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            boolean night = (getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            if (night) {
                hsv[1] = Math.min(hsv[1], 0.8f);
                hsv[2] = Math.min(hsv[2], 0.32f);
            } else {
                hsv[1] = Math.min(hsv[1], 0.35f);
                hsv[2] = 0.97f;
            }
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return 0;
        }
    }

    private void animateBackground(int target) {
        ValueAnimator a = ValueAnimator.ofArgb(backgroundTop, target);
        backgroundTop = target;
        a.setDuration(500);
        a.addUpdateListener(v -> background.setColors(
                new int[]{(int) v.getAnimatedValue(), surfaceColor}));
        a.start();
    }
}
