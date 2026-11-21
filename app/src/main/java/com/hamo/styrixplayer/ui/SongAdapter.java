package com.hamo.styrixplayer.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Song;

import android.graphics.Typeface;

import java.util.List;
import java.util.Locale;

public class SongAdapter extends RecyclerView.Adapter<SongAdapter.Holder> {

    public interface OnSongClick {
        void onClick(int position);
    }

    public interface OnSongLongClick {
        void onLongClick(int position, View anchor);
    }

    private final List<Song> songs;
    private final OnSongClick listener;
    private final OnSongLongClick longListener;

    @Nullable private String playingId;
    private boolean colorsReady;
    private int activeColor, normalColor;

    public SongAdapter(List<Song> songs, OnSongClick listener, OnSongLongClick longListener) {
        this.songs = songs;
        this.listener = listener;
        this.longListener = longListener;
    }

    /** Highlights the row of the song that is currently playing. */
    public void setPlayingId(@Nullable String id) {
        String old = playingId;
        if ((id == null && old == null) || (id != null && id.equals(old))) return;
        playingId = id;
        for (int i = 0; i < songs.size(); i++) {
            String sid = String.valueOf(songs.get(i).id);
            if (sid.equals(old) || sid.equals(id)) notifyItemChanged(i);
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (!colorsReady) {
            activeColor = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorPrimary);
            normalColor = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface);
            colorsReady = true;
        }
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_song, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Song s = songs.get(position);
        boolean playing = String.valueOf(s.id).equals(playingId);

        h.title.setText(s.cleanTitle);
        h.title.setTextColor(playing ? activeColor : normalColor);
        // the playing row gets a real color wash (primary container follows the cover live)
        h.itemView.setBackgroundResource(playing ? R.drawable.bg_song_row_playing : R.drawable.bg_song_row);
        h.title.setTypeface(Typeface.create("sans-serif-medium", playing ? Typeface.BOLD : Typeface.NORMAL));

        h.subtitle.setText(songLine(s));
        String tags = s.tagsText();
        h.tags.setText(tags);
        h.tags.setVisibility(tags.isEmpty() ? View.GONE : View.VISIBLE);
        h.duration.setText(formatTime(s.durationMs));
        ArtLoader.load(h.itemView.getContext(), s, h.art, 144);

        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onClick(pos);
        });
        h.itemView.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            longListener.onLongClick(pos, v);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return songs.size();
    }

    /** "Artist • Album", but albums that only repeat the title or the app name are left out. */
    static String songLine(Song s) {
        String album = s.album == null ? "" : s.album.trim();
        if (album.isEmpty() || "<unknown>".equals(album)) return s.artistLine;
        String a = album.toLowerCase(Locale.ROOT);
        String title = s.title == null ? "" : s.title.toLowerCase(Locale.ROOT);
        String clean = s.cleanTitle == null ? "" : s.cleanTitle.toLowerCase(Locale.ROOT);
        boolean generic = a.equals("styrixplayer") || a.equals("download") || a.equals("downloads")
                || a.equals("music") || a.equals("unknown") || a.equals("0");
        boolean repeatsTitle = a.equals(title) || a.equals(clean)
                || a.equals(clean + " - single") || a.equals(title + " - single")
                || a.equals(clean + " - ep") || a.equals(title + " - ep")
                || a.startsWith(clean + " (") && a.endsWith("single)");
        if (generic || repeatsTitle) return s.artistLine;
        return s.artistLine + " • " + album;
    }

    static String formatTime(long ms) {
        long total = ms / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60);
    }

    static class Holder extends RecyclerView.ViewHolder {
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
