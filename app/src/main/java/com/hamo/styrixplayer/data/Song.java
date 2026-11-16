package com.hamo.styrixplayer.data;

import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import java.util.ArrayList;
import java.util.List;

public class Song {
    public static final String EXTRA_TAGS = "tags";
    public static final String TAG_SEPARATOR = "\u001F";

    public final long id;

    /** What the file's tags said. */
    public final String rawTitle, rawArtist, rawAlbum;
    /** The text in use: the raw tags, or whatever you typed in the metadata editor. */
    public final String title, artist, album;
    /** Cleaned-up versions used for display. */
    public final String cleanTitle, artistLine;
    public final List<String> tags;

    public final long durationMs;
    public final Uri uri;
    /** Art in use (picked photo, or the original), and the original from MediaStore. */
    public final Uri artUri, rawArtUri;

    public Song(long id, String title, String artist, String album,
                long durationMs, Uri uri, Uri artUri) {
        this(id, title, artist, album, title, artist, album, durationMs, uri, artUri, artUri);
    }

    private Song(long id, String rawTitle, String rawArtist, String rawAlbum,
                 String title, String artist, String album,
                 long durationMs, Uri uri, Uri artUri, Uri rawArtUri) {
        this.id = id;
        this.rawTitle = rawTitle;
        this.rawArtist = rawArtist;
        this.rawAlbum = rawAlbum;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
        this.uri = uri;
        this.artUri = artUri;
        this.rawArtUri = rawArtUri;
        TitleParser.Result r = TitleParser.parse(title, artist);
        this.cleanTitle = r.title;
        this.artistLine = r.artistLine;
        this.tags = r.tags;
    }

    /** Same song with different text (from the metadata editor). Raw values stay untouched. */
    public Song withMeta(String newTitle, String newArtist, @Nullable String newAlbum) {
        return withMeta(newTitle, newArtist, newAlbum, artUri);
    }

    /** Same, with a different cover photo (null/empty path means the original art). */
    public Song withMeta(String newTitle, String newArtist, @Nullable String newAlbum,
                         @Nullable Uri newArt) {
        return new Song(id, rawTitle, rawArtist, rawAlbum, newTitle, newArtist, newAlbum,
                durationMs, uri, newArt == null ? rawArtUri : newArt, rawArtUri);
    }

    /** Labels for display, e.g. "Slowed · Reverb". Empty when there are none. */
    public String tagsText() {
        StringBuilder sb = new StringBuilder();
        for (String t : tags) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(t);
        }
        return sb.toString();
    }

    public MediaItem toMediaItem() {
        Bundle extras = new Bundle();
        StringBuilder joined = new StringBuilder();
        for (String t : new ArrayList<>(tags)) {
            if (joined.length() > 0) joined.append(TAG_SEPARATOR);
            joined.append(t);
        }
        extras.putString(EXTRA_TAGS, joined.toString());

        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(cleanTitle)
                .setArtist(artistLine)
                .setAlbumTitle(album)
                .setArtworkUri(artUri)
                .setExtras(extras)
                .build();
        return new MediaItem.Builder()
                .setMediaId(String.valueOf(id))
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build();
    }
}
