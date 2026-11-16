package com.hamo.styrixplayer.data;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

public final class SongScanner {

    private static final Uri ALBUM_ART = Uri.parse("content://media/external/audio/albumart");

    private SongScanner() {}

    /** Queries MediaStore for music. Call off the main thread. */
    public static List<Song> scan(Context context, long minDurationMs) {
        List<Song> songs = new ArrayList<>();

        Uri collection = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION
        };
        String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                + MediaStore.Audio.Media.DURATION + " >= ?";
        String[] args = {String.valueOf(minDurationMs)};
        String sort = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

        try (Cursor c = context.getContentResolver().query(collection, projection, selection, args, sort)) {
            if (c == null) return songs;
            int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
            int durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);

            while (c.moveToNext()) {
                long id = c.getLong(idCol);
                String artist = c.getString(artistCol);
                if (artist == null || "<unknown>".equals(artist)) artist = "Unknown artist";
                songs.add(new Song(
                        id,
                        c.getString(titleCol),
                        artist,
                        c.getString(albumCol),
                        c.getLong(durCol),
                        ContentUris.withAppendedId(collection, id),
                        ContentUris.withAppendedId(ALBUM_ART, c.getLong(albumIdCol))));
            }
        }
        return songs;
    }
}
