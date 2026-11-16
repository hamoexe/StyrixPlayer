package com.hamo.styrixplayer.yt;

import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import com.hamo.styrixplayer.data.Song;

/** A song that lives on YouTube Music instead of on the phone. */
public final class YtTrack {

    public static final String ID_PREFIX = "yt:";
    /** Scheme the player service resolves into a real stream URL right before playing. */
    public static final String SCHEME = "ytm";

    public final String videoId;
    public final String title, artist, album;
    public final long durationMs;
    @Nullable public final String thumbUrl;

    public YtTrack(String videoId, String title, String artist, @Nullable String album,
                   long durationMs, @Nullable String thumbUrl) {
        this.videoId = videoId;
        this.title = title;
        this.artist = artist;
        this.album = album == null ? "" : album;
        this.durationMs = durationMs;
        this.thumbUrl = thumbUrl;
    }

    public static boolean isYtId(@Nullable String mediaId) {
        return mediaId != null && mediaId.startsWith(ID_PREFIX);
    }

    public String mediaId() {
        return ID_PREFIX + videoId;
    }

    public MediaItem toMediaItem() {
        Bundle extras = new Bundle();
        extras.putString(Song.EXTRA_TAGS, "");
        MediaMetadata md = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(thumbUrl == null ? null : Uri.parse(thumbUrl))
                .setExtras(extras)
                .build();
        return new MediaItem.Builder()
                .setMediaId(mediaId())
                .setUri(Uri.parse(SCHEME + ":" + videoId))
                .setMediaMetadata(md)
                .build();
    }
}
