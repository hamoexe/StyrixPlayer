package com.hamo.styrixplayer.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Favorites, playlists and per-track speed. Plain SQLite, no extra libraries. */
public final class Db extends SQLiteOpenHelper {

    public static final class Playlist {
        public final long id;
        public final String name;
        public final int count;

        Playlist(long id, String name, int count) {
            this.id = id;
            this.name = name;
            this.count = count;
        }
    }

    private static Db instance;

    public static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "styrix.db", null, 6);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE favorites (song_id INTEGER PRIMARY KEY, added_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "name TEXT NOT NULL, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE playlist_songs (playlist_id INTEGER NOT NULL, "
                + "song_id INTEGER NOT NULL, position INTEGER NOT NULL, "
                + "PRIMARY KEY (playlist_id, song_id))");
        db.execSQL("CREATE TABLE track_speed (song_id INTEGER PRIMARY KEY, speed REAL NOT NULL)");
        createMetaTable(db);
        createLyricsTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createMetaTable(db);
        if (oldVersion < 3 && oldVersion >= 2) {
            db.execSQL("ALTER TABLE meta_override ADD COLUMN art TEXT");
        }
        if (oldVersion < 4) createLyricsTable(db);
        if (oldVersion < 5) {
            // new lyrics providers: forget earlier "nothing found" results
            db.execSQL("DELETE FROM lyrics WHERE synced IS NULL AND plain IS NULL AND instrumental = 0");
        }
        if (oldVersion < 6 && oldVersion >= 4) {
            // lyrics you edited / saved yourself are never replaced by an online lookup
            db.execSQL("ALTER TABLE lyrics ADD COLUMN custom INTEGER NOT NULL DEFAULT 0");
        }
    }

    private static void createLyricsTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS lyrics (song_id INTEGER PRIMARY KEY, "
                + "synced TEXT, plain TEXT, instrumental INTEGER NOT NULL DEFAULT 0, "
                + "fetched_at INTEGER NOT NULL, offset_ms INTEGER NOT NULL DEFAULT 0, "
                + "custom INTEGER NOT NULL DEFAULT 0)");
    }

    private static void createMetaTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS meta_override (song_id INTEGER PRIMARY KEY, "
                + "title TEXT, artist TEXT, album TEXT, art TEXT)");
    }

    // ---------- lyrics cache ----------

    public static final class LyricsRow {
        @Nullable public final String synced, plain;
        public final boolean instrumental, custom;
        public final long fetchedAt, offsetMs;

        LyricsRow(@Nullable String synced, @Nullable String plain, boolean instrumental,
                  long fetchedAt, long offsetMs, boolean custom) {
            this.custom = custom;
            this.synced = synced;
            this.plain = plain;
            this.instrumental = instrumental;
            this.fetchedAt = fetchedAt;
            this.offsetMs = offsetMs;
        }
    }

    @Nullable
    public LyricsRow getLyrics(long songId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT synced, plain, instrumental, fetched_at, offset_ms, custom FROM lyrics WHERE song_id = ?",
                new String[]{String.valueOf(songId)})) {
            if (!c.moveToFirst()) return null;
            return new LyricsRow(c.isNull(0) ? null : c.getString(0),
                    c.isNull(1) ? null : c.getString(1),
                    c.getInt(2) != 0, c.getLong(3), c.getLong(4), c.getInt(5) != 0);
        }
    }

    /** Saves a lookup result (all-null and not instrumental = "nothing found"). Keeps the sync offset. */
    public void putLyrics(long songId, @Nullable String synced, @Nullable String plain,
                          boolean instrumental) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("song_id", songId);
        cv.put("fetched_at", System.currentTimeMillis());
        db.insertWithOnConflict("lyrics", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues up = new ContentValues();
        up.put("synced", synced);
        up.put("plain", plain);
        up.put("instrumental", instrumental ? 1 : 0);
        up.put("fetched_at", System.currentTimeMillis());
        db.update("lyrics", up, "song_id = ?", new String[]{String.valueOf(songId)});
    }

    /** Lyrics typed, edited, translated or imported by the user: kept forever, works offline. */
    public void putCustomLyrics(long songId, @Nullable String synced, @Nullable String plain) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("song_id", songId);
        cv.put("fetched_at", System.currentTimeMillis());
        db.insertWithOnConflict("lyrics", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        ContentValues up = new ContentValues();
        up.put("synced", synced);
        up.put("plain", plain);
        up.put("instrumental", 0);
        up.put("custom", 1);
        up.put("fetched_at", System.currentTimeMillis());
        db.update("lyrics", up, "song_id = ?", new String[]{String.valueOf(songId)});
    }

    /** Forgets the saved lyrics (edited or not) so the next lookup goes online again. */
    public void deleteLyrics(long songId) {
        getWritableDatabase().delete("lyrics", "song_id = ?", new String[]{String.valueOf(songId)});
    }

    public void setLyricsOffset(long songId, long offsetMs) {
        ContentValues up = new ContentValues();
        up.put("offset_ms", offsetMs);
        getWritableDatabase().update("lyrics", up, "song_id = ?",
                new String[]{String.valueOf(songId)});
    }

    // ---------- favorites ----------

    public boolean isFavorite(long songId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM favorites WHERE song_id = ?", new String[]{String.valueOf(songId)})) {
            return c.moveToFirst();
        }
    }

    /** Returns the new state (true = now a favorite). */
    public boolean toggleFavorite(long songId) {
        SQLiteDatabase db = getWritableDatabase();
        if (isFavorite(songId)) {
            db.delete("favorites", "song_id = ?", new String[]{String.valueOf(songId)});
            return false;
        }
        ContentValues cv = new ContentValues();
        cv.put("song_id", songId);
        cv.put("added_at", System.currentTimeMillis());
        db.insertWithOnConflict("favorites", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public List<Long> favoriteIds() {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT song_id FROM favorites ORDER BY added_at DESC", null)) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }

    // ---------- playlists ----------

    public long createPlaylist(String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("playlists", null, cv);
    }

    public void renamePlaylist(long id, String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        getWritableDatabase().update("playlists", cv, "id = ?", new String[]{String.valueOf(id)});
    }

    public void deletePlaylist(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String[] arg = {String.valueOf(id)};
        db.delete("playlist_songs", "playlist_id = ?", arg);
        db.delete("playlists", "id = ?", arg);
    }

    public List<Playlist> playlists() {
        List<Playlist> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_songs s WHERE s.playlist_id = p.id) "
                        + "FROM playlists p ORDER BY p.created_at DESC", null)) {
            while (c.moveToNext()) out.add(new Playlist(c.getLong(0), c.getString(1), c.getInt(2)));
        }
        return out;
    }

    @Nullable
    public Playlist playlist(long id) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT p.id, p.name, (SELECT COUNT(*) FROM playlist_songs s WHERE s.playlist_id = p.id) "
                        + "FROM playlists p WHERE p.id = ?", new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) return new Playlist(c.getLong(0), c.getString(1), c.getInt(2));
        }
        return null;
    }

    /** Returns true if the song was added, false if it was already in the playlist. */
    public boolean addToPlaylist(long playlistId, long songId) {
        SQLiteDatabase db = getWritableDatabase();
        int next = 0;
        try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs "
                + "WHERE playlist_id = ?", new String[]{String.valueOf(playlistId)})) {
            if (c.moveToFirst()) next = c.getInt(0);
        }
        ContentValues cv = new ContentValues();
        cv.put("playlist_id", playlistId);
        cv.put("song_id", songId);
        cv.put("position", next);
        return db.insertWithOnConflict("playlist_songs", null, cv,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public void removeFromPlaylist(long playlistId, long songId) {
        getWritableDatabase().delete("playlist_songs", "playlist_id = ? AND song_id = ?",
                new String[]{String.valueOf(playlistId), String.valueOf(songId)});
    }

    public List<Long> playlistSongIds(long playlistId) {
        List<Long> ids = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT song_id FROM playlist_songs WHERE playlist_id = ? ORDER BY position ASC",
                new String[]{String.valueOf(playlistId)})) {
            while (c.moveToNext()) ids.add(c.getLong(0));
        }
        return ids;
    }

    public Set<Long> playlistsContaining(long songId) {
        Set<Long> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT playlist_id FROM playlist_songs WHERE song_id = ?",
                new String[]{String.valueOf(songId)})) {
            while (c.moveToNext()) out.add(c.getLong(0));
        }
        return out;
    }

    // ---------- metadata overrides (what you typed in the metadata editor) ----------

    public void setMeta(long songId, String title, String artist, String album, String art) {
        ContentValues cv = new ContentValues();
        cv.put("art", art);
        cv.put("song_id", songId);
        cv.put("title", title);
        cv.put("artist", artist);
        cv.put("album", album);
        getWritableDatabase().insertWithOnConflict("meta_override", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void clearMeta(long songId) {
        getWritableDatabase().delete("meta_override", "song_id = ?",
                new String[]{String.valueOf(songId)});
    }

    @Nullable
    public String[] getMeta(long songId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT title, artist, album, art FROM meta_override WHERE song_id = ?",
                new String[]{String.valueOf(songId)})) {
            if (c.moveToFirst()) return new String[]{nz(c.getString(0)), nz(c.getString(1)), nz(c.getString(2)), nz(c.getString(3))};
        }
        return null;
    }

    public Map<Long, String[]> allMeta() {
        Map<Long, String[]> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT song_id, title, artist, album, art FROM meta_override", null)) {
            while (c.moveToNext()) {
                out.put(c.getLong(0), new String[]{nz(c.getString(1)), nz(c.getString(2)), nz(c.getString(3)), nz(c.getString(4))});
            }
        }
        return out;
    }

    private static String nz(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** Removes everything stored about a song that no longer exists. */
    public void forgetSong(long songId) {
        String[] a = {String.valueOf(songId)};
        getWritableDatabase().delete("favorites", "song_id = ?", a);
        getWritableDatabase().delete("playlist_songs", "song_id = ?", a);
        getWritableDatabase().delete("track_speed", "song_id = ?", a);
        getWritableDatabase().delete("meta_override", "song_id = ?", a);
    }

    // ---------- per-track speed ----------

    public float getSpeed(long songId, float def) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT speed FROM track_speed WHERE song_id = ?", new String[]{String.valueOf(songId)})) {
            if (c.moveToFirst()) return c.getFloat(0);
        }
        return def;
    }

    public void setSpeed(long songId, float speed) {
        ContentValues cv = new ContentValues();
        cv.put("song_id", songId);
        cv.put("speed", speed);
        getWritableDatabase().insertWithOnConflict("track_speed", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }
}
