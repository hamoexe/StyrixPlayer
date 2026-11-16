package com.hamo.styrixplayer.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.view.LayoutInflater;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.transition.TransitionManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.view.TextureView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
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
import com.hamo.styrixplayer.yt.YtAuth;
import com.hamo.styrixplayer.yt.YtDownloads;
import com.hamo.styrixplayer.yt.YtLikes;
import com.hamo.styrixplayer.yt.YtLocal;
import com.hamo.styrixplayer.yt.YtMusicApi;
import com.hamo.styrixplayer.yt.YtTrack;
import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.data.Prefs;
import com.hamo.styrixplayer.data.Song;
import com.hamo.styrixplayer.lyrics.Lyrics;
import com.hamo.styrixplayer.lyrics.LyricsRepo;
import com.hamo.styrixplayer.playback.PlaybackService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayerFragment extends Fragment {

    /** Implemented by the activity that hosts the sheet. */
    public interface Host {
        void collapsePlayer();

        /** Touch listener that lets the user drag the expanded sheet back down. */
        View.OnTouchListener sheetDragListener();
    }

    private View view;

    private <T extends View> T findViewById(int id) {
        return view.findViewById(id);
    }

    private Context ctx() {
        return requireContext();
    }

    private ContentResolver getContentResolver() {
        return ctx().getContentResolver();
    }

    private File getFilesDir() {
        return ctx().getFilesDir();
    }

    private Context getApplicationContext() {
        return ctx().getApplicationContext();
    }

    private void runOnUiThread(Runnable r) {
        Activity a = getActivity();
        if (a != null) a.runOnUiThread(r);
    }

    private boolean isDestroyed() {
        return !isAdded() || view == null;
    }


    private static final int[] SLEEP_MINUTES = {0, 5, 10, 15, 30, 45, 60};
    private static final String[] SLEEP_LABELS = {
            "Off", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "45 minutes", "60 minutes"};
    private static final String PREFS = "player";
    private static final String KEY_SLEEP_END = "sleep_end";
    private static final String KEY_QUICK_OPEN = "quick_open";
    private static final String KEY_LYRICS_OPEN = "lyrics_open";
    private static final String KEY_VIDEO = "video_on";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Lyrics lookups hit the network, so they get their own thread (art decoding must not wait). */
    private final ExecutorService lyricsIo = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ListenableFuture<MediaController> controllerFuture;
    @Nullable private MediaController controller;

    private View root, quickPanel;
    private ViewGroup content;
    private ImageView art;
    private ImageView artShadow;
    private TextView title, artist, elapsed, total, speedBadge, speedLabel;
    private Slider seek, speedSlider;
    private ImageButton btnShuffle, btnRepeat, btnFav, btnPlaylist, btnTune;
    private FloatingActionButton btnPlay;
    private MaterialButton btnQueue, btnSleep;
    private MaterialSwitch switchPitch;
    private ChipGroup tagGroup;
    private SharedPreferences prefs;

    // lyrics
    private LyricsView lyricsView;
    private ImageButton btnLyrics;

    // video (streamed songs): a muted second player, lyrics under it, player tinted like the picture
    private ImageButton btnVideo;
    private FrameLayout videoBox;
    private TextureView videoView;
    private View artFrame;
    @Nullable private VideoLayer videoLayer;
    private boolean videoPref, videoOn, lyricsBeforeVideo, lyricsEmpty = true;
    @Nullable private String videoAsked;
    @Nullable private ValueAnimator frameAnim;
    @Nullable private CornerGlow glow;
    @Nullable private CornerGlow coverGlow;      // the cover's corner colors (normal, non-video player)
    @Nullable private ValueAnimator glowAnim;
    private int lastVideoSeed;
    @Nullable private int[] videoRoles;
    @Nullable private AmbientTheme.Palette videoPal;
    @Nullable private ValueAnimator videoAnim;
    private int baseSurface, lastTint;
    private boolean lyricsOpen;
    @Nullable private String lyricsForId;

    private boolean dragging, speedDragging, quickOpen;

    // metadata editor: cover photo being edited (picked, not saved yet)
    private ActivityResultLauncher<String> photoPicker;
    /** The full tag editor; when it saves, the library is re-read so the new tags show up. */
    private ActivityResultLauncher<Intent> editorLauncher;
    private ActivityResultLauncher<Intent> lyricsEditorLauncher;
    @Nullable private ImageView metaArtView;
    @Nullable private Uri pickedPhoto;
    private boolean photoRemoved;
    @Nullable private Bitmap pickedPreview;
    private String artForId;
    private LayerDrawable bgLayers;
    private HeatBar heatBar;
    @Nullable private String heatKey;
    private int[] heat = new int[HeatBar.SLICES];
    private int heatLast = -1;
    private long heatLastPos = -1, heatSaved;
    private boolean heatDirty;
    @Nullable private ValueAnimator blurAnim;
    @Nullable private Bitmap lastBlur;
    @Nullable private Bitmap panelBlur;
    @Nullable private FrostPanelDrawable panelBg;
    private int bActive, bInactive, bNormal, surfaceColor;
    private GradientDrawable background;
    private int backgroundTop;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (lyricsView != null) lyricsView.kick();   // also catches seeks while paused
            if (videoOn && videoLayer != null) videoLayer.sync(controller, false);
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
            if (videoOn && videoLayer != null) videoLayer.sync(controller, false);
            updatePlayPause();
            if (lyricsView != null) lyricsView.kick();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            updateProgress();
        }

        @Override
        public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition,
                                            @NonNull Player.PositionInfo newPosition, int reason) {
            if (videoOn && videoLayer != null) videoLayer.sync(controller, true);
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
            if (videoOn && videoLayer != null) videoLayer.sync(controller, false);
            updateSpeed();
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        lyricsEditorLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != Activity.RESULT_OK || controller == null) return;
            MediaItem item = controller.getCurrentMediaItem();
            if (item == null) return;
            lyricsForId = null;   // show the saved version now
            loadLyrics(item, false);
        });
        editorLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() != Activity.RESULT_OK || r.getData() == null) return;
            final long id = r.getData().getLongExtra(MetadataEditorActivity.EXTRA_SONG_ID, -1);
            if (id < 0) return;
            final Context app = getApplicationContext();
            io.execute(() -> {
                Library.load(app);
                runOnUiThread(() -> {
                    if (!isDestroyed()) finishMetadataChange(id);
                });
            });
        });
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
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.activity_player, container, false);

        root = findViewById(R.id.player_root);
        BaseActivity.padSystemBars(root, true, true);

        bActive = MaterialColors.getColor(ctx(), com.google.android.material.R.attr.colorPrimary, Color.WHITE);
        bInactive = MaterialColors.getColor(ctx(), com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        bNormal = MaterialColors.getColor(ctx(), com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        surfaceColor = MaterialColors.getColor(ctx(), com.google.android.material.R.attr.colorSurface, Color.BLACK);
        baseSurface = surfaceColor;

        background = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{surfaceColor, surfaceColor});
        backgroundTop = surfaceColor;
        // layers: [blurred cover, translucent color gradient on top]. The gradient carries the live
        // ambient colors and tints the blur, so the background always matches the rest of the UI.
        boolean nightNow = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        background.setAlpha(nightNow ? 150 : 165);
        bgLayers = new LayerDrawable(new Drawable[]{new ColorDrawable(0), background});
        root.setBackground(bgLayers);
        if (lastBlur != null) setBlur(lastBlur);

        content = findViewById(R.id.player_content);
        quickPanel = findViewById(R.id.quick_panel);
        art = findViewById(R.id.art);
        artShadow = findViewById(R.id.art_shadow);
        artShadow.setImageBitmap(CoverShadow.bitmap());
        title = findViewById(R.id.title);
        artist = findViewById(R.id.artist);
        tagGroup = findViewById(R.id.tag_group);
        elapsed = findViewById(R.id.elapsed);
        total = findViewById(R.id.total);
        seek = findViewById(R.id.seek);
        speedBadge = findViewById(R.id.speed_badge);
        speedLabel = findViewById(R.id.speed_label);
        speedSlider = findViewById(R.id.speed_slider);
        heatBar = findViewById(R.id.heat_bar);
        switchPitch = findViewById(R.id.switch_pitch);
        AppTheme.tintSwitch(switchPitch);
        btnShuffle = findViewById(R.id.btn_shuffle);
        btnRepeat = findViewById(R.id.btn_repeat);
        btnFav = findViewById(R.id.btn_fav);
        btnPlaylist = findViewById(R.id.btn_playlist);
        btnTune = findViewById(R.id.btn_tune);
        btnPlay = findViewById(R.id.btn_play);
        btnQueue = findViewById(R.id.btn_queue);
        btnSleep = findViewById(R.id.btn_sleep);
        prefs = ctx().getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        // Make the artwork square
        art.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            final int w = r - l;
            final ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (w > 0 && lp.height != w) {
                lp.height = w;
                v.post(() -> v.setLayoutParams(lp));
            }
        });

        View prev = findViewById(R.id.btn_prev);
        View next = findViewById(R.id.btn_next);
        for (View v : new View[]{prev, next, btnShuffle, btnRepeat, btnFav, btnPlaylist, btnTune, btnPlay}) {
            Anim.pressable(v);
        }

        findViewById(R.id.btn_back).setOnClickListener(v -> ((Host) requireActivity()).collapsePlayer());
        // dragging the top bar or the artwork down collapses the sheet
        View.OnTouchListener drag = ((Host) requireActivity()).sheetDragListener();
        findViewById(R.id.player_top_bar).setOnTouchListener(drag);
        art.setOnTouchListener(drag);
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
            String yt = ytVideoId();
            if (yt != null) {
                toggleYtLike(yt);
                return;
            }
            long id = currentSongId();
            if (id < 0) return;
            Db.get(ctx()).toggleFavorite(id);
            updateFavorite();
            Anim.pop(btnFav);
        });
        btnPlaylist.setOnClickListener(v -> {
            String yt = ytVideoId();
            if (yt != null) {
                long local = YtLocal.songIdFor(ctx(), yt);
                if (local > 0) {
                    // already on the phone: this becomes "add to playlist" for the saved copy
                    SongActions.addToPlaylistDialog(requireActivity(), local, this::updatePlaylistIndicator);
                } else {
                    downloadYt(yt);
                }
                return;
            }
            long id = currentSongId();
            if (id >= 0) SongActions.addToPlaylistDialog(requireActivity(), id, this::updatePlaylistIndicator);
        });
        btnQueue.setOnClickListener(v -> {
            startActivity(new Intent(ctx(), QueueActivity.class));
            requireActivity().overridePendingTransition(R.anim.slide_up, R.anim.stay_still);
        });
        btnSleep.setOnClickListener(v -> showSleepDialog());

        // ----- lyrics -----
        lyricsView = findViewById(R.id.lyrics_view);
        // art_frame no longer clips (the cover shadow needs room), so the lyrics clip themselves
        lyricsView.setOutlineProvider(android.view.ViewOutlineProvider.BOUNDS);
        lyricsView.setClipToOutline(true);
        btnLyrics = findViewById(R.id.btn_lyrics);
        Anim.pressable(btnLyrics);
        lyricsView.setTextColor(normalColor());
        lyricsView.setCallbacks(new LyricsView.Callbacks() {
            @Override
            public long positionMs() {
                return controller != null ? Math.max(0, controller.getCurrentPosition()) : 0;
            }

            @Override
            public boolean isPlaying() {
                return controller != null && controller.isPlaying();
            }

            @Override
            public float speed() {
                return controller != null ? controller.getPlaybackParameters().speed : 1f;
            }

            @Override
            public void onSeek(long positionMs) {
                if (controller != null) controller.seekTo(positionMs);
            }

            @Override
            public void onLongPress() {
                showLyricsMenu();
            }
        });
        btnLyrics.setOnClickListener(v -> setLyricsOpen(!lyricsOpen, true));
        btnLyrics.setOnLongClickListener(v -> { showLyricsMenu(); return true; });
        artFrame = findViewById(R.id.art_frame);
        videoBox = findViewById(R.id.video_box);
        videoView = findViewById(R.id.video_view);
        btnVideo = findViewById(R.id.btn_video);
        Anim.pressable(btnVideo);
        GradientDrawable black = new GradientDrawable();
        black.setColor(Color.BLACK);
        black.setCornerRadius(24 * getResources().getDisplayMetrics().density);
        videoBox.setBackground(black);
        videoPref = prefs.getBoolean(KEY_VIDEO, false);
        videoLayer = new VideoLayer(ctx(), videoView, new VideoLayer.Callback() {
            @Override
            public void onSize(int w, int h) {
                fitVideo(w, h);
            }

            @Override
            public void onColor(int argb) {
                onVideoColor(argb);
            }

            @Override
            public void onFailed() {
                if (!isAdded()) return;
                videoPref = false;
                prefs.edit().putBoolean(KEY_VIDEO, false).apply();
                syncVideo(true);
                Toast.makeText(ctx(), "Couldn't load the video", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onGrid(int[] cells) {
                onVideoGrid(cells);
            }

            @Override
            public void onFirstFrame() {
            }
        });
        btnVideo.setOnClickListener(v -> {
            videoPref = !videoPref;
            prefs.edit().putBoolean(KEY_VIDEO, videoPref).apply();
            syncVideo(true);
        });
        setLyricsOpen(prefs.getBoolean(KEY_LYRICS_OPEN, false), false);

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

        switchPitch.setChecked(!Prefs.getBool(ctx(), Prefs.KEEP_PITCH, true));
        switchPitch.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setBool(ctx(), Prefs.KEEP_PITCH, !checked);
            if (controller != null) applySpeed(controller.getPlaybackParameters().speed);
        });
        findViewById(R.id.btn_metadata).setOnClickListener(v -> {
            long id = currentSongId();
            if (id < 0) return;
            editorLauncher.launch(new Intent(ctx(), MetadataEditorActivity.class)
                    .putExtra(MetadataEditorActivity.EXTRA_SONG_ID, id));
        });

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
        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        YtDownloads.addListener(downloadsChanged);
        AmbientTheme.register(ambientListener);
        AmbientTheme.Palette ambientNow = AmbientTheme.current();
        if (ambientNow != null && AmbientTheme.active(ctx()) && background != null) {
            AmbientTheme.recolor(root);
            ambientListener.onPalette(ambientNow);
        }
        SessionToken token = new SessionToken(ctx(), new ComponentName(ctx(), PlaybackService.class));
        controllerFuture = new MediaController.Builder(ctx(), token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(playerListener);
                updateTrack();
                updatePlayPause();
                updateShuffleRepeat();
                updateSpeed();
                handler.removeCallbacks(ticker);
                handler.post(ticker);
            } catch (Exception e) {
                controller = null;
            }
        }, ContextCompat.getMainExecutor(ctx()));
    }

    @Override
    public void onStop() {
        if (videoLayer != null) videoLayer.hide();
        saveHeat();
        YtDownloads.removeListener(downloadsChanged);
        AmbientTheme.unregister(ambientListener);
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
    public void onDestroy() {
        io.shutdownNow();
        lyricsIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(ticker);
        if (videoAnim != null) videoAnim.cancel();
        if (videoLayer != null) videoLayer.release();
        videoLayer = null;
        view = null;
        super.onDestroyView();
    }

    // ---------- lyrics ----------

    private void setLyricsOpen(boolean open, boolean animate) {
        lyricsOpen = open;
        prefs.edit().putBoolean(KEY_LYRICS_OPEN, open).apply();
        if (animate && !videoOn) TransitionManager.beginDelayedTransition(content);
        if (!videoOn) lyricsView.setVisibility(open ? View.VISIBLE : View.GONE);
        // INVISIBLE (not GONE) so the artwork keeps its size and the lyrics fill the same square
        boolean hideCover = open || videoOn;
        art.setVisibility(hideCover ? View.INVISIBLE : View.VISIBLE);
        artShadow.setVisibility(hideCover ? View.INVISIBLE : View.VISIBLE); // fades with the cover (same transition)
        applyVideoLayout(animate && videoOn);
        btnLyrics.setImageTintList(ColorStateList.valueOf(open ? activeColor() : normalColor()));
        if (open && controller != null) {
            MediaItem item = controller.getCurrentMediaItem();
            if (item != null) loadLyrics(item, false);
        }
    }

    /** Looks the song up (cache first, then LRCLIB) and hands the result to the view. */
    private void loadLyrics(MediaItem item, boolean force) {
        if (!lyricsOpen || lyricsView == null) return;
        final String id = item.mediaId;
        if (!force && id.equals(lyricsForId)) return;
        lyricsForId = id;

        long parsedId;
        try {
            parsedId = Long.parseLong(id);
        } catch (NumberFormatException e) {
            // streamed song: a stable negative id keeps its lyrics cached without clashing with files
            parsedId = -2L - Math.abs((long) id.hashCode());
        }
        final long songId = parsedId;
        final MediaMetadata md = item.mediaMetadata;
        final String t = md.title != null ? md.title.toString() : "";
        final String a = md.artist != null ? md.artist.toString() : "";
        final String al = md.albumTitle != null ? md.albumTitle.toString() : "";
        Song s = Library.get(songId);
        long d = s != null ? s.durationMs : (controller != null ? controller.getDuration() : 0);
        final long durationMs = d == C.TIME_UNSET ? 0 : d;

        lyricsView.showLoading();
        final Context app = getApplicationContext();
        lyricsIo.execute(() -> {
            final Song sg = Library.get(songId);
            final String provider = prefs.getString("lyrics_provider", "auto");
            final Lyrics result = sg != null
                    ? LyricsRepo.load(app, songId, sg, durationMs, force, provider)
                    : LyricsRepo.load(app, songId, t, a, al, durationMs, force, provider);
            runOnUiThread(() -> {
                if (isDestroyed() || lyricsView == null || !id.equals(lyricsForId)) return;
                lyricsView.setLyrics(result);
                boolean empty = result == null || result.lines == null || result.lines.isEmpty()
                        || result.state == Lyrics.State.NOT_FOUND || result.state == Lyrics.State.ERROR;
                if (empty != lyricsEmpty) {
                    lyricsEmpty = empty;
                    if (videoOn) applyVideoLayout(true);
                }
            });
        });
    }

/** Long-press on the lyrics: edit / translate, nudge the timing or search again. */
private void showLyricsMenu() {
    final long id = currentSongId();
    if (id < 0 || lyricsView == null || controller == null) return;
    final boolean synced = lyricsView.isSynced();
    final long offset = lyricsView.getOffsetMs();
    final Db.LyricsRow row = Db.get(ctx()).getLyrics(id);
    final boolean custom = row != null && row.custom;

    final List<String> labels = new ArrayList<>();
    final List<Integer> actions = new ArrayList<>();
    labels.add("Edit / translate lyrics"); actions.add(0);
    if (synced) {
        labels.add("Lyrics earlier  (-0.5 s)"); actions.add(1);
        labels.add("Lyrics later  (+0.5 s)"); actions.add(2);
        labels.add("Reset sync"); actions.add(3);
    }
    labels.add(custom ? "Discard my version and search online" : "Search again"); actions.add(4);
    
    // --- CHANGE 1: Update the label at the top to support BiniLyrics ---
    String selectedProvider = prefs.getString("lyrics_provider", "auto");
    String providerLabel = "lrclib".equals(selectedProvider) ? "LRCLIB" :
            "lyricsplus".equals(selectedProvider) ? "LyricsPlus" : 
            "binilyrics".equals(selectedProvider) ? "BiniLyrics" : "Automatic (recommended)";
    labels.add("Lyrics source · " + providerLabel); actions.add(5);

    new ThemedDialogBuilder(requireActivity())
            .setTitle(synced ? String.format(Locale.US, "Lyrics sync: %+.1f s", offset / 1000f)
                    : custom ? "Lyrics (your version)" : "Lyrics")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                int action = actions.get(which);
                if (action == 0) {
                    MediaItem item = controller != null ? controller.getCurrentMediaItem() : null;
                    String name = item != null && item.mediaMetadata.title != null
                            ? item.mediaMetadata.title.toString() : "lyrics";
                    if (controller != null && controller.isPlaying()) controller.pause();
                    lyricsEditorLauncher.launch(new Intent(ctx(), LyricsEditorActivity.class)
                            .putExtra(LyricsEditorActivity.EXTRA_SONG_ID, id)
                            .putExtra(LyricsEditorActivity.EXTRA_NAME, name));
                    return;
                }
                if (action == 4) {
                    if (custom) Db.get(ctx()).deleteLyrics(id);
                    MediaItem item = controller != null ? controller.getCurrentMediaItem() : null;
                    if (item != null) loadLyrics(item, true);
                    return;
                }
                if (action == 5) {
                    // --- CHANGE 2: Add BiniLyrics to the sources array ---
                    String[] sources = {
                        "Automatic (LyricsPlus → BiniLyrics → LRCLIB)", 
                        "LRCLIB only", 
                        "LyricsPlus only", 
                        "BiniLyrics only"
                    };
                    
                    String current = prefs.getString("lyrics_provider", "auto");
                    
                    // --- CHANGE 3: Update the checked index logic ---
                    int checked = "lrclib".equals(current) ? 1 : 
                                  "lyricsplus".equals(current) ? 2 : 
                                  "binilyrics".equals(current) ? 3 : 0;
                                  
                    new ThemedDialogBuilder(requireActivity())
                            .setTitle("Choose lyrics source")
                            .setSingleChoiceItems(sources, checked, (d, selectedIndex) -> {
                                // --- CHANGE 4: Map the selected index to the correct string value ---
                                String value = selectedIndex == 1 ? "lrclib" : 
                                               selectedIndex == 2 ? "lyricsplus" : 
                                               selectedIndex == 3 ? "binilyrics" : "auto";
                                               
                                prefs.edit().putString("lyrics_provider", value).apply();
                                Db.LyricsRow cached = Db.get(ctx()).getLyrics(id);
                                if (cached != null && !cached.custom) Db.get(ctx()).deleteLyrics(id);
                                MediaItem item = controller != null ? controller.getCurrentMediaItem() : null;
                                if (item != null) loadLyrics(item, true);
                                d.dismiss();
                            }).show();
                    return;
                }
                long next = action == 1 ? offset - 500 : action == 2 ? offset + 500 : 0;
                lyricsView.setOffsetMs(next);
                Db.get(ctx()).setLyricsOffset(id, next);
            })
            .show();
}

    // ---------- quick settings panel ----------

    private void setQuickOpen(boolean open, boolean animate) {
        quickOpen = open;
        prefs.edit().putBoolean(KEY_QUICK_OPEN, open).apply();
        if (animate) TransitionManager.beginDelayedTransition(content);
        quickPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        btnTune.setImageTintList(ColorStateList.valueOf(open ? activeColor() : normalColor()));
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
        loadLyrics(item, false);
        updateFavorite();
        updatePlaylistIndicator();
        updateProgress();
        syncVideo(false);
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
            Chip chip = new Chip(ctx());
            chip.setText(t);
            chip.setClickable(false);
            chip.setCheckable(false);
            chip.setFocusable(false);
            chip.setEnsureMinTouchTargetSize(false);
            tagGroup.addView(chip);
        }
        tagGroup.setVisibility(tagGroup.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    /** Video id of the playing song when it is streamed from YouTube Music, else null. */
    @Nullable
    private String ytVideoId() {
        if (controller == null) return null;
        MediaItem it = controller.getCurrentMediaItem();
        return it != null && YtTrack.isYtId(it.mediaId)
                ? it.mediaId.substring(YtTrack.ID_PREFIX.length()) : null;
    }

    /** The heart likes the song on YouTube Music. */
    private void toggleYtLike(final String videoId) {
        if (!YtAuth.isSignedIn(ctx())) {
            Toast.makeText(ctx(), "Sign in to YouTube (Settings) to like songs", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean liked = !YtLikes.isLiked(ctx(), videoId);
        YtLikes.set(ctx(), videoId, liked);
        updateFavorite();
        Anim.pop(btnFav);
        final Context app = ctx().getApplicationContext();
        io.execute(() -> {
            try {
                YtMusicApi.setLiked(app, videoId, liked);
            } catch (Exception e) {
                YtLikes.set(app, videoId, !liked);
                if (isAdded()) requireActivity().runOnUiThread(() -> {
                    updateFavorite();
                    Toast.makeText(app, "Couldn't update the like: "
                            + (e.getMessage() == null ? "network error" : e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void downloadYt(String videoId) {
        MediaItem cur = controller == null ? null : controller.getCurrentMediaItem();
        if (cur == null) return;
        MediaMetadata m = cur.mediaMetadata;
        YtDownloads.start(ctx(), videoId,
                m.title == null ? "" : m.title.toString(),
                m.artist == null ? "" : m.artist.toString(),
                m.artworkUri == null ? "" : m.artworkUri.toString());
        Anim.pop(btnPlaylist);
    }

    private final Runnable downloadsChanged = () -> {
        if (isAdded()) updatePlaylistIndicator();
    };

    /** The cover glow behind the player follows the ambient palette; the widgets are recolored generically. */
    private final AmbientTheme.Listener ambientListener = p -> {
        if (!isAdded() || background == null || videoOn) return;
        surfaceColor = p.bg;
        backgroundTop = p.soft;
        background.setColors(new int[]{p.soft, p.bg});
        paintControls(p);
    };

    @Nullable
    private AmbientTheme.Palette amb() {
        if (videoOn && videoPal != null) return videoPal;
        return AmbientTheme.active(ctx()) ? AmbientTheme.current() : null;
    }

    private int activeColor() {
        AmbientTheme.Palette p = amb();
        return p != null ? p.accent : bActive;
    }

    private int inactiveColor() {
        AmbientTheme.Palette p = amb();
        return p != null ? p.onVariant : bInactive;
    }

    private int normalColor() {
        AmbientTheme.Palette p = amb();
        return p != null ? p.text : bNormal;
    }

    private static int alpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private static ColorStateList csl(int c) { return ColorStateList.valueOf(c); }

    /**
     * Frosted: a flat, soft, milky tint of the color (no outline, no gradient). The ambient
     * background shows through it, so it takes on the surrounding color.
     */
    private GradientDrawable glassShape(int fill, boolean oval, float radiusDp) {
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(alpha(lighter(fill, 0.28f), 0x6E));
        if (oval) g.setShape(GradientDrawable.OVAL);
        else g.setCornerRadius(radiusDp * d);
        return g;
    }

    private static int lighter(int c, float amount) {
        return android.graphics.Color.rgb(
                Math.round(android.graphics.Color.red(c) + (255 - android.graphics.Color.red(c)) * amount),
                Math.round(android.graphics.Color.green(c) + (255 - android.graphics.Color.green(c)) * amount),
                Math.round(android.graphics.Color.blue(c) + (255 - android.graphics.Color.blue(c)) * amount));
    }

    /** A frosted button / panel whose two ends take the colors of the ambient mesh right behind them. */
    private static final class Frost {
        View v;
        GradientDrawable g;
    }

    private final java.util.ArrayList<Frost> frosts = new java.util.ArrayList<>();

    private void frost(View v, boolean oval, float radiusDp, AmbientTheme.Palette p) {
        float d = getResources().getDisplayMetrics().density;
        Frost f = null;
        for (Frost x : frosts) if (x.v == v) f = x;
        if (f == null) {
            f = new Frost();
            f.v = v;
            frosts.add(f);
        }
        int base = alpha(lighter(p.control, 0.28f), 0x6E);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{base, base});
        if (oval) g.setShape(GradientDrawable.OVAL);
        else g.setCornerRadius(radiusDp * d);
        f.g = g;
        v.setBackground(new android.graphics.drawable.RippleDrawable(csl(alpha(p.accent, 0x33)), g, null));
    }

    /** The quick panel is real frosted glass: the cover, softly blurred, with a tint over it. */
    private void paintQuickPanel(AmbientTheme.Palette p) {
        if (quickPanel == null) return;
        unfrost(quickPanel);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (panelBg == null) {
            panelBg = new FrostPanelDrawable(quickPanel, art, 20 * getResources().getDisplayMetrics().density);
            panelBg.setBitmap(panelBlur);
            quickPanel.setBackground(panelBg);
        }
        int base = lighter(p.control, night ? 0.1f : 0.35f);
        panelBg.setColors(alpha(base, night ? 0x9A : 0x99), alpha(base, 0xE6));
    }

    /** Closes the quick panel without animation (the player is being closed). */
    public void closeQuickPanel() {
        if (quickOpen && quickPanel != null && isAdded()) setQuickOpen(false, false);
    }

    private void unfrost(View v) {
        for (int i = frosts.size() - 1; i >= 0; i--) if (frosts.get(i).v == v) frosts.remove(i);
    }

    private static int frostTone(int c) {
        return alpha(lighter(c, 0.34f), 0x8C);
    }

    /** Re-colors every frosted control from the ambient mesh at its own place on the screen. */
    private void updateFrost() {
        CornerGlow g = glow != null ? glow : coverGlow;
        if (g == null || view == null || frosts.isEmpty()) return;
        int W = view.getWidth(), H = view.getHeight();
        if (W == 0 || H == 0) return;
        int[] rl = new int[2], vl = new int[2];
        view.getLocationInWindow(rl);
        for (Frost f : frosts) {
            if (f.v.getWidth() == 0 || f.g == null) continue;
            f.v.getLocationInWindow(vl);
            float y = (vl[1] - rl[1] + f.v.getHeight() / 2f) / H;
            float x0 = (vl[0] - rl[0]) / (float) W;
            float x1 = x0 + f.v.getWidth() / (float) W;
            f.g.setColors(new int[]{frostTone(g.colorAt(x0, y)), frostTone(g.colorAt(x1, y))});
        }
    }

    private void tonal(@Nullable MaterialButton b, AmbientTheme.Palette p) {
        if (b == null) return;
        b.setBackgroundTintList(null);
        b.setStrokeWidth(0);
        frost(b, false, 100, p);          // frosted, gradient of the ambient colors behind it
        b.setTextColor(p.onTonal);
        b.setIconTint(csl(p.onTonal));
        b.setRippleColor(csl(alpha(p.onTonal, 0x30)));
    }

    private void slider(@Nullable Slider s, AmbientTheme.Palette p) {
        if (s == null) return;
        s.setThumbTintList(csl(p.accent));
        s.setTrackActiveTintList(csl(p.accent));
        s.setTrackInactiveTintList(csl(p.variant));
        s.setHaloTintList(csl(alpha(p.accent, 0x33)));
        s.setTickActiveTintList(csl(alpha(p.onAccent, 0xAA)));
        s.setTickInactiveTintList(csl(alpha(p.accent, 0x99)));
    }

    /**
     * Colors every player control straight from the cover palette. The generic recolor walker only
     * swaps colors it recognises, which missed the tonal buttons, the play button and the round
     * transport buttons, so those are set here explicitly on every palette change.
     */
    private void paintControls(AmbientTheme.Palette p) {
        if (view == null) return;
        // play button
        btnPlay.setBackgroundTintList(csl(alpha(p.accent, 0xE6)));      // the one bright control, a touch of glass
        btnPlay.setImageTintList(csl(p.onAccent));
        btnPlay.setRippleColor(alpha(p.onAccent, 0x30));
        // round transport buttons
        int ripple = alpha(p.accent, 0x33);
        int[] ids = {R.id.btn_shuffle, R.id.btn_prev, R.id.btn_next, R.id.btn_repeat};
        for (int id : ids) {
            View v = view.findViewById(id);
            if (v != null) frost(v, true, 0, p);
        }
        ((ImageButton) view.findViewById(R.id.btn_prev)).setImageTintList(csl(p.text));
        ((ImageButton) view.findViewById(R.id.btn_next)).setImageTintList(csl(p.text));
        updateShuffleRepeat();
        // tonal buttons (Queue, Sleep timer, Edit metadata) and the Reset text button
        tonal(btnQueue, p);
        tonal(btnSleep, p);
        tonal((MaterialButton) view.findViewById(R.id.btn_metadata), p);
        MaterialButton reset = view.findViewById(R.id.btn_speed_reset);
        if (reset != null) {
            reset.setTextColor(p.accent);
            reset.setRippleColor(csl(alpha(p.accent, 0x30)));
        }
        // sliders, speed badge, pitch switch, quick panel
        slider(seek, p);
        slider(speedSlider, p);
        speedBadge.setTextColor(p.accent);
        if (heatBar != null) heatBar.setTint(p.accent);
        int[][] st = {{android.R.attr.state_checked}, {}};
        switchPitch.setThumbTintList(new ColorStateList(st, new int[]{p.onAccent, p.onVariant}));
        switchPitch.setTrackTintList(new ColorStateList(st, new int[]{p.accent, p.variant}));
        paintQuickPanel(p);
        // text
        title.setTextColor(p.text);
        artist.setTextColor(p.onVariant);
        elapsed.setTextColor(p.onVariant);
        total.setTextColor(p.onVariant);
        speedLabel.setTextColor(p.text);
        ((ImageButton) view.findViewById(R.id.btn_back)).setImageTintList(csl(p.text));
        updateFrost();
    }

    // ---------- video ----------

    private int dpx(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The song's YouTube id: the streamed id, or the id a downloaded copy came from. */
    @Nullable
    private String baseVideoId(@Nullable MediaItem it) {
        if (it == null) return null;
        if (YtTrack.isYtId(it.mediaId)) return it.mediaId.substring(YtTrack.ID_PREFIX.length());
        try {
            return YtLocal.videoIdForSong(ctx(), Long.parseLong(it.mediaId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Id of the song's real music video, or null while unknown / when YouTube Music has none.
     * The answer comes from YouTube (asked once per song, in the background).
     */
    @Nullable
    private String videoIdFor(@Nullable MediaItem it) {
        final String base = baseVideoId(it);
        if (base == null) return null;
        String known = YtMusicApi.peekVideo(base);
        if (known != null) return known.isEmpty() ? null : known;
        if (!base.equals(videoAsked)) {
            videoAsked = base;
            final Context app = getApplicationContext();
            io.execute(() -> {
                try {
                    YtMusicApi.musicVideoFor(app, base);
                } catch (Exception e) {
                    videoAsked = null;      // try again next time
                    return;
                }
                runOnUiThread(() -> {
                    if (isDestroyed() || view == null) return;
                    syncVideo(true);
                });
            });
        }
        return null;
    }

    /** Shows or hides the video to match the button and the current song. */
    private void syncVideo(boolean animate) {
        if (view == null || videoLayer == null) return;
        MediaItem it = controller == null ? null : controller.getCurrentMediaItem();
        String vid = videoIdFor(it);
        btnVideo.setVisibility(vid != null ? View.VISIBLE : View.GONE);
        boolean want = videoPref && vid != null;
        if (want != videoOn) {
            videoOn = want;
            videoBox.animate().cancel();
            art.animate().cancel();
            artShadow.animate().cancel();
            if (want) {
                lyricsBeforeVideo = lyricsOpen;
                lyricsOpen = true;                 // the lyrics go under the video
                videoPal = null;
                lastVideoSeed = 0;
                if (it != null) loadLyrics(it, false);
                videoBox.setAlpha(animate ? 0f : 1f);
                videoBox.setVisibility(View.VISIBLE);
                if (animate) videoBox.animate().alpha(1f).setDuration(260).start();
                if (animate) {
                    art.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                        art.setVisibility(View.INVISIBLE);
                        art.setAlpha(1f);
                    }).start();
                    artShadow.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                        artShadow.setVisibility(View.INVISIBLE);
                        artShadow.setAlpha(1f);
                    }).start();
                } else {
                    art.setVisibility(View.INVISIBLE);
                    artShadow.setVisibility(View.INVISIBLE);
                }
            } else {
                lyricsOpen = lyricsBeforeVideo;
                videoLayer.hide();
                if (animate) {
                    videoBox.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                        videoBox.setVisibility(View.GONE);
                        videoBox.setAlpha(1f);
                    }).start();
                } else {
                    videoBox.setVisibility(View.GONE);
                }
                boolean cover = !lyricsOpen;
                art.setVisibility(cover ? View.VISIBLE : View.INVISIBLE);
                artShadow.setVisibility(cover ? View.VISIBLE : View.INVISIBLE);
                art.setAlpha(animate && cover ? 0f : 1f);
                artShadow.setAlpha(animate && cover ? 0f : 1f);
                if (animate && cover) {
                    art.animate().alpha(1f).setDuration(260).start();
                    artShadow.animate().alpha(1f).setDuration(260).start();
                }
                endVideoLook();
            }
            btnLyrics.setImageTintList(ColorStateList.valueOf(lyricsOpen ? activeColor() : normalColor()));
            applyVideoLayout(animate);
        }
        btnVideo.setImageTintList(ColorStateList.valueOf(videoOn ? activeColor() : normalColor()));
        if (videoOn && vid != null) videoLayer.show(vid, controller);
    }

    private void applyVideoLayout() {
        applyVideoLayout(false);
    }

    /**
     * Frame layout. Normal: cover, 300 dp. Video mode: the video on top and, when there are lyrics and
     * they are on, the lyrics below it. The frame height is animated by hand (one smooth move, no
     * transition framework) so nothing jumps when the lyrics come or go.
     */
    private void applyVideoLayout(boolean animate) {
        if (artFrame == null || videoBox == null || lyricsView == null) return;
        int w = artFrame.getWidth();
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels - dpx(44);
        final int vh = Math.round(w * 9f / 16f);
        FrameLayout.LayoutParams vl = (FrameLayout.LayoutParams) videoBox.getLayoutParams();
        vl.height = vh;
        videoBox.setLayoutParams(vl);

        final boolean showLyr = videoOn ? (lyricsOpen && !lyricsEmpty) : lyricsOpen;
        final int lyrH = dpx(240);
        final int target = videoOn ? (showLyr ? vh + dpx(12) + lyrH : vh) : dpx(300);

        final ViewGroup.LayoutParams al = art.getLayoutParams();
        final FrameLayout.LayoutParams ll = (FrameLayout.LayoutParams) lyricsView.getLayoutParams();
        if (frameAnim != null) frameAnim.cancel();

        if (videoOn) {
            ll.topMargin = vh + dpx(12);
            ll.height = lyrH;                  // fixed, so it never stretches the frame by itself
        } else {
            ll.topMargin = 0;
            ll.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        lyricsView.setLayoutParams(ll);

        final int from = al.height > 0 ? al.height : target;
        if (!animate || from == target || !art.isAttachedToWindow()) {
            al.height = target;
            art.setLayoutParams(al);
            lyricsView.setVisibility(showLyr ? View.VISIBLE : View.GONE);
            lyricsView.setAlpha(1f);
            return;
        }
        final boolean lyrChanging = showLyr != (lyricsView.getVisibility() == View.VISIBLE);
        if (lyrChanging && showLyr) {
            lyricsView.setAlpha(0f);
            lyricsView.setVisibility(View.VISIBLE);
        }
        ValueAnimator a = ValueAnimator.ofInt(from, target);
        a.setDuration(240);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        a.addUpdateListener(v -> {
            al.height = (int) v.getAnimatedValue();
            art.setLayoutParams(al);
            float f = v.getAnimatedFraction();
            if (lyrChanging) lyricsView.setAlpha(showLyr ? f : 1f - f);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                lyricsView.setAlpha(1f);
                lyricsView.setVisibility(showLyr ? View.VISIBLE : View.GONE);
            }
        });
        a.start();
        frameAnim = a;
    }

    /** Fits the picture inside the 16:9 box without cropping or stretching it. */
    private void fitVideo(final int w, final int h) {
        if (videoBox == null) return;
        videoBox.post(() -> {
            if (videoBox == null || videoView == null) return;
            int bw = videoBox.getWidth(), bh = videoBox.getHeight();
            if (bw <= 0 || bh <= 0) return;
            float ar = w / (float) h;
            int tw, th;
            if (ar > bw / (float) bh) {
                tw = bw;
                th = Math.round(bw / ar);
            } else {
                th = bh;
                tw = Math.round(bh * ar);
            }
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) videoView.getLayoutParams();
            p.width = tw;
            p.height = th;
            p.gravity = android.view.Gravity.CENTER;
            videoView.setLayoutParams(p);
        });
    }

    /** The picture's average color becomes a dark palette for the whole player. */
    private void onVideoColor(int argb) {
        if (!videoOn || !isAdded()) return;
        float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);
        if (hsv[1] > 0.12f) hsv[1] = Math.min(1f, hsv[1] * 1.25f);
        int seed = Color.HSVToColor(hsv);
        if (lastVideoSeed != 0) {
            int d = Math.abs(Color.red(seed) - Color.red(lastVideoSeed))
                    + Math.abs(Color.green(seed) - Color.green(lastVideoSeed))
                    + Math.abs(Color.blue(seed) - Color.blue(lastVideoSeed));
            if (d < Prefs.videoSkipDist(ctx())) return;         // nothing to see, don't repaint everything
        }
        lastVideoSeed = seed;
        final int[] to = AmbientTheme.darkRoles(seed);
        final int[] from = videoRoles == null ? to : videoRoles.clone();
        if (videoAnim != null) videoAnim.cancel();
        final int blend = Prefs.videoBlendMs(ctx());
        if (blend <= 0) {                // Live: follow the video instantly
            videoRoles = to;
            applyVideoPalette(to);
            return;
        }
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(blend);
        a.addUpdateListener(v -> {
            if (!videoOn || !isAdded()) return;
            float f = (float) v.getAnimatedValue();
            int[] next = new int[to.length];
            for (int i = 0; i < next.length; i++) next[i] = (int) ev.evaluate(f, from[i], to[i]);
            videoRoles = next;
            applyVideoPalette(next);
        });
        a.start();
        videoAnim = a;
    }

    /** Corner to corner: each cell of the picture becomes a color of the background (dark, so text stays readable). */
    private void onVideoGrid(int[] cells) {
        if (!videoOn || !isAdded() || bgLayers == null) return;
        final int[] to = new int[9];
        float[] hsv = new float[3];
        for (int i = 0; i < 9; i++) {
            Color.colorToHSV(cells[i], hsv);
            hsv[1] = Math.min(0.55f, hsv[1] * 0.78f);
            hsv[2] = Math.max(0.20f, Math.min(0.50f, hsv[2] * 0.72f));
            to[i] = Color.HSVToColor(hsv);
        }
        if (glow == null) {
            glow = new CornerGlow();
            glow.set(to);
            bgLayers.setDrawable(0, glow);
            background.setAlpha(28);
            updateFrost();
            return;
        }
        final CornerGlow g = glow;
        final int[] from = g.colors();
        if (glowAnim != null) glowAnim.cancel();
        final int gblend = Prefs.videoBlendMs(ctx());
        if (gblend <= 0) {
            g.set(to);
            updateFrost();
            return;
        }
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(gblend);
        a.addUpdateListener(v -> {
            float f = (float) v.getAnimatedValue();
            int[] next = new int[9];
            for (int i = 0; i < 9; i++) next[i] = (int) ev.evaluate(f, from[i], to[i]);
            g.set(next);
            updateFrost();
        });
        a.start();
        glowAnim = a;
    }

    private static int mix(int a, int b, float t) {
        return (Integer) new android.animation.ArgbEvaluator().evaluate(t, a, b);
    }

    private void applyVideoPalette(int[] roles) {
        if (background == null || view == null) return;
        AmbientTheme.Palette p = AmbientTheme.paletteOf(roles);
        videoPal = p;
        surfaceColor = p.bg;
        backgroundTop = mix(p.soft, p.bg, 0.55f);
        background.setAlpha(glow != null ? 28 : 255);
        background.setColors(new int[]{backgroundTop, p.bg});
        if (bgLayers != null) {
            if (glow != null) {
                if (bgLayers.getDrawable(0) != glow) bgLayers.setDrawable(0, glow);
                background.setAlpha(28);
            } else {
                bgLayers.setDrawable(0, new ColorDrawable(0));
            }
        }
        paintControls(p);
        refreshTints();
    }

    /** Icons and lyric text follow whatever palette is active (video, ambient or plain theme). */
    private void refreshTints() {
        if (btnLyrics == null) return;
        btnLyrics.setImageTintList(ColorStateList.valueOf(lyricsOpen ? activeColor() : normalColor()));
        btnTune.setImageTintList(ColorStateList.valueOf(quickOpen ? activeColor() : normalColor()));
        btnVideo.setImageTintList(ColorStateList.valueOf(videoOn ? activeColor() : normalColor()));
        lyricsView.setTextColor(normalColor());
        updateFavorite();
        updatePlaylistIndicator();
    }

    /** Back from the video look to the cover's (or the theme's) colors. */
    private void endVideoLook() {
        if (videoAnim != null) videoAnim.cancel();
        if (glowAnim != null) glowAnim.cancel();
        glow = null;
        coverGlow = null;
        if (bgLayers != null) bgLayers.setDrawable(0, new ColorDrawable(0));
        videoRoles = null;
        videoPal = null;
        if (background == null || view == null) return;
        boolean nightNow = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        background.setAlpha(nightNow ? 150 : 165);
        AmbientTheme.Palette amb = amb();
        if (amb != null) {
            ambientListener.onPalette(amb);
        } else {
            surfaceColor = baseSurface;
            backgroundTop = baseSurface;
            background.setColors(new int[]{baseSurface, baseSurface});
            paintControls(AmbientTheme.themePalette(ctx()));
            animateBackground(lastTint != 0 ? lastTint : baseSurface);
        }
        if (lastBlur != null) setBlur(lastBlur);
        refreshTints();
    }

    private void updateFavorite() {
        String yt = ytVideoId();
        if (yt != null) {
            boolean liked = YtLikes.isLiked(ctx(), yt);
            btnFav.setImageResource(liked ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            btnFav.setImageTintList(ColorStateList.valueOf(liked ? activeColor() : normalColor()));
            return;
        }
        long id = currentSongId();
        if (id < 0) {
            btnFav.setImageResource(R.drawable.ic_favorite_border);
            btnFav.setImageTintList(ColorStateList.valueOf(normalColor()));
            return;
        }
        boolean fav = Db.get(ctx()).isFavorite(id);
        btnFav.setImageResource(fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        btnFav.setImageTintList(ColorStateList.valueOf(fav ? activeColor() : normalColor()));
    }

    /** The playlist button shows a check (in the accent color) when the song is already in a playlist. */
    private void updatePlaylistIndicator() {
        String yt = ytVideoId();
        if (yt != null) {
            // streamed song: downloads it; once it is on the phone it adds the saved copy to a playlist
            long local = YtLocal.songIdFor(ctx(), yt);
            if (local > 0) {
                boolean inAny = !Db.get(ctx()).playlistsContaining(local).isEmpty();
                btnPlaylist.setImageResource(inAny ? R.drawable.ic_playlist_add_check : R.drawable.ic_playlist_add);
                btnPlaylist.setImageTintList(ColorStateList.valueOf(inAny ? activeColor() : normalColor()));
            } else {
                btnPlaylist.setImageResource(R.drawable.ic_download);
                btnPlaylist.setImageTintList(ColorStateList.valueOf(normalColor()));
            }
            return;
        }
        long id = currentSongId();
        if (id < 0) {
            btnPlaylist.setImageResource(R.drawable.ic_playlist_add);
            btnPlaylist.setImageTintList(ColorStateList.valueOf(normalColor()));
            return;
        }
        boolean inAny = !Db.get(ctx()).playlistsContaining(id).isEmpty();
        btnPlaylist.setImageResource(inAny ? R.drawable.ic_playlist_add_check : R.drawable.ic_playlist_add);
        btnPlaylist.setImageTintList(ColorStateList.valueOf(inAny ? activeColor() : normalColor()));
    }

    private void updatePlayPause() {
        if (controller == null) return;
        boolean playing = controller.isPlaying();
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        // artwork "breathes": full size while playing, slightly smaller when paused
        float scale = playing ? 1f : 0.9f;
        art.animate().scaleX(scale).scaleY(scale).setDuration(300).start();
        artShadow.animate().scaleX(scale).scaleY(scale).setDuration(300).start();
    }

    private void updateShuffleRepeat() {
        if (controller == null) return;
        boolean shuffle = controller.getShuffleModeEnabled();
        int mode = controller.getRepeatMode();
        btnRepeat.setImageResource(mode == Player.REPEAT_MODE_ONE
                ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        AmbientTheme.Palette p = amb();
        if (p != null) {
            // ON = a solid filled circle with a contrasting icon, OFF = the frosted circle with a dim icon:
            // readable on any color, even when the accent and the surface are both light.
            toggleLook(btnShuffle, shuffle, p);
            toggleLook(btnRepeat, mode != Player.REPEAT_MODE_OFF, p);
            return;
        }
        btnShuffle.setImageTintList(ColorStateList.valueOf(shuffle ? activeColor() : inactiveColor()));
        btnRepeat.setImageTintList(ColorStateList.valueOf(
                mode == Player.REPEAT_MODE_OFF ? inactiveColor() : activeColor()));
    }

    private void toggleLook(ImageButton b, boolean on, AmbientTheme.Palette p) {
        int ripple = alpha(p.accent, 0x33);
        if (on) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(p.accent);
            unfrost(b);
            b.setBackground(new android.graphics.drawable.RippleDrawable(csl(alpha(p.onAccent, 0x30)), g, null));
            b.setImageTintList(csl(p.onAccent));
        } else {
            frost(b, true, 0, p);
            updateFrost();
            b.setImageTintList(csl(alpha(p.onVariant, 0xB0)));
        }
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
        trackHeat(duration, known);
    }

    // ---------- most played part ----------

    private void trackHeat(long duration, boolean known) {
        if (heatBar == null || controller == null) return;
        MediaItem item = controller.getCurrentMediaItem();
        boolean on = Prefs.getBool(ctx(), Prefs.HEATMAP, true) && item != null && known;
        if (!on) {
            heatBar.setVisibility(View.INVISIBLE);
            return;
        }
        String key = item.mediaId;
        if (!key.equals(heatKey)) {
            saveHeat();
            heatKey = key;
            heat = HeatBar.load(ctx(), key);
            heatLast = -1;
            heatLastPos = -1;
        }
        long pos = Math.max(0, controller.getCurrentPosition());
        if (controller.isPlaying()) {
            int bucket = (int) Math.min(HeatBar.SLICES - 1, pos * HeatBar.SLICES / duration);
            boolean smooth = heatLastPos >= 0 && pos >= heatLastPos && pos - heatLastPos < 1800;
            if (bucket != heatLast) {
                if (smooth && heatLast >= 0) {   // heard through into a new slice (a seek does not count)
                    heat[bucket]++;
                    heatDirty = true;
                } else if (heatLast < 0 && heat[bucket] == 0) {
                    heat[bucket]++;              // the slice playback started in
                    heatDirty = true;
                }
                heatLast = bucket;
            }
        }
        heatLastPos = pos;
        boolean visible = heatBar.setData(heat);
        heatBar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        long now = android.os.SystemClock.elapsedRealtime();
        if (heatDirty && now - heatSaved > 20_000) saveHeat();
    }

    private void saveHeat() {
        if (heatKey != null && heatDirty) {
            HeatBar.save(ctx(), heatKey, heat);
            heatDirty = false;
            heatSaved = android.os.SystemClock.elapsedRealtime();
        }
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
        boolean keepPitch = Prefs.getBool(ctx(), Prefs.KEEP_PITCH, true);
        controller.setPlaybackParameters(new PlaybackParameters(s, keepPitch ? 1f : s));
    }

    private void applySpeed(float speed) {
        if (controller == null) return;
        float s = snapSpeed(speed);
        boolean keepPitch = Prefs.getBool(ctx(), Prefs.KEEP_PITCH, true);
        controller.setPlaybackParameters(new PlaybackParameters(s, keepPitch ? 1f : s));
        if (Prefs.getBool(ctx(), Prefs.SPEED_PER_TRACK, false)) {
            long id = currentSongId();
            if (id >= 0) Db.get(ctx()).setSpeed(id, s);
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
        ArtLoader.load(ctx(), s, editArt, 256);

        form.findViewById(R.id.btn_change_photo).setOnClickListener(v -> photoPicker.launch("image/*"));
        form.findViewById(R.id.btn_remove_photo).setOnClickListener(v -> {
            pickedPhoto = null;
            pickedPreview = null;
            photoRemoved = true;
            // show the file's own art again
            ArtLoader.load(ctx(), s.id, s.uri, s.rawArtUri, editArt, 256 + 1,
                    R.drawable.ic_art_placeholder);
        });

        new ThemedDialogBuilder(ctx())
                .setTitle("Edit metadata")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", (d, w) -> {
                    deleteCustomArt(s.id);
                    Db.get(ctx()).clearMeta(s.id);
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
            String[] old = Db.get(ctx()).getMeta(s.id);
            String artPath = old == null ? "" : old[3];
            if (removed) {
                deleteCustomArt(s.id);
                artPath = "";
            } else if (picked != null) {
                String saved = storeCustomArt(s.id, picked);
                if (saved != null) artPath = saved;
                else runOnUiThread(() -> Toast.makeText(ctx(), "Couldn't read that photo",
                        Toast.LENGTH_SHORT).show());
            }
            Db.get(ctx()).setMeta(s.id, newTitle, newArtist, newAlbum, artPath);
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
            if (lyricsOpen) loadLyrics(updated.toMediaItem(), true);   // new title = new lookup
        }
        Toast.makeText(ctx(), "Metadata updated", Toast.LENGTH_SHORT).show();
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
        new ThemedDialogBuilder(ctx())
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
        Toast.makeText(ctx(), minutes > 0 ? "Music will pause in " + minutes + " min"
                : "Sleep timer off", Toast.LENGTH_SHORT).show();
    }

    // ---------- album art + background tint ----------

    /** The cover, blurred into mush, as a soft background that fades in behind the color gradient. */
    private void setBlur(@Nullable Bitmap b) {
        if (bgLayers == null) return;
        if (videoOn && glow != null) return;      // the video's corner colors own the background
        if (blurAnim != null) blurAnim.cancel();
        if (b == null) {
            bgLayers.setDrawable(0, new ColorDrawable(0));
            return;
        }
        if (videoOn) {
            bgLayers.setDrawable(0, new ColorDrawable(0));
            return;
        }
        if (Prefs.getBool(ctx(), Prefs.BG_MESH, true)) {
            setMesh(b);
            return;
        }
        coverGlow = null;
        boolean nightNow = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        background.setAlpha(nightNow ? 150 : 165);
        final Drawable d = new Blur.Backdrop(b);
        d.setAlpha(0);
        bgLayers.setDrawable(0, d);
        blurAnim = ValueAnimator.ofInt(0, 200);
        blurAnim.setDuration(350);
        blurAnim.addUpdateListener(a -> d.setAlpha((int) a.getAnimatedValue()));
        blurAnim.start();
    }

    /** The cover's 3 x 3 average colors become a smooth corner-to-corner background. */
    private void setMesh(Bitmap b) {
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int[] cells = VideoLayer.grid(b);
        final int[] to = new int[9];
        float[] hsv = new float[3];
        for (int i = 0; i < 9; i++) {
            Color.colorToHSV(cells[i], hsv);
            if (night) {
                hsv[1] = Math.min(0.55f, hsv[1] * 0.78f);
                hsv[2] = Math.max(0.20f, Math.min(0.48f, hsv[2] * 0.7f));
            } else {
                hsv[1] = Math.min(0.55f, hsv[1] * 0.7f);
                hsv[2] = Math.max(0.84f, Math.min(0.98f, 0.8f + hsv[2] * 0.2f));
            }
            to[i] = Color.HSVToColor(hsv);
        }
        background.setAlpha(night ? 56 : 70);        // the base tint stays faintly on top
        final CornerGlow g;
        final int[] from;
        if (coverGlow == null) {
            g = new CornerGlow();
            g.set(to);
            g.setAlpha(0);
            coverGlow = g;
            bgLayers.setDrawable(0, g);
            blurAnim = ValueAnimator.ofInt(0, 255);
            blurAnim.setDuration(350);
            blurAnim.addUpdateListener(a -> g.setAlpha((int) a.getAnimatedValue()));
            blurAnim.start();
            view.post(this::updateFrost);
            return;
        }
        g = coverGlow;
        from = g.colors();
        if (bgLayers.getDrawable(0) != g) bgLayers.setDrawable(0, g);
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        blurAnim = ValueAnimator.ofFloat(0f, 1f);
        blurAnim.setDuration(400);
        blurAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            int[] next = new int[9];
            for (int i = 0; i < 9; i++) next[i] = (int) ev.evaluate(f, from[i], to[i]);
            g.set(next);
            updateFrost();
        });
        blurAnim.start();
    }

    private void loadArt(MediaItem item) {
        final String id = item.mediaId;
        if (id.equals(artForId)) return;
        artForId = id;

        final Uri songUri = item.localConfiguration != null ? item.localConfiguration.uri : null;
        final Uri artUri = item.mediaMetadata.artworkUri;

        io.execute(() -> {
            final Bitmap bmp = ArtLoader.decode(getApplicationContext(), songUri, artUri, 768);
            final int tint = bmp != null ? tintFrom(bmp) : 0;
            final Bitmap blur = bmp != null ? Blur.make(bmp) : null;
            final Bitmap soft = bmp != null ? Blur.soft(bmp) : null;
            runOnUiThread(() -> {
                if (isDestroyed() || !id.equals(artForId)) return;
                lastBlur = blur;
                panelBlur = soft;
                if (panelBg != null) panelBg.setBitmap(soft);
                setBlur(blur);
                if (bmp != null) art.setImageBitmap(bmp);
                else art.setImageResource(R.drawable.ic_art_placeholder);
                art.setAlpha(0f);
                art.animate().alpha(1f).setDuration(250).start();
                lastTint = tint;
                if (!AmbientTheme.active(ctx()) && !videoOn) animateBackground(tint != 0 ? tint : surfaceColor);
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
        a.setDuration(300);
        a.addUpdateListener(v -> background.setColors(
                new int[]{(int) v.getAnimatedValue(), surfaceColor}));
        a.start();
    }
}
