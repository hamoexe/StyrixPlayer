package com.hamo.styrixplayer.yt;

import androidx.annotation.Nullable;

/** One row on the YouTube Music screen: either a playable song or a playlist/album to open. */
public final class YtItem {
    @Nullable public final YtTrack track;     // set for songs
    @Nullable public final String browseId;   // set for playlists and albums
    public final String title, subtitle;
    @Nullable public final String thumbUrl;

    private YtItem(@Nullable YtTrack track, @Nullable String browseId, String title,
                   String subtitle, @Nullable String thumbUrl) {
        this.track = track;
        this.browseId = browseId;
        this.title = title;
        this.subtitle = subtitle;
        this.thumbUrl = thumbUrl;
    }

    public static YtItem song(YtTrack t) {
        return new YtItem(t, null, t.title, t.artist, t.thumbUrl);
    }

    public static YtItem folder(String browseId, String title, String subtitle, @Nullable String thumb) {
        return new YtItem(null, browseId, title, subtitle, thumb);
    }

    /** A section heading inside a list ("Artists", "Songs" ...). Not clickable. */
    public static YtItem header(String title) {
        return new YtItem(null, null, title, "", null);
    }

    public boolean isHeader() {
        return track == null && browseId == null;
    }

    public boolean isSong() {
        return track != null;
    }
}
