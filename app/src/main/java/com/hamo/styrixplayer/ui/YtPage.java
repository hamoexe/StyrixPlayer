package com.hamo.styrixplayer.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.content.res.ColorStateList;
import androidx.core.content.ContextCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Song;
import com.hamo.styrixplayer.yt.YtAuth;
import com.hamo.styrixplayer.yt.YtDownloads;
import com.hamo.styrixplayer.yt.YtItem;
import com.hamo.styrixplayer.yt.YtLikes;
import com.hamo.styrixplayer.yt.YtLocal;
import com.hamo.styrixplayer.yt.YtMusicApi;
import com.hamo.styrixplayer.yt.YtTrack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The YouTube Music page inside the main screen (same family as Songs / Favorites / Playlists):
 * Home, Liked songs, Playlists and Search tabs, plus playlists and albums you can open and go back
 * from. Songs that exist on the phone play from the file.
 */
final class YtPage {

    interface Host {
        @Nullable MediaController controller();
        void setTitle(String title);
        void launchLogin();
    }

    static final String ROOT_TITLE = "YouTube Music";
    private static final int TAB_HOME = 0, TAB_LIKED = 1, TAB_PLAYLISTS = 2, TAB_SEARCH = 3;

    private final Context app;
    private final Host host;
    private final List<YtItem> items = new ArrayList<>();
    private final Deque<String[]> stack = new ArrayDeque<>(); // {browseId, title}
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final TabLayout tabs;
    private final EditText searchInput;
    private final MaterialButton signIn;
    private final ProgressBar progress;
    private final TextView empty;
    private final View emptyBox;
    private final ImageView emptyIcon;
    private final MaterialButton retry;
    private final PullRefreshLayout pull;
    private final ItemAdapter adapter = new ItemAdapter();

    private Map<String, Song> local = new HashMap<>();
    @Nullable private String playingId;
    private int generation;
    private int currentTab = TAB_HOME;
    private boolean loadedOnce;
    private boolean wasSignedIn;

