package com.hamo.styrixplayer.ui;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import com.google.android.material.color.MaterialColors;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.transition.TransitionManager;
import android.view.LayoutInflater;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.view.animation.DecelerateInterpolator;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.yt.YtAuth;
import com.hamo.styrixplayer.yt.YtMusicApi;
import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.data.Prefs;
import com.hamo.styrixplayer.data.Song;
import com.hamo.styrixplayer.playback.PlayerConnection;
import com.hamo.styrixplayer.playback.ResumeStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseActivity implements PlayerFragment.Host {
    public static final String EXTRA_OPEN_PLAYER = "open_player";

    private View sheet, mainRoot;
    private float collapsedY;
    private ValueAnimator sheetAnim;
    // morph (mini player <-> full player) state
    private float sheetP;                    // 0 = mini player, 1 = full player
    private float miniH, miniInset;          // mini player size / side margin, px
    private ImageView morphArt;
    private ImageView morphMini;
    private boolean miniSnapOk;
    private float miniSnapX;
    private View sheetContent;
    private boolean morphOk;
    /** Solid card color under the player: starts as the mini player color, the player fades in over it. */
    private final ColorDrawable sheetBacking = new ColorDrawable(0);
    private float miniArtX, miniArtY, miniArtSize, bigArtX, bigArtY, bigArtSize, ghostBase;
    private float ghostRadius;
    private View morphBig;                 // the real cover; measured live so the flying one lands exactly on it
    private final OnBackPressedCallback sheetBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            collapsePlayer();
        }
    };


    // what to put back after the screen is rebuilt for new colors
    private static int restoreTab = -1, restoreScrollPos = -1, restoreScrollOff;
    private static boolean restoreSheet;

    private static final String[] TAB_TITLES = {"Songs", "Favorites", "Playlists", YtPage.ROOT_TITLE};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Song> allSongs = new ArrayList<>();
    private final List<Song> songsShown = new ArrayList<>();
    private final List<Song> favAll = new ArrayList<>();
    private final List<Song> favShown = new ArrayList<>();
    private final List<Db.Playlist> playlists = new ArrayList<>();

    private SongAdapter songAdapter, favAdapter;
    private com.google.android.material.button.MaterialButton songsShuffle, songsSort, favsPlay, favsShuffle;
    private FastScroller fastSongs;
    private static final String[] SORT_NAMES = {"Title", "Artist", "Longest", "Shortest"};
    private PlaylistAdapter playlistAdapter;
    private RecyclerView listSongs, listFavs, listPlaylists, listYt;
    private PullRefreshLayout pullSongs;
    private YtPage ytPage;
    private boolean bigHidden;
    private ImageView morphShadow;
    private BottomNavigationView bottomNav;
    private View pageSongs, pageFavorites, pagePlaylists, pageYoutube, searchBar, miniPlayer;
    private ViewGroup header;
    private TextView title, emptySongs, emptyFavs, emptyPlaylists, miniTitle, miniArtist;
    private ImageView miniArt;
    private ImageButton btnSearch, miniPlayPause;
    private ImageView btnAccount;
    private View btnMore;
    private long accountFetchedAt;
    private EditText searchInput;
    private LinearProgressIndicator miniProgress;

    private PlayerConnection connection;
    @Nullable private MediaController controller;

    private int tab = 0;
    private String query = "";
    private boolean libraryLoaded, restoreDone;
    private long loadedMinLen = -1;
    private boolean loadedSmart = true;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> loadSongs());

    private final OnBackPressedCallback backCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            closeSearch();
        }
    };

    /** Back on the YouTube page leaves an opened playlist, then returns to Songs. */
    private final OnBackPressedCallback ytBack = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            if (!ytPage.back()) bottomNav.setSelectedItemId(R.id.nav_songs);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateMiniProgress();
            handler.postDelayed(this, 500);
        }
    };

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            AppTheme.onTrack(MainActivity.this, item);
            updateMini();
            updatePlaying();
        }

        @Override
        public void onMediaMetadataChanged(@NonNull MediaMetadata metadata) {
            updateMini();
        }

        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateMini();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            updateMiniProgress();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainRoot = findViewById(R.id.main_root);
        sheet = findViewById(R.id.player_sheet);
        morphArt = (ImageView) findViewById(R.id.morph_art);
        morphShadow = (ImageView) findViewById(R.id.morph_shadow);
        morphShadow.setImageBitmap(CoverShadow.bitmap());
        morphMini = (ImageView) findViewById(R.id.morph_mini);
        setupMorph();
        header = findViewById(R.id.header);
        padSystemBars(header, true, false);

        title = findViewById(R.id.title);
        btnSearch = findViewById(R.id.btn_search);
        searchBar = findViewById(R.id.search_bar);
        searchInput = findViewById(R.id.search_input);
        pageSongs = findViewById(R.id.page_songs);
        pageFavorites = findViewById(R.id.page_favorites);
        pagePlaylists = findViewById(R.id.page_playlists);
        pageYoutube = findViewById(R.id.page_youtube);
        pullSongs = findViewById(R.id.pull_songs);
        listYt = findViewById(R.id.yt_list);
        listSongs = findViewById(R.id.list_songs);
        songsShuffle = findViewById(R.id.songs_primary);
        songsSort = findViewById(R.id.songs_secondary);
        favsPlay = findViewById(R.id.favs_primary);
        favsShuffle = findViewById(R.id.favs_secondary);
        fastSongs = findViewById(R.id.fast_songs);
        listFavs = findViewById(R.id.list_favs);
        listPlaylists = findViewById(R.id.list_playlists);
        emptySongs = findViewById(R.id.empty_songs);
        emptyFavs = findViewById(R.id.empty_favs);
        emptyPlaylists = findViewById(R.id.empty_playlists);
        miniPlayer = findViewById(R.id.mini_player);
        miniTitle = findViewById(R.id.mini_title);
        miniArtist = findViewById(R.id.mini_artist);
        miniArt = findViewById(R.id.mini_art);
        miniPlayPause = findViewById(R.id.mini_play_pause);
        miniProgress = findViewById(R.id.mini_progress);
        miniProgress.setMax(1000);

        // lists
        songAdapter = new SongAdapter(songsShown,
                pos -> playList(songsShown, pos),
                (pos, anchor) -> SongActions.showMenu(this, anchor, songsShown.get(pos),
                        controller, -1, this::resyncLibrary));
        favAdapter = new SongAdapter(favShown,
                pos -> playList(favShown, pos),
                (pos, anchor) -> SongActions.showMenu(this, anchor, favShown.get(pos),
                        controller, -1, this::resyncLibrary));
        playlistAdapter = new PlaylistAdapter();

        listSongs.setLayoutManager(new LinearLayoutManager(this));
        listSongs.setAdapter(songAdapter);
        fastSongs.attach(listSongs, pos -> {
            int mode = Prefs.getInt(this, Prefs.SONG_SORT, 0);
            if (mode > 1 || pos < 0 || pos >= songsShown.size()) return null;
            Song sg = songsShown.get(pos);
            String t = mode == 0 ? sg.cleanTitle : sg.artistLine;
            return t == null || t.isEmpty() ? null : t.substring(0, 1).toUpperCase(java.util.Locale.getDefault());
        });
        updateSortLabel();
        songsSort.setOnClickListener(v -> {
            int cur = Prefs.getInt(this, Prefs.SONG_SORT, 0);
            new ThemedDialogBuilder(this)
                    .setTitle("Sort songs by")
                    .setSingleChoiceItems(SORT_NAMES, cur, (dialog, which) -> {
                        Prefs.setInt(this, Prefs.SONG_SORT, which);
                        updateSortLabel();
                        applyFilters();
                        listSongs.scrollToPosition(0);
                        dialog.dismiss();
                    })
                    .show();
        });
        songsShuffle.setOnClickListener(v -> playShuffled(songsShown));
        favsPlay.setOnClickListener(v -> {
            if (!favShown.isEmpty()) playList(favShown, 0);
        });
        favsShuffle.setOnClickListener(v -> playShuffled(favShown));
        listFavs.setLayoutManager(new LinearLayoutManager(this));
        listFavs.setAdapter(favAdapter);
        listPlaylists.setLayoutManager(new LinearLayoutManager(this));
        listPlaylists.setAdapter(playlistAdapter);

        emptySongs.setOnClickListener(v -> requestPermissionsThenLoad());
        pullSongs.setOnRefreshListener(this::loadSongs); // pull down to rescan the phone

        ytPage = new YtPage(this, pageYoutube, new YtPage.Host() {
            @Nullable
            @Override
            public MediaController controller() {
                return controller;
            }

            @Override
            public void setTitle(String t) {
                if (tab == 3) title.setText(t);
            }

            @Override
            public void launchLogin() {
                startActivity(new Intent(MainActivity.this, YtLoginActivity.class));
            }
        });

        // bottom navigation
        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        bottomNav = nav;
        padSystemBars(nav, false, true);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            int target = id == R.id.nav_favorites ? 1 : id == R.id.nav_playlists ? 2
                    : id == R.id.nav_youtube ? 3 : 0;
            // already on this tab: leave it alone so the page doesn't fade/animate in again
            if (target != tab) showTab(target);
            return true;
        });
        nav.setOnItemReselectedListener(item -> { });

        // header buttons
        Anim.pressable(btnSearch);
        View searchClose = findViewById(R.id.btn_search_close);
        View moreButton = findViewById(R.id.btn_more);
        Anim.pressable(searchClose);
        Anim.pressable(moreButton);
        btnSearch.setOnClickListener(v -> openSearch());
        searchClose.setOnClickListener(v -> closeSearch());
        moreButton.setOnClickListener(this::showOverflowMenu);
        btnMore = moreButton;
        btnAccount = findViewById(R.id.btn_account);
        Anim.pressable(btnAccount);
        btnAccount.setOnClickListener(this::showAccountMenu);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilters();
            }
        });
        getOnBackPressedDispatcher().addCallback(this, ytBack);
        getOnBackPressedDispatcher().addCallback(this, backCallback);
        getOnBackPressedDispatcher().addCallback(this, sheetBack);
        if (getIntent().getBooleanExtra(EXTRA_OPEN_PLAYER, false)) mainRoot.post(this::expandPlayer);

        // playlists FAB
        FloatingActionButton fab = findViewById(R.id.fab_new_playlist);
        Anim.pressable(fab);
        fab.setOnClickListener(v -> SongActions.promptName(this, "New playlist", "", name -> {
            Db.get(this).createPlaylist(name);
            refreshData();
        }));

        // mini-player
        findViewById(R.id.mini_click_area).setOnClickListener(v -> expandPlayer());
        findViewById(R.id.mini_click_area).setOnTouchListener(new SheetDragger(true));
        Anim.pressable(miniPlayPause);
        miniPlayPause.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.isPlaying()) controller.pause();
            else controller.play();
        });
        ImageButton miniNext = findViewById(R.id.mini_next);
        Anim.pressable(miniNext);
        miniNext.setOnClickListener(v -> {
            if (controller != null && controller.hasNextMediaItem()) controller.seekToNext();
        });

        if (restoreTab > 0) {
            int[] ids = {R.id.nav_songs, R.id.nav_favorites, R.id.nav_playlists, R.id.nav_youtube};
            bottomNav.setSelectedItemId(ids[Math.min(restoreTab, 3)]);
        }
        if (restoreSheet) {
            mainRoot.post(() -> {
                computeCollapsedY();
                setSheet(0);
            });
        }
        restoreTab = -1;
        restoreSheet = false;

        requestPermissionsThenLoad();
    }

    @Override
    protected void onThemeChanging() {
        restoreTab = tab;
        restoreSheet = sheet.getVisibility() == View.VISIBLE && sheet.getTranslationY() < 1f;
        RecyclerView.LayoutManager lm = listSongs.getLayoutManager();
        if (lm instanceof LinearLayoutManager) {
            LinearLayoutManager l = (LinearLayoutManager) lm;
            restoreScrollPos = l.findFirstVisibleItemPosition();
            View first = l.findViewByPosition(restoreScrollPos);
            restoreScrollOff = first == null ? 0 : first.getTop();
        }
    }

    // ---------- full player sheet ----------

    @Override
    public void collapsePlayer() {
        prepareMorph();
        animateSheet(collapsedY, 0);
    }

    @Override
    public View.OnTouchListener sheetDragListener() {
        return new SheetDragger(false);
    }

    private void expandPlayer() {
        if (sheet.getVisibility() != View.VISIBLE) {
            computeCollapsedY();
            setSheet(collapsedY);
        }
        prepareMorph();
        animateSheet(0, 0);
    }

    private void computeCollapsedY() {
        int[] a = new int[2];
        int[] b = new int[2];
        miniPlayer.getLocationInWindow(a);
        mainRoot.getLocationInWindow(b);
        collapsedY = a[1] - b[1];
        if (collapsedY <= 0 || miniPlayer.getVisibility() != View.VISIBLE) {
            collapsedY = mainRoot.getHeight() * 0.88f;
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** The player sheet is clipped to a rounded card that grows out of the mini player. */
    private void setupMorph() {
        ghostBase = dp(200);
        sheet.setClipToOutline(true);
        sheet.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                int w = v.getWidth();
                int h = v.getHeight();
                if (w <= 0 || h <= 0) return;
                float p = sheetP;
                int inset = Math.round(miniInset * (1f - p));
                int bottom = Math.round(lerp(miniH, h, p));
                o.setRoundRect(inset, 0, w - inset, Math.min(bottom, h), dp(24) * (1f - p));
            }
        });
        morphArt.setPivotX(0f);
        morphArt.setPivotY(0f);
        morphShadow.setPivotX(0f); // same corner pivot as the cover, so both scale from the same point
        morphShadow.setPivotY(0f);
        morphArt.setClipToOutline(true);
        morphArt.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), ghostRadius);
            }
        });
    }

    /** Measures where the cover starts (mini player) and ends (full player). Called per gesture. */
    /** Songs / Favorites buttons: set straight from the palette so they can never keep an old cover's color. */
    private final AmbientTheme.Listener buttonPainter = p -> {
        if (!AmbientTheme.active(this)) return;
        for (com.google.android.material.button.MaterialButton b : new com.google.android.material.button.MaterialButton[]{songsShuffle, favsPlay}) {
            if (b == null) continue;
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(p.tonal));
            b.setTextColor(p.onTonal);
            b.setIconTint(android.content.res.ColorStateList.valueOf(p.onTonal));
            b.setRippleColor(android.content.res.ColorStateList.valueOf((p.onTonal & 0x00FFFFFF) | 0x30000000));
        }
        for (com.google.android.material.button.MaterialButton b : new com.google.android.material.button.MaterialButton[]{songsSort, favsShuffle}) {
            if (b == null) continue;
            b.setStrokeColor(android.content.res.ColorStateList.valueOf(p.variant));
            b.setTextColor(p.accent);
            b.setIconTint(android.content.res.ColorStateList.valueOf(p.accent));
            b.setRippleColor(android.content.res.ColorStateList.valueOf((p.accent & 0x00FFFFFF) | 0x30000000));
        }
    };

    private void prepareMorph() {
        sheetContent = sheet.findViewById(R.id.player_content);
        miniH = miniPlayer.getHeight() > 0 ? miniPlayer.getHeight() : dp(75);
        ViewGroup.LayoutParams lp = miniPlayer.getLayoutParams();
        miniInset = lp instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) lp).leftMargin : dp(12);

        captureMiniSnapshot();

        morphOk = false;
        morphBig = null;
        View big = sheet.findViewById(R.id.art);
        if (big == null || big.getWidth() <= 0 || big.getVisibility() != View.VISIBLE
                || miniPlayer.getVisibility() != View.VISIBLE || miniArt.getWidth() <= 0
                || sheet.getHeight() <= 0) {
            return;
        }
        int[] a = new int[2];
        int[] b = new int[2];
        mainRoot.getLocationInWindow(b);
        miniArt.getLocationInWindow(a);
        miniArtX = a[0] - b[0];
        miniArtY = a[1] - b[1];
        miniArtSize = miniArt.getWidth();
        big.getLocationInWindow(a);
        bigArtX = a[0] - b[0];
        bigArtY = a[1] - b[1] - sheet.getTranslationY();
        bigArtSize = big.getWidth() * big.getScaleX();
        // player scrolled so far that the cover is off screen: skip the flying cover
        if (bigArtY < 0 || bigArtY > mainRoot.getHeight() * 0.7f) return;

        Drawable d = big instanceof ImageView ? ((ImageView) big).getDrawable() : null;
        if (d == null) d = miniArt.getDrawable();
        if (d == null) return;
        Drawable.ConstantState cs = d.getConstantState();
        morphArt.setImageDrawable(cs != null ? cs.newDrawable().mutate() : d);
        morphBig = big;
        morphOk = true;
    }

    /** Puts the sheet's top edge at y (0 = fully open, collapsedY = tucked into the mini player). */
    private void setSheet(float y) {
        y = Math.max(0f, Math.min(y, collapsedY));
        float p = collapsedY > 0 ? 1f - y / collapsedY : 1f;
        sheetP = p;
        sheet.setTranslationY(y);
        sheet.setAlpha(1f); // the card itself is always solid, so the list never shows through
        // colors change smoothly: the card starts in the mini player's color and the player's own
        // gradient + content fade in over it
        sheetBacking.setColor(miniCardColor());
        if (sheet.getBackground() != sheetBacking) sheet.setBackground(sheetBacking);
        View playerRoot = sheet.findViewById(R.id.player_root);
        if (playerRoot != null) {
            float t = clamp01((p - 0.03f) / 0.6f);
            playerRoot.setAlpha(t * t * (3f - 2f * t));
        }
        sheet.setVisibility(p <= 0.001f ? View.INVISIBLE : View.VISIBLE);
        if (p < 0.9f) {                       // the player is closing: the speed panel goes with it
            androidx.fragment.app.Fragment pf = getSupportFragmentManager().findFragmentById(R.id.player_sheet);
            if (pf instanceof PlayerFragment) ((PlayerFragment) pf).closeQuickPanel();
        }
        sheet.invalidateOutline();
        miniPlayer.setAlpha(p > 0.001f ? 0f : 1f);
        sheetBack.setEnabled(p > 0.001f);
        applyContentFade(p);
        updateMorphArt(p);
        updateMiniSnapshot(p);
    }

    /**
     * Text and buttons fade in as the card opens. The player's own cover block stays hidden
     * while the flying cover is on screen, so there is never a second cover underneath.
     */
    private void applyContentFade(float p) {
        if (sheetContent == null) sheetContent = sheet.findViewById(R.id.player_content);
        if (!(sheetContent instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) sheetContent;
        g.setAlpha(1f);
        View branch = null;
        View big = sheet.findViewById(R.id.art);
        if (big != null) {
            View c = big;
            while (c.getParent() instanceof View && c.getParent() != g) c = (View) c.getParent();
            if (c.getParent() == g) branch = c;
        }
        float rest = 1f; // content is never faded; the growing card simply reveals it
        float cover = 1f;
        // while the flying cover is on screen the real one is switched off completely (not just
        // faded), so it can never show through as a second, ghosted cover
        boolean hideReal = morphOk && p > 0.001f && p < 0.995f;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            c.setAlpha(c == branch ? cover : rest);
            if (c == branch) c.setVisibility(View.VISIBLE);
        }
        // hide just the cover (not its whole frame) so the shadow can build up under the landing cover
        if (big != null) {
            if (hideReal) {
                big.setVisibility(View.INVISIBLE);
                bigHidden = true;
            } else if (bigHidden) {
                big.setVisibility(View.VISIBLE);
                bigHidden = false;
                View sh = sheet.findViewById(R.id.art_shadow);
                if (sh != null) sh.setAlpha(1f); // matches the flying cover's shadow exactly
            }
        }
        View shadow = sheet.findViewById(R.id.art_shadow);
        if (shadow != null && hideReal) shadow.setAlpha(0f); // the flying cover carries its own shadow
    }

    /**
     * Draws the mini player's content (title, artist, buttons, progress; not the card and not the
     * cover) into a bitmap. The bitmap sits above the opening card and fades out with it, so the
     * mini player's text and buttons are not cut off the moment the swipe starts.
     */
    private void captureMiniSnapshot() {
        miniSnapOk = false;
        int w = miniPlayer.getWidth();
        int h = miniPlayer.getHeight();
        if (miniPlayer.getVisibility() != View.VISIBLE || w <= 0 || h <= 0 || !(miniPlayer instanceof ViewGroup)) return;
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            int artVis = miniArt.getVisibility();
            miniArt.setVisibility(View.INVISIBLE);
            ViewGroup g = (ViewGroup) miniPlayer;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                cv.save();
                cv.translate(c.getLeft(), c.getTop());
                c.draw(cv);
                cv.restore();
            }
            miniArt.setVisibility(artVis);
            ViewGroup.LayoutParams lp = morphMini.getLayoutParams();
            lp.width = w;
            lp.height = h;
            morphMini.setLayoutParams(lp);
            morphMini.setImageBitmap(bmp);
            int[] a = new int[2];
            int[] b = new int[2];
            miniPlayer.getLocationInWindow(a);
            mainRoot.getLocationInWindow(b);
            miniSnapX = a[0] - b[0];
            miniSnapOk = true;
        } catch (Throwable ignored) {
            miniSnapOk = false;
        }
    }

    private void updateMiniSnapshot(float p) {
        if (!miniSnapOk || p <= 0.001f || p >= 0.4f) {
            if (morphMini.getVisibility() != View.GONE) morphMini.setVisibility(View.GONE);
            return;
        }
        float t = clamp01(p / 0.32f);
        morphMini.setTranslationX(miniSnapX);
        morphMini.setTranslationY(sheet.getTranslationY());
        morphMini.setAlpha(1f - t * t * (3f - 2f * t));
        if (morphMini.getVisibility() != View.VISIBLE) morphMini.setVisibility(View.VISIBLE);
    }

    /** Current (ambient-aware) fill color of the mini player card. */
    private int miniCardColor() {
        Drawable d = miniPlayer.getBackground();
        if (d instanceof GradientDrawable) {
            ColorStateList c = ((GradientDrawable) d).getColor();
            if (c != null) return c.getDefaultColor() | 0xFF000000;
        }
        return 0xFF2A2A35;
    }

    private void updateMorphArt(float p) {
        if (!morphOk || p <= 0.001f || p >= 0.995f) {
            if (morphArt.getVisibility() != View.GONE) morphArt.setVisibility(View.GONE);
            if (morphShadow.getVisibility() != View.GONE) morphShadow.setVisibility(View.GONE);
            if (miniArt.getVisibility() != View.VISIBLE) miniArt.setVisibility(View.VISIBLE);
            return;
        }
        // the mini player's own cover would be a second copy next to the flying one
        if (miniArt.getVisibility() != View.INVISIBLE) miniArt.setVisibility(View.INVISIBLE);
        // Re-measure where the real cover is right now (it may still be scaling or the page may have
        // settled a few px differently since the gesture began), so the landing never pops.
        float endRadius = dp(32);
        if (morphBig != null && morphBig.getWidth() > 0) {
            int[] la = new int[2];
            int[] lb = new int[2];
            mainRoot.getLocationInWindow(lb);
            morphBig.getLocationInWindow(la);
            bigArtX = la[0] - lb[0];
            bigArtY = la[1] - lb[1] - sheet.getTranslationY();
            bigArtSize = morphBig.getWidth() * morphBig.getScaleX();
            endRadius = dp(32) * morphBig.getScaleX();
        }
        float size = lerp(miniArtSize, bigArtSize, p);
        float s = size / ghostBase;
        morphArt.setScaleX(s);
        morphArt.setScaleY(s);
        morphArt.setTranslationX(lerp(miniArtX, bigArtX, p));
        morphArt.setTranslationY(lerp(miniArtY, bigArtY, p));
        ghostRadius = lerp(dp(16), endRadius, p) / s;   // 16dp mini cover to the player's 32dp cover
        morphArt.invalidateOutline();
        // the shadow flies with the cover and grows as it lands (bitmap is 1.15x the cover, nudged down)
        float k = Math.max(0f, Math.min(1f, (p - 0.3f) / 0.65f));
        float grow = size * 0.0755f; // the shadow image is 1.151x the cover, centered on it
        morphShadow.setScaleX(s * 1.151f);
        morphShadow.setScaleY(s * 1.151f);
        morphShadow.setTranslationX(lerp(miniArtX, bigArtX, p) - grow);
        morphShadow.setTranslationY(lerp(miniArtY, bigArtY, p) - grow
                + dp(8) * (size / Math.max(1f, bigArtSize)));
        boolean fullCover = morphBig == null || morphBig.getWidth() >= mainRoot.getWidth() * 0.6f;
        morphShadow.setAlpha(fullCover ? k * k * (3f - 2f * k) : 0f);   // video mode: the small cover card has no shadow
        if (morphShadow.getVisibility() != View.VISIBLE) morphShadow.setVisibility(View.VISIBLE);
        // the real cover takes over only once the flight is finished (positions match exactly there)
        morphArt.setAlpha(1f); // hard swap with the real cover at the very end, never both at once
        if (morphArt.getVisibility() != View.VISIBLE) morphArt.setVisibility(View.VISIBLE);
    }

    private void animateSheet(float target, float velocityY) {
        if (sheetAnim != null) sheetAnim.cancel();
        final float from = sheet.getVisibility() == View.VISIBLE ? sheet.getTranslationY() : collapsedY;
        if (Math.abs(from - target) < 1f) {
            setSheet(target);
            return;
        }
        sheetAnim = ValueAnimator.ofFloat(from, target);
        long dur = (long) Math.max(340, Math.min(560, Math.abs(from - target) / mainRoot.getHeight() * 680));
        sheetAnim.setDuration(dur);
        sheetAnim.setInterpolator(target == 0
                ? new PathInterpolator(0.22f, 0.05f, 0.1f, 1f)   // opening: eases in, then a long soft landing
                : new PathInterpolator(0.3f, 0f, 0.2f, 1f));     // closing: gentle in and out
        sheetAnim.addUpdateListener(a -> setSheet((float) a.getAnimatedValue()));
        sheetAnim.start();
    }

    /**
     * Follows the finger: drag the mini player up to pull the player open, or drag the open
     * player down to close it. Release decides by distance and fling speed.
     */
    private class SheetDragger implements View.OnTouchListener {
        private final boolean fromMini;
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private final float fling = 900 * getResources().getDisplayMetrics().density;
        private float downX, downY;
        private boolean dragging;
        private VelocityTracker tracker;

        SheetDragger(boolean fromMini) {
            this.fromMini = fromMini;
        }

        private void track(MotionEvent e) {
            // raw position, because the view itself moves while dragging
            MotionEvent c = MotionEvent.obtain(e);
            c.setLocation(e.getRawX(), e.getRawY());
            tracker.addMovement(c);
            c.recycle();
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    if (tracker != null) tracker.recycle();
                    tracker = VelocityTracker.obtain();
                    track(e);
                    if (fromMini) v.setPressed(true);
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    if (tracker == null) return true;
                    track(e);
                    float dy = e.getRawY() - downY;
                    float dx = e.getRawX() - downX;
                    boolean rightWay = fromMini ? dy < -slop : dy > slop;
                    if (!dragging && rightWay && Math.abs(dy) > Math.abs(dx)) {
                        dragging = true;
                        v.setPressed(false);
                        if (sheetAnim != null) sheetAnim.cancel();
                        if (fromMini && sheet.getVisibility() != View.VISIBLE) computeCollapsedY();
                        prepareMorph();
                    }
                    if (dragging) setSheet(fromMini ? collapsedY + dy : dy);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    v.setPressed(false);
                    boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                    if (tracker == null) return true;
                    track(e);
                    tracker.computeCurrentVelocity(1000);
                    float vy = tracker.getYVelocity();
                    float dy = e.getRawY() - downY;
                    tracker.recycle();
                    tracker = null;
                    if (dragging) {
                        boolean expand;
                        if (up && Math.abs(vy) > fling) expand = vy < 0;
                        else expand = sheet.getTranslationY() < collapsedY * (fromMini ? 0.6f : 0.4f);
                        if (expand) animateSheet(0, vy);
                        else animateSheet(collapsedY, vy);
                    } else if (up && fromMini && Math.abs(dy) <= slop
                            && Math.abs(e.getRawX() - downX) <= slop) {
                        v.performClick();
                    }
                    dragging = false;
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) mainRoot.post(this::expandPlayer);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        SongActions.handleActivityResult(requestCode, resultCode);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getWindow().getDecorView().post(() -> {
            if (!isFinishing() && !isDestroyed()) com.hamo.styrixplayer.CrashReport.showIfAny(this);
        });
        AmbientTheme.register(buttonPainter);
        AmbientTheme.Palette now = AmbientTheme.active(this) ? AmbientTheme.current() : null;
        if (now != null) buttonPainter.onPalette(now);
        connection = new PlayerConnection(this);
        connection.connect(c -> {
            controller = c;
            c.addListener(playerListener);
            AppTheme.onTrack(this, c.getCurrentMediaItem());
            maybeRestore();
            updateMini();
            updatePlaying();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ytPage.onResume();
        com.hamo.styrixplayer.update.Updater.autoCheck(this);
        if (tab == 3) refreshAccount();
        boolean settingsChanged = loadedMinLen >= 0
                && (Prefs.minLenMs(this) != loadedMinLen
                || Prefs.getBool(this, Prefs.SMART_TITLES, true) != loadedSmart);
        if (settingsChanged && hasAudioPermission()) {
            loadSongs();
        } else if (libraryLoaded) {
            // pick up metadata edits made in the player
            allSongs.clear();
            allSongs.addAll(Library.all());
            refreshData();
        }
    }

    @Override
    protected void onStop() {
        AmbientTheme.unregister(buttonPainter);
        handler.removeCallbacks(ticker);
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
        ytPage.destroy();
        super.onDestroy();
    }

    // ---------- permissions + library ----------

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, audioPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsThenLoad() {
        if (hasAudioPermission()) {
            loadSongs();
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{Manifest.permission.POST_NOTIFICATIONS});
            }
            return;
        }
        List<String> needed = new ArrayList<>();
        needed.add(audioPermission());
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        permissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void loadSongs() {
        if (!hasAudioPermission()) {
            emptySongs.setText(R.string.permission_needed);
            emptySongs.setVisibility(View.VISIBLE);
            pullSongs.setRefreshing(false);
            return;
        }
        loadedMinLen = Prefs.minLenMs(this);
        loadedSmart = Prefs.getBool(this, Prefs.SMART_TITLES, true);
        io.execute(() -> {
            Library.load(getApplicationContext());
            final List<Song> found = Library.all();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                allSongs.clear();
                allSongs.addAll(found);
                libraryLoaded = true;
                pullSongs.setRefreshing(false);
                ytPage.onLibraryLoaded();
                refreshData();
                if (restoreScrollPos >= 0) {
                    final int p = restoreScrollPos, o = restoreScrollOff;
                    restoreScrollPos = -1;
                    listSongs.post(() -> ((LinearLayoutManager) listSongs.getLayoutManager())
                            .scrollToPositionWithOffset(p, o));
                } else {
                    listSongs.scheduleLayoutAnimation();
                }
                maybeRestore();
                updatePlaying();
            });
        });
    }

    /** Re-reads favorites and playlists, then redraws every list. */
    /** Re-reads the in-memory library (after a song was deleted) and refreshes every list. */
    private void resyncLibrary() {
        allSongs.clear();
        allSongs.addAll(Library.all());
        refreshData();
    }

    private void refreshData() {
        favAll.clear();
        favAll.addAll(Library.resolve(Db.get(this).favoriteIds()));
        playlists.clear();
        playlists.addAll(Db.get(this).playlists());
        playlistAdapter.notifyDataSetChanged();
        emptyPlaylists.setVisibility(playlists.isEmpty() ? View.VISIBLE : View.GONE);
        applyFilters();
    }

    private boolean contains(@Nullable String v) {
        return v != null && v.toLowerCase(Locale.getDefault()).contains(query);
    }

    private boolean matches(Song s) {
        return query.isEmpty() || contains(s.title) || contains(s.cleanTitle) || contains(s.artist)
                || contains(s.album) || contains(s.tagsText());
    }

    private void updateSortLabel() {
        int mode = Prefs.getInt(this, Prefs.SONG_SORT, 0);
        songsSort.setText("Sort: " + SORT_NAMES[Math.max(0, Math.min(mode, SORT_NAMES.length - 1))]);
    }

    private void playShuffled(List<Song> list) {
        if (list.isEmpty()) return;
        playList(list, new java.util.Random().nextInt(list.size()));
        if (controller != null) controller.setShuffleModeEnabled(true);
    }

    private void sortSongs(List<Song> list) {
        int mode = Prefs.getInt(this, Prefs.SONG_SORT, 0);
        final java.text.Collator col = java.text.Collator.getInstance();
        col.setStrength(java.text.Collator.PRIMARY);
        java.util.Comparator<Song> byTitle = (x, y) -> col.compare(x.cleanTitle, y.cleanTitle);
        switch (mode) {
            case 1:
                java.util.Collections.sort(list, (x, y) -> {
                    int c = col.compare(x.artistLine, y.artistLine);
                    return c != 0 ? c : byTitle.compare(x, y);
                });
                break;
            case 2:
                java.util.Collections.sort(list, (x, y) -> Long.compare(y.durationMs, x.durationMs));
                break;
            case 3:
                java.util.Collections.sort(list, (x, y) -> Long.compare(x.durationMs, y.durationMs));
                break;
            default:
                java.util.Collections.sort(list, byTitle);
        }
    }

    private void applyFilters() {
        songsShown.clear();
        for (Song s : allSongs) if (matches(s)) songsShown.add(s);
        sortSongs(songsShown);
        songAdapter.notifyDataSetChanged();

        favShown.clear();
        for (Song s : favAll) if (matches(s)) favShown.add(s);
        favAdapter.notifyDataSetChanged();

        if (songsShown.isEmpty() && libraryLoaded) {
            emptySongs.setText(allSongs.isEmpty() ? getString(R.string.no_songs) : "No results");
            emptySongs.setVisibility(View.VISIBLE);
        } else if (hasAudioPermission()) {
            emptySongs.setVisibility(View.GONE);
        }
        if (favShown.isEmpty()) {
            emptyFavs.setText(favAll.isEmpty()
                    ? "No favorites yet\nLong-press a song or tap the heart in the player"
                    : "No results");
            emptyFavs.setVisibility(View.VISIBLE);
        } else {
            emptyFavs.setVisibility(View.GONE);
        }
    }

    // ---------- tabs + search ----------

    private void showTab(int t) {
        tab = t;
        View[] pages = {pageSongs, pageFavorites, pagePlaylists, pageYoutube};
        RecyclerView[] lists = {listSongs, listFavs, listPlaylists, listYt};
        for (int i = 0; i < pages.length; i++) {
            View p = pages[i];
            p.animate().cancel();
            if (i == t) {
                p.setAlpha(0f);
                p.setTranslationY(12f * getResources().getDisplayMetrics().density);
                p.setVisibility(View.VISIBLE);
                p.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(280)
                        .setInterpolator(new DecelerateInterpolator(1.7f))
                        .withLayer()
                        .start();
                lists[i].scheduleLayoutAnimation();
            } else {
                p.setVisibility(View.GONE);
            }
        }
        title.setText(TAB_TITLES[t]);
        ytBack.setEnabled(t == 3);
        btnMore.setVisibility(t == 3 ? View.GONE : View.VISIBLE);
        btnAccount.setVisibility(t == 3 ? View.VISIBLE : View.GONE);
        if (t == 3) refreshAccount();
        if (t == 3) ytPage.onShown();
        if (t == 2 || t == 3) {
            closeSearch();
            btnSearch.setVisibility(View.GONE);
        } else {
            btnSearch.setVisibility(View.VISIBLE);
        }
    }

    private void openSearch() {
        slideSearch(true);
        backCallback.setEnabled(true);
        searchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchInput, 0);
    }

    private void closeSearch() {
        if (searchBar.getVisibility() != View.VISIBLE) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        searchInput.setText("");
        slideSearch(false);
        backCallback.setEnabled(false);
    }

    private android.animation.ValueAnimator searchAnim;

    /**
     * The search bar grows and shrinks frame by frame, and the page under it moves with it. The
     * old page-wide transition only animated the header: the list below jumped to its final spot
     * at once while the bar was still sliding over the buttons.
     */
    private void slideSearch(final boolean open) {
        if (searchAnim != null) searchAnim.cancel();
        final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) searchBar.getLayoutParams();
        final int fullH = dp(54);
        final int fullM = dp(10);
        final float start = searchBar.getVisibility() == View.VISIBLE ? clamp01(lp.height / (float) fullH) : 0f;
        final float end = open ? 1f : 0f;
        if (open) searchBar.setVisibility(View.VISIBLE);
        searchAnim = android.animation.ValueAnimator.ofFloat(start, end);
        searchAnim.setDuration(220);
        searchAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f));
        searchAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            lp.height = Math.round(fullH * f);
            lp.bottomMargin = Math.round(fullM * f);
            searchBar.setAlpha(f);
            searchBar.setLayoutParams(lp);
        });
        searchAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (cancelled) return;
                lp.height = fullH;
                lp.bottomMargin = fullM;
                searchBar.setLayoutParams(lp);
                searchBar.setAlpha(1f);
                if (!open) searchBar.setVisibility(View.GONE);
            }
        });
        searchAnim.start();
    }

    /** Shows the Google account photo (or a person icon) and quietly refreshes it from YouTube. */
    private void refreshAccount() {
        boolean in = YtAuth.isSignedIn(this);
        String photo = in ? YtAuth.accountPhoto(this) : "";
        if (photo.isEmpty()) {
            btnAccount.setPadding(dp(11), dp(11), dp(11), dp(11));
            btnAccount.setImageResource(R.drawable.ic_account);
            btnAccount.setImageTintList(ColorStateList.valueOf(
                    MaterialColors.getColor(btnAccount, com.google.android.material.R.attr.colorOnSurface)));
        } else {
            btnAccount.setPadding(0, 0, 0, 0);
            btnAccount.setImageTintList(null);
            ArtLoader.load(this, ("acct" + photo).hashCode(), null, android.net.Uri.parse(photo),
                    btnAccount, 128, R.drawable.ic_account);
        }
        long now = System.currentTimeMillis();
        if (in && (photo.isEmpty() || now - accountFetchedAt > 3_600_000L)) {
            accountFetchedAt = now;
            final Context app = getApplicationContext();
            io.execute(() -> {
                try {
                    String[] info = YtMusicApi.accountInfo(app);
                    if (info[0].isEmpty() && info[1].isEmpty()) return;
                    boolean changed = !info[1].equals(YtAuth.accountPhoto(app))
                            || !info[0].equals(YtAuth.accountName(app));
                    YtAuth.saveAccount(app, info[0], info[1]);
                    if (changed) runOnUiThread(() -> {
                        if (!isDestroyed() && tab == 3) refreshAccount();
                    });
                } catch (Exception ignored) {
                    // keep what we have
                }
            });
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void showAccountMenu(View anchor) {
        final boolean in = YtAuth.isSignedIn(this);
        ThemedPopupMenu pm = new ThemedPopupMenu(this, anchor);
        if (in) {
            String name = YtAuth.accountName(this);
            if (!name.isEmpty()) pm.getMenu().add(0, 0, 0, name).setEnabled(false);
            pm.getMenu().add(0, 1, 1, "Settings");
            pm.getMenu().add(0, 2, 2, "Sign out");
        } else {
            pm.getMenu().add(0, 3, 0, "Sign in with Google");
            pm.getMenu().add(0, 1, 1, "Settings");
        }
        pm.getMenu().add(0, 4, 3, "Download from YouTube");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    startActivity(new Intent(this, SettingsActivity.class));
                    break;
                case 2:
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Sign out of YouTube?")
                            .setMessage("Your library will disappear from this app. Streaming still works.")
                            .setPositiveButton("Sign out", (d, w) -> {
                                YtAuth.signOut(this);
                                ytPage.onResume();
                                refreshAccount();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    break;
                case 3:
                    startActivity(new Intent(this, YtLoginActivity.class));
                    break;
                case 4:
                    startActivity(new Intent(this, DownloadActivity.class));
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void showOverflowMenu(View anchor) {
        ThemedPopupMenu pm = new ThemedPopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Settings");
        pm.getMenu().add(0, 2, 1, "Rescan library");
        pm.getMenu().add(0, 3, 2, "Download from YouTube");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) startActivity(new Intent(this, SettingsActivity.class));
            else if (item.getItemId() == 2) requestPermissionsThenLoad();
            else if (item.getItemId() == 3) startActivity(new Intent(this, DownloadActivity.class));
            return true;
        });
        pm.show();
    }

    // ---------- playback ----------

    private void playList(List<Song> list, int position) {
        if (controller == null) {
            Toast.makeText(this, R.string.not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        SongActions.playList(controller, list, position, false);
    }

    /** Brings back the last session (queue, song, position) once, when the app starts. */
    private void maybeRestore() {
        if (restoreDone || !libraryLoaded || controller == null) return;
        restoreDone = true;
        if (!Prefs.getBool(this, Prefs.RESUME, true) || controller.getMediaItemCount() > 0) return;

        ResumeStore.State st = ResumeStore.load(this);
        if (st == null) return;
        List<MediaItem> items = new ArrayList<>();
        int index = 0;
        for (Long id : st.ids) {
            Song s = Library.get(id);
            if (s == null) continue;
            if (id == st.currentId) index = items.size();
            items.add(s.toMediaItem());
        }
        if (items.isEmpty()) return;
        controller.setShuffleModeEnabled(st.shuffle);
        controller.setRepeatMode(st.repeat);
        controller.setMediaItems(items, index, st.position);
        controller.prepare(); // ready to resume, but doesn't start playing by itself
    }

    private void updatePlaying() {
        String id = null;
        if (controller != null && controller.getCurrentMediaItem() != null) {
            id = controller.getCurrentMediaItem().mediaId;
        }
        songAdapter.setPlayingId(id);
        favAdapter.setPlayingId(id);
        if (ytPage != null) ytPage.setPlayingId(id);
    }

    private void updateMini() {
        if (controller == null || controller.getMediaItemCount() == 0
                || controller.getCurrentMediaItem() == null) {
            miniPlayer.setVisibility(View.GONE);
            return;
        }
        MediaItem item = controller.getCurrentMediaItem();
        MediaMetadata md = item.mediaMetadata;
        miniTitle.setText(md.title != null ? md.title : "");
        miniArtist.setText(md.artist != null ? md.artist : "");
        miniTitle.setSelected(true);
        miniArtist.setSelected(true);
        ArtLoader.loadItem(this, item, miniArt, 132);
        miniPlayPause.setImageResource(controller.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        if (miniPlayer.getVisibility() != View.VISIBLE) {
            miniPlayer.setAlpha(0f);
            miniPlayer.setTranslationY(10f * getResources().getDisplayMetrics().density);
            miniPlayer.setVisibility(View.VISIBLE);
            miniPlayer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setInterpolator(new DecelerateInterpolator(1.7f))
                    .withLayer()
                    .start();
        }
        updateMiniProgress();
    }

    private void updateMiniProgress() {
        if (controller == null) return;
        long duration = controller.getDuration();
        if (duration <= 0) {
            miniProgress.setProgressCompat(0, false);
            return;
        }
        int p = (int) (1000L * Math.max(0, controller.getCurrentPosition()) / duration);
        miniProgress.setProgressCompat(Math.min(p, 1000), false);
    }

    // ---------- playlists ----------

    private void openPlaylist(long id) {
        Intent i = new Intent(this, PlaylistActivity.class);
        i.putExtra(PlaylistActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private void showPlaylistMenu(View anchor, Db.Playlist p) {
        ThemedPopupMenu pm = new ThemedPopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Rename");
        pm.getMenu().add(0, 2, 1, "Delete");
        pm.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                SongActions.promptName(this, "Rename playlist", p.name, name -> {
                    Db.get(this).renamePlaylist(p.id, name);
                    refreshData();
                });
            } else {
                new ThemedDialogBuilder(this)
                        .setTitle("Delete \"" + p.name + "\"?")
                        .setMessage("The songs themselves are not deleted.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete", (d, w) -> {
                            Db.get(this).deletePlaylist(p.id);
                            refreshData();
                        })
                        .show();
            }
            return true;
        });
        pm.show();
    }

    private static class PlaylistHolder extends RecyclerView.ViewHolder {
        final TextView name, count;
        final ImageView icon, cover1;
        final View grid;
        final ImageView[] cells;

        PlaylistHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.playlist_icon);
            cover1 = v.findViewById(R.id.playlist_cover1);
            grid = v.findViewById(R.id.playlist_grid);
            cells = new ImageView[]{v.findViewById(R.id.cov_a), v.findViewById(R.id.cov_b),
                    v.findViewById(R.id.cov_c), v.findViewById(R.id.cov_d)};
            name = v.findViewById(R.id.playlist_name);
            count = v.findViewById(R.id.playlist_count);
        }
    }

    /** Up to four covers of the playlist's songs as a 2 x 2 collage (one cover fills the tile). */
    private void bindCovers(PlaylistHolder h, Db.Playlist p) {
        List<Long> ids = Db.get(this).playlistSongIds(p.id);
        List<Song> covers = new ArrayList<>();
        for (long id : ids) {
            for (Song s : allSongs) {
                if (s.id == id) {
                    covers.add(s);
                    break;
                }
            }
            if (covers.size() == 4) break;
        }
        h.cover1.setVisibility(View.GONE);
        h.grid.setVisibility(View.GONE);
        if (covers.isEmpty()) return;
        if (covers.size() < 4) {
            h.cover1.setVisibility(View.VISIBLE);
            ArtLoader.load(this, covers.get(0), h.cover1, 160);
            return;
        }
        h.grid.setVisibility(View.VISIBLE);
        for (int i = 0; i < 4; i++) ArtLoader.load(this, covers.get(i), h.cells[i], 100);
    }

    private class PlaylistAdapter extends RecyclerView.Adapter<PlaylistHolder> {
        @NonNull
        @Override
        public PlaylistHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PlaylistHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_playlist, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PlaylistHolder h, int position) {
            Db.Playlist p = playlists.get(position);
            h.name.setText(p.name);
            PlaylistStyle.applyColor(h.icon, PlaylistStyle.color(p.id));
            h.count.setText(p.count + (p.count == 1 ? " song" : " songs"));
            bindCovers(h, p);
            h.itemView.setOnClickListener(v -> openPlaylist(p.id));
            h.itemView.setOnLongClickListener(v -> {
                showPlaylistMenu(v, p);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return playlists.size();
        }
    }
}
