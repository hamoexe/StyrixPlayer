package com.hamo.styrixplayer.tags;

/**
 * The tags the editor deals with. When used as an EDIT, a null text field means "leave it as it
 * is" and an empty string means "remove it". When returned by a read, null means "not present".
 */
public final class TagData {
    public enum CoverMode { KEEP, SET, REMOVE }

    public String title, artist, album, albumArtist, year, track, genre;

    /** Read: the embedded picture (or null). Edit: the new picture when coverMode == SET. */
    public byte[] cover;
    public CoverMode coverMode = CoverMode.KEEP;

    public boolean hasText() {
        return title != null || artist != null || album != null || albumArtist != null
                || year != null || track != null || genre != null;
    }
}