    YtPage(Context context, View root, Host host) {
        this.app = context.getApplicationContext();
        this.host = host;
        tabs = root.findViewById(R.id.yt_tabs);
        searchInput = root.findViewById(R.id.yt_search);
        signIn = root.findViewById(R.id.yt_sign_in);
        progress = root.findViewById(R.id.yt_progress);
        empty = root.findViewById(R.id.yt_empty);
        emptyBox = root.findViewById(R.id.yt_state);
        emptyIcon = root.findViewById(R.id.yt_state_icon);
        retry = root.findViewById(R.id.yt_retry);
        pull = root.findViewById(R.id.yt_pull);

        RecyclerView list = root.findViewById(R.id.yt_list);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(adapter);

        tabs.addTab(tabs.newTab().setText("Home"));
        tabs.addTab(tabs.newTab().setText("Liked songs"));
        tabs.addTab(tabs.newTab().setText("Playlists"));
        tabs.addTab(tabs.newTab().setText("Search"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                searchInput.setVisibility(currentTab == TAB_SEARCH ? View.VISIBLE : View.GONE);
                load();
                if (currentTab == TAB_SEARCH) searchInput.requestFocus();
            }

            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { load(); }
        });

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                InputMethodManager imm = context.getSystemService(InputMethodManager.class);
                if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
                load();
                return true;
            }
            return false;
        });

        setupSearchBox(context);
        signIn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(signIn.getContext(), android.R.color.holo_red_dark)));
        signIn.setOnClickListener(v -> host.launchLogin());
        retry.setOnClickListener(v -> load());
        pull.setOnRefreshListener(this::load);
        wasSignedIn = YtAuth.isSignedIn(app);
        updateChrome();
    }

    /** Magnifier at the start, a clear (x) at the end once something is typed. */
    private void setupSearchBox(Context context) {
        final int tint = com.google.android.material.color.MaterialColors.getColor(searchInput,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        final android.graphics.drawable.Drawable search = tinted(context, R.drawable.ic_search, tint);
        final android.graphics.drawable.Drawable close = tinted(context, R.drawable.ic_close, tint);
        searchInput.setCompoundDrawablePadding((int) (10 * context.getResources().getDisplayMetrics().density));
        searchInput.setCompoundDrawablesRelativeWithIntrinsicBounds(search, null, null, null);
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                searchInput.setCompoundDrawablesRelativeWithIntrinsicBounds(search, null,
                        e.length() == 0 ? null : close, null);
            }
        });
        searchInput.setOnTouchListener((v, ev) -> {
            if (ev.getAction() != android.view.MotionEvent.ACTION_UP) return false;
            android.graphics.drawable.Drawable end = searchInput.getCompoundDrawablesRelative()[2];
            if (end == null) return false;
            boolean rtl = searchInput.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            boolean hit = rtl ? ev.getX() <= searchInput.getPaddingStart() + end.getIntrinsicWidth() + 16
                    : ev.getX() >= searchInput.getWidth() - searchInput.getPaddingEnd() - end.getIntrinsicWidth() - 16;
            if (!hit) return false;
            searchInput.setText("");
            return true;
        });
    }

    private static android.graphics.drawable.Drawable tinted(Context c, int res, int color) {
        android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(c, res).mutate();
        d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        androidx.core.graphics.drawable.DrawableCompat.setTint(d, color);
        return d;
    }

    // ---------- lifecycle hooks (called by MainActivity) ----------

    /** The page became visible. */
    void onShown() {
        host.setTitle(stack.isEmpty() ? ROOT_TITLE : stack.peek()[1]);
        if (!loadedOnce) load();
    }

    /** The activity resumed: pick up sign-in changes and new downloads. */
    void onResume() {
        boolean now = YtAuth.isSignedIn(app);
        if (now != wasSignedIn) {
            wasSignedIn = now;
            updateChrome();
            if (loadedOnce) load();
            return;
        }
        if (loadedOnce && !items.isEmpty()) refreshLocal();
    }

    /** The phone's library finished scanning: re-check which songs are already on it. */
    void onLibraryLoaded() {
        if (loadedOnce && !items.isEmpty()) refreshLocal();
    }

    /** Back inside the page: leaves an opened playlist. Returns false at the top level. */
    boolean back() {
        if (stack.isEmpty()) return false;
        stack.pop();
        updateChrome();
        host.setTitle(stack.isEmpty() ? ROOT_TITLE : stack.peek()[1]);
        load();
        return true;
    }

    void destroy() {
        generation++;
        io.shutdownNow();
    }

    // ---------- loading ----------

    private void updateChrome() {
        boolean root = stack.isEmpty();
        tabs.setVisibility(root ? View.VISIBLE : View.GONE);
        searchInput.setVisibility(root && currentTab == TAB_SEARCH ? View.VISIBLE : View.GONE);
        signIn.setVisibility(root && !YtAuth.isSignedIn(app) ? View.VISIBLE : View.GONE);
    }

    private void load() {
        loadedOnce = true;
        final int gen = ++generation;
        final boolean signedIn = YtAuth.isSignedIn(app);
        final String query = searchInput.getText().toString().trim();
        final String browseId = stack.isEmpty() ? null : stack.peek()[0];
        final int tab = currentTab;

        if (browseId == null && (tab == TAB_LIKED || tab == TAB_PLAYLISTS) && !signedIn) {
            show(new ArrayList<>(), new HashMap<>(), "Sign in with your Google account to see your library here.");
            return;
        }
        if (browseId == null && tab == TAB_SEARCH && query.isEmpty()) {
            show(new ArrayList<>(), new HashMap<>(), "Type a song or artist and press search.");
            return;
        }

        emptyBox.setVisibility(View.GONE);
        if (!pull.isRefreshing()) {
            items.clear();
            adapter.notifyDataSetChanged();
            progress.setVisibility(View.VISIBLE);
        }

        io.execute(() -> {
            List<YtItem> result = null;
            String error = null;
            try {
                if (browseId != null) result = YtMusicApi.playlist(app, browseId);
                else if (tab == TAB_HOME) result = YtMusicApi.home(app);
                else if (tab == TAB_LIKED) result = YtMusicApi.liked(app);
                else if (tab == TAB_PLAYLISTS) result = YtMusicApi.libraryPlaylists(app);
                else result = YtMusicApi.searchAll(app, query);
            } catch (Exception e) {
                error = friendly(e);
            }
            final List<YtItem> r = result;
            final String err = error;
            final Map<String, Song> found = new HashMap<>();
            if (r != null) {
                List<YtTrack> tracks = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                for (YtItem i : r) {
                    if (!i.isSong()) continue;
                    tracks.add(i.track);
                    ids.add(i.track.videoId);
                }
                found.putAll(YtLocal.match(app, tracks));
                if (browseId == null && tab == TAB_LIKED) YtLikes.replaceAll(app, ids);
            }
            main.post(() -> {
                if (gen != generation) return;
                pull.setRefreshing(false);
                if (err != null) {
                    show(new ArrayList<>(), found, err);
                    emptyIcon.setVisibility(View.VISIBLE);
                    retry.setVisibility(View.VISIBLE);
                } else {
                    show(r, found, r.isEmpty() ? "Nothing here yet." : null);
                }
            });
        });
    }

    private void refreshLocal() {
        final int gen = generation;
        final List<YtTrack> tracks = new ArrayList<>();
        for (YtItem i : items) if (i.isSong()) tracks.add(i.track);
        io.execute(() -> {
            final Map<String, Song> found = YtLocal.match(app, tracks);
            main.post(() -> {
                if (gen != generation) return;
                local = found;
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void show(List<YtItem> list, Map<String, Song> found, @Nullable String message) {
        progress.setVisibility(View.GONE);
        pull.setRefreshing(false);
        local = found;
        items.clear();
        items.addAll(list);
        adapter.notifyDataSetChanged();
        empty.setText(message);
        emptyIcon.setVisibility(View.GONE);
        retry.setVisibility(View.GONE);
        emptyBox.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    /** A calm sentence instead of a raw exception message. */
    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        Throwable t = e;
        boolean net = false;
        while (t != null) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                    || t instanceof java.net.SocketTimeoutException || t instanceof javax.net.ssl.SSLException) {
                net = true;
            }
            t = t.getCause();
        }
        String low = m.toLowerCase(Locale.ROOT);
        if (net || low.contains("unable to resolve host") || low.contains("no address associated")
                || low.contains("timeout") || low.contains("timed out") || low.contains("failed to connect")
                || low.contains("network is unreachable")) {
            return "You're offline\nCheck your connection, then try again. Songs on your phone still play.";
        }
        return "Couldn't load YouTube Music\nSomething went wrong on the way. Try again in a moment.";
    }

    /** The media id of the song that is playing now (null if none): its row gets the color wash. */
    void setPlayingId(@Nullable String id) {
        if ((id == null && playingId == null) || (id != null && id.equals(playingId))) return;
        playingId = id;
        adapter.notifyDataSetChanged();
    }

    private boolean isPlaying(YtItem it) {
        if (playingId == null || !it.isSong()) return false;
        if (playingId.equals(it.track.mediaId())) return true;
        Song s = local.get(it.track.videoId); // playing from the copy on the phone
        return s != null && playingId.equals(String.valueOf(s.id));
    }

    // ---------- playback ----------

    private void onItemClick(int position) {
        YtItem item = items.get(position);
        if (item.isHeader()) return;
        if (!item.isSong()) {
            stack.push(new String[]{item.browseId, item.title});
            updateChrome();
            host.setTitle(item.title);
            load();
            return;
        }
        MediaController c = host.controller();
        if (c == null) {
            Toast.makeText(app, R.string.not_ready, Toast.LENGTH_SHORT).show();
            return;
        }
        List<MediaItem> queue = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < items.size(); i++) {
            YtItem it = items.get(i);
            if (!it.isSong()) continue;
            if (i == position) start = queue.size();
            queue.add(YtLocal.itemFor(it.track, local)); // the file on the phone when there is one
        }
        c.setMediaItems(queue, start, 0);
        c.prepare();
        c.play();
    }

    private void onItemLongClick(int position, View anchor) {
        final YtItem item = items.get(position);
        if (!item.isSong()) return;
        ThemedPopupMenu pm = new ThemedPopupMenu(anchor.getContext(), anchor);
        pm.getMenu().add(0, 1, 0, "Play next");
        pm.getMenu().add(0, 2, 1, "Add to queue");
        if (!local.containsKey(item.track.videoId)) pm.getMenu().add(0, 3, 2, "Download to phone");
        pm.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 3) {
                YtDownloads.start(app, item.track.videoId, item.track.title, item.track.artist,
                        item.track.thumbUrl);
                return true;
            }
            MediaController c = host.controller();
            if (c == null) {
                Toast.makeText(app, R.string.not_ready, Toast.LENGTH_SHORT).show();
                return true;
            }
            MediaItem mi2 = YtLocal.itemFor(item.track, local);
            if (c.getMediaItemCount() == 0) {
                c.setMediaItem(mi2);
                c.prepare();
                c.play();
            } else if (mi.getItemId() == 1) {
                c.addMediaItem(c.getCurrentMediaItemIndex() + 1, mi2);
                Toast.makeText(app, "Playing next", Toast.LENGTH_SHORT).show();
            } else {
                c.addMediaItem(mi2);
                Toast.makeText(app, "Added to queue", Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        pm.show();
    }

    // ---------- list ----------

    private final class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.Holder> {

        @Override
        public int getItemViewType(int position) {
            return items.get(position).isHeader() ? 1 : 0;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(viewType == 1 ? R.layout.item_section_header : R.layout.item_song,
                            parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            YtItem it = items.get(position);
            h.title.setText(it.title);
            if (it.isHeader()) return;
            boolean playing = isPlaying(it);
            h.title.setTextColor(playing
                    ? com.google.android.material.color.MaterialColors.getColor(h.itemView,
                            com.google.android.material.R.attr.colorPrimary)
                    : com.google.android.material.color.MaterialColors.getColor(h.itemView,
                            com.google.android.material.R.attr.colorOnSurface));
            h.title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", playing ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            h.itemView.setBackgroundResource(playing ? R.drawable.bg_song_row_playing : R.drawable.bg_song_row);
            boolean offline = it.isSong() && local.containsKey(it.track.videoId);
            h.subtitle.setText(offline ? it.subtitle + " • On phone" : it.subtitle);
            h.tags.setVisibility(View.GONE);
            long ms = it.isSong() ? it.track.durationMs : 0;
            h.duration.setText(!it.isSong() ? "›" : ms > 0 ? formatTime(ms) : "");
            String key = it.isSong() ? it.track.mediaId() : "b:" + it.browseId;
            ArtLoader.load(h.itemView.getContext(), key.hashCode(), null,
                    it.thumbUrl == null ? null : Uri.parse(it.thumbUrl), h.art, 144,
                    R.drawable.ic_art_placeholder);
            h.itemView.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) onItemClick(p);
            });
            h.itemView.setOnLongClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p == RecyclerView.NO_POSITION) return false;
                onItemLongClick(p, v);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView art;
            final TextView title, subtitle, tags, duration;

            Holder(View v) {
                super(v);
                art = v.findViewById(R.id.song_art);
                title = v.findViewById(R.id.song_title);
                subtitle = v.findViewById(R.id.song_subtitle);
                tags = v.findViewById(R.id.song_tags);
                duration = v.findViewById(R.id.song_duration);
            }
        }
    }

    private static String formatTime(long ms) {
        long total = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60);
    }
}
