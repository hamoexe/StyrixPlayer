package com.hamo.styrixplayer.lyrics;

import android.content.Context;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Song;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.zip.InflaterInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds lyrics on LRCLIB (https://lrclib.net: free, no key, time-synced) and caches them in the
 * database. Call {@link #load} from a background thread.
 */
public final class LyricsRepo {

    private static final String BASE = "https://lrclib.net/api/";
    // LyricsPlus (the server behind the YouLyPlus extension): word-synced lyrics from Apple Music,
    // Musixmatch, QQ etc. Tried first; LRCLIB is the fallback.
    private static final String PLUS = "https://lyricsplus.binimum.org/v2/lyrics/get";
    // LRCLIB asks clients to say who they are (app name, version, link): higher rate limit.
    private static final String USER_AGENT = "StyrixPlayer/1.0 (https://github.com/hamoexe)";
    /** "Not found" is remembered this long before we ask again. */
    private static final long RETRY_NOT_FOUND_MS = 3L * 24 * 60 * 60 * 1000;
    /** How far (ms) a search hit's length may be from our file's length. */
    private static final long MAX_DURATION_DIFF_MS = 6000;

    private static final Pattern FEAT = Pattern.compile(
            "\\s*[(\\[]?\\s*\\b(feat|ft|featuring)\\b\\.?[^)\\]]*[)\\]]?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "\\s*(,|&|\\bx\\b|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bwith\\b|/|;)\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Not-found results saved before the smarter lookup existed are tried again. */
    private static final long LOGIC_CHANGED_AT = 1793440000000L;

    private static final int STRICT = 0, CLOSEST = 1, ANY = 2;

    private static final Pattern BRACKETS = Pattern.compile(
            "\\s*[(\\[{\\u3010\\uFF08][^)\\]}\\u3011\\uFF09]*[)\\]}\\u3011\\uFF09]");
    private static final Pattern SPEED_TAG = Pattern.compile(
            "\\b(slowed|sped|speed(ed)? up|nightcore|daycore|slow)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TAIL_TAG = Pattern.compile(
            "\\s*[-\\u2013\\u2014]\\s*(ultra |mega |super )?(slowed|sped up|speed up|nightcore|reverb|remix|"
                    + "edit|version|instrumental)[^-]*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STAMP = Pattern.compile(
            "([\\[<])(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?([\\]>])");

    private LyricsRepo() {}

    private static final class Cand {
        final String title, artist, album;

        Cand(String title, String artist, String album) {
            this.title = title == null ? "" : title.trim();
            this.artist = artist == null ? "" : artist.trim();
            this.album = album == null ? "" : album.trim();
        }
    }

    private static final class Found {
        @Nullable final String synced, plain;
        final boolean instrumental;
        /** Length of the original recording (LRCLIB only), 0 when unknown. */
        final long durMs;

        Found(@Nullable String synced, @Nullable String plain, boolean instrumental) {
            this(synced, plain, instrumental, 0);
        }

        Found(@Nullable String synced, @Nullable String plain, boolean instrumental, long durMs) {
            this.synced = synced;
            this.plain = plain;
            this.instrumental = instrumental;
            this.durMs = durMs;
        }

        /** Same lyrics with every time stamp multiplied by k (slowed / sped-up versions). */
        Found scaled(double k) {
            return new Found(synced == null ? null : scaleStamps(synced, k), plain, instrumental, durMs);
        }
    }

    /** Cached result, or a network lookup. Never throws. */
    public static Lyrics load(Context c, long songId, String title, String artist,
                              @Nullable String album, long durationMs, boolean force) {
        return load(c, songId, title, artist, album, durationMs, force, "auto");
    }

    public static Lyrics load(Context c, long songId, String title, String artist,
                              @Nullable String album, long durationMs, boolean force, String provider) {
        List<Cand> cands = new ArrayList<>();
        cands.add(new Cand(title, artist, album));
        return load(c, songId, cands, false, false, durationMs, force, provider);
    }

    /**
     * Smarter lookup for messy titles: tries the clean title with the real artist, the original
     * artist of a remix ("Altare Remix" of an Ariana Grande song), the "Artist - Title" split of
     * the raw title and finally the title alone. Remix / slowed versions don't need to match the
     * original's length, and slowed / sped-up ones get their time stamps stretched to fit.
     */
    public static Lyrics load(Context c, long songId, Song song, long durationMs, boolean force) {
        return load(c, songId, song, durationMs, force, "auto");
    }

    public static Lyrics load(Context c, long songId, Song song, long durationMs, boolean force, String provider) {
        boolean speed = false;
        for (String t : song.tags) if (SPEED_TAG.matcher(t).find()) speed = true;
        return load(c, songId, candidates(song), !song.tags.isEmpty(), speed, durationMs, force, provider);
    }

    private static Lyrics load(Context c, long songId, List<Cand> cands, boolean relaxed,
                               boolean speed, long durationMs, boolean force, String provider) {
        Db db = Db.get(c);
        Db.LyricsRow row = db.getLyrics(songId);
        if (row != null && row.custom) return fromRow(row);   // your own version always wins
        if (!force && row != null) {
            boolean hasContent = row.instrumental || notEmpty(row.synced) || notEmpty(row.plain);
            // A song on the phone keeps what it has: a "not found" is not searched again by itself
            // (only "Search again" does that). Streamed songs retry after a few days.
            boolean fresh = (songId > 0 || System.currentTimeMillis() - row.fetchedAt < RETRY_NOT_FOUND_MS)
                    && row.fetchedAt > LOGIC_CHANGED_AT;
            if (hasContent || fresh) return fromRow(row);
        }
        try {
            Found f = null;
            StringBuilder errors = new StringBuilder();
            for (Cand cand : cands) {
                try {
                    f = fetch(cand, durationMs, relaxed, speed, provider);
                } catch (IOException e) {
                    if (errors.length() > 0) errors.append('\n');
                    errors.append(e.getMessage());
                }
                if (f != null) break;
            }
            if (f == null && errors.length() > 0) throw new IOException(errors.toString());
            if (f == null) db.putLyrics(songId, null, null, false);
            else db.putLyrics(songId, f.synced, f.plain, f.instrumental);
            return fromRow(db.getLyrics(songId));
        } catch (IOException | RuntimeException e) {
            // network trouble: show the old copy if we have one, but don't cache the failure
            if (row != null) return fromRow(row);
            return Lyrics.error(e.getMessage());
        }
    }

    // ---------- candidates ----------

    private static List<Cand> candidates(Song s) {
        List<Cand> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String line = s.artistLine == null ? "" : s.artistLine;
        String base = line, orig = "";
        int dot = line.indexOf(" \u00B7 orig. ");
        if (dot >= 0) {
            base = line.substring(0, dot).trim();
            orig = line.substring(dot + " \u00B7 orig. ".length()).trim();
        }
        String album = s.album;

        add(out, seen, new Cand(s.cleanTitle, base, album));
        if (!orig.isEmpty()) add(out, seen, new Cand(s.cleanTitle, orig, album));

        // raw "Artist - Title (Slowed)" split, in case the parser kept it whole
        String raw = BRACKETS.matcher(s.title).replaceAll("").trim();
        raw = TAIL_TAG.matcher(raw).replaceAll("").trim();
        int d = firstDash(raw);
        if (d > 0) {
            String left = raw.substring(0, d).trim();
            String right = raw.substring(d).replaceFirst("^\\s*[-\\u2013\\u2014]\\s*", "").trim();
            if (!left.isEmpty() && !right.isEmpty()) {
                add(out, seen, new Cand(right, left, album));
                add(out, seen, new Cand(right, base, album));
            }
        }
        String first = firstArtist(base);
        if (!first.isEmpty() && !first.equals(base)) add(out, seen, new Cand(s.cleanTitle, first, album));
        add(out, seen, new Cand(s.cleanTitle, "", ""));
        return out.size() > 5 ? new ArrayList<>(out.subList(0, 5)) : out;
    }

    private static void add(List<Cand> out, Set<String> seen, Cand c) {
        if (c.title.isEmpty()) return;
        if (seen.add((c.title + "|" + c.artist).toLowerCase(Locale.ROOT))) out.add(c);
    }

    private static int firstDash(String s) {
        int best = -1;
        for (String d : new String[]{" - ", " \u2013 ", " \u2014 "}) {
            int i = s.indexOf(d);
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }
    private static String firstArtist(String artist) {
    if (artist == null || artist.isEmpty()) return "";
    String[] parts = ARTIST_SPLIT.split(artist, 2);
    return parts.length > 0 ? parts[0].trim() : artist.trim();
}

    /** Multiplies every [mm:ss.xx] and <mm:ss.xx> stamp by k. */
    static String scaleStamps(String lrc, double k) {
        Matcher m = STAMP.matcher(lrc);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long ms = Long.parseLong(m.group(2)) * 60000L + Long.parseLong(m.group(3)) * 1000L;
            String frac = m.group(4);
            if (frac != null) {
                long f = Long.parseLong(frac);
                ms += frac.length() == 1 ? f * 100 : frac.length() == 2 ? f * 10 : f;
            }
            ms = Math.round(ms * k);
            String rep = m.group(1) + String.format(Locale.US, "%02d:%02d.%03d",
                    ms / 60000, (ms / 1000) % 60, ms % 1000) + m.group(5);
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Lyrics fromRow(@Nullable Db.LyricsRow row) {
        if (row == null) return Lyrics.of(Lyrics.State.NOT_FOUND);
        if (row.instrumental) return Lyrics.of(Lyrics.State.INSTRUMENTAL);
        if (notEmpty(row.synced)) {
            List<LrcParser.Line> lines = LrcParser.parse(row.synced);
            if (!lines.isEmpty()) return new Lyrics(Lyrics.State.SYNCED, lines, row.offsetMs);
        }
        if (notEmpty(row.plain)) {
            List<LrcParser.Line> lines = LrcParser.fromPlain(row.plain);
            if (!lines.isEmpty()) return new Lyrics(Lyrics.State.PLAIN, lines, 0);
        }
        return Lyrics.of(Lyrics.State.NOT_FOUND);
    }

    // ---------- lookup strategy ----------

    /** Tries each provider in turn. Throws only if nothing was found AND a provider failed. */
    @Nullable
    private static Found fetch(Cand cand, long durationMs, boolean relaxed, boolean speed, String provider)
            throws IOException {
        final String title = cand.title, artist = cand.artist, album = cand.album;
        StringBuilder errors = new StringBuilder();
        Found lr = null;
        if ("lrclib".equals(provider)) {
            try { return fetchLrclib(title, artist, album, durationMs, relaxed ? CLOSEST : STRICT); }
            catch (IOException | JSONException e) { throw new IOException("LRCLIB: " + e.getMessage()); }
        }
        if ("lyricsplus".equals(provider)) {
            try { return fetchPlus(title, artist, album, durationMs); }
            catch (IOException | JSONException e) { throw new IOException("LyricsPlus: " + e.getMessage()); }
        }
        if ("binilyrics".equals(provider)) {
            try { return fetchBini(title, artist, album, durationMs); }
            catch (IOException | JSONException e) { throw new IOException("BiniLyrics: " + e.getMessage()); }
        }
        if ("kugou".equals(provider)) {
            try { return fetchKugou(title, artist, durationMs, relaxed); }
            catch (IOException | JSONException e) { throw new IOException("Kugou: " + e.getMessage()); }
        }
        if ("qqmusic".equals(provider)) {
            try { return fetchQqMusic(title, artist, durationMs, relaxed); }
            catch (IOException | JSONException e) { throw new IOException("QQ Music: " + e.getMessage()); }
        }
        if ("netease".equals(provider)) {
            try { return fetchNetease(title, artist, durationMs, relaxed); }
            catch (IOException | JSONException e) { throw new IOException("NetEase: " + e.getMessage()); }
        }
        if (speed) {
            // LRCLIB first: it tells us how long the original is, so we can stretch the time stamps
            try {
                lr = fetchLrclib(title, artist, album, durationMs, ANY);
            } catch (IOException | JSONException e) {
                errors.append("LRCLIB: ").append(e.getMessage());
            }
            long orig = lr != null ? lr.durMs : 0;
            Found best = null;
            try {
                best = fetchPlus(title, artist, album, orig > 0 ? orig : durationMs);
            } catch (IOException | JSONException e) {
                if (errors.length() > 0) errors.append('\n');
                errors.append("LyricsPlus: ").append(e.getMessage());
            }
            if (best == null) {
                try {
                    best = fetchBini(title, artist, album, orig > 0 ? orig : durationMs);
                } catch (IOException | JSONException e) {
                    if (errors.length() > 0) errors.append('\n');
                    errors.append("BiniLyrics: ").append(e.getMessage());
                }
            }
            if (best == null) {
                try {
                    best = fetchKugou(title, artist, orig > 0 ? orig : durationMs, true);
                } catch (IOException | JSONException e) {
                    if (errors.length() > 0) errors.append('\n');
                    errors.append("Kugou: ").append(e.getMessage());
                }
            }
            if (best == null) {
                try {
                    best = fetchQqMusic(title, artist, orig > 0 ? orig : durationMs, true);
                } catch (IOException | JSONException e) {
                    if (errors.length() > 0) errors.append('\n');
                    errors.append("QQ Music: ").append(e.getMessage());
                }
            }
            if (best == null) {
                try {
                    best = fetchNetease(title, artist, orig > 0 ? orig : durationMs, true);
                } catch (IOException | JSONException e) {
                    if (errors.length() > 0) errors.append('\n');
                    errors.append("NetEase: ").append(e.getMessage());
                }
            }
            if (best == null) best = lr;
            if (best == null) {
                if (errors.length() > 0) throw new IOException(errors.toString());
                return null;
            }
            if (orig > 0 && durationMs > 0) {
                double k = (double) durationMs / orig;
                if (k > 0.5 && k < 2.0 && Math.abs(k - 1.0) > 0.03) best = best.scaled(k);
            }
            return best;
        }
        try {
            Found f = fetchPlus(title, artist, album, durationMs);
            if (f != null) return f;
        } catch (IOException | JSONException e) {
            errors.append("LyricsPlus: ").append(e.getMessage());
        }

        try {
            Found f = fetchBini(title, artist, album, durationMs);
            if (f != null) return f;
        } catch (IOException | JSONException e) {
            if (errors.length() > 0) errors.append('\n');
            errors.append("BiniLyrics: ").append(e.getMessage());
        }
        try {
            Found f = fetchKugou(title, artist, durationMs, relaxed);
            if (f != null) return f;
        } catch (IOException | JSONException e) {
            if (errors.length() > 0) errors.append('\n');
            errors.append("Kugou: ").append(e.getMessage());
        }
        try {
            Found f = fetchQqMusic(title, artist, durationMs, relaxed);
            if (f != null) return f;
        } catch (IOException | JSONException e) {
            if (errors.length() > 0) errors.append('\n');
            errors.append("QQ Music: ").append(e.getMessage());
        }
        try {
            Found f = fetchNetease(title, artist, durationMs, relaxed);
            if (f != null) return f;
        } catch (IOException | JSONException e) {
            if (errors.length() > 0) errors.append('\n');
            errors.append("NetEase: ").append(e.getMessage());
        }
        try {
            Found f = fetchLrclib(title, artist, album, durationMs, relaxed ? CLOSEST : STRICT);
            if (f != null) return f;
        } catch (IOException | JSONException e) {
            if (errors.length() > 0) errors.append('\n');
            errors.append("LRCLIB: ").append(e.getMessage());
        }
        if (errors.length() > 0) throw new IOException(errors.toString());
        return null;
    }

    @Nullable
    private static Found fetchPlus(String title, String artist, @Nullable String album, long durationMs)
            throws IOException, JSONException {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || isUnknown(artist)) return null;
        String url = PLUS + "?title=" + enc(t) + "&artist=" + enc(artist.trim())
                + (isUnknown(album) ? "" : "&album=" + enc(album.trim()))
                + (durationMs > 0 ? "&duration=" + String.format(Locale.US, "%.3f", durationMs / 1000.0) : "");
        String body = httpUrl(url, 20000);
        if (body == null) return null;
        String lrc = LyricsPlusParser.toEnhancedLrc(body);
        return lrc == null ? null : new Found(lrc, null, false);
    }

    @Nullable
    private static Found fetchLrclib(String title, String artist, @Nullable String album,
                                     long durationMs, int mode)
            throws IOException, JSONException {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return null;
        boolean knownArtist = !isUnknown(artist);
        String a = knownArtist ? artist.trim() : "";
        String al = isUnknown(album) ? "" : album.trim();
        long secs = Math.round(durationMs / 1000.0);

        // 1. exact signature (LRCLIB allows +-2 s on the length)
        if (mode == STRICT && knownArtist && secs > 0) {
            if (!al.isEmpty()) {
                Found f = fromRecord(getOne(t, a, al, secs));
                if (f != null) return f;
            }
            Found f = fromRecord(getOne(t, a, "", secs));
            if (f != null) return f;
        }

        // 2. looser search: strip "feat." and keep the first artist
        String t2 = FEAT.matcher(t).replaceAll("").trim();
        if (t2.isEmpty()) t2 = t;
        String a2 = firstArtist(a);
        Found f = pickBest(search("track_name=" + enc(t2)
                + (a.isEmpty() ? "" : "&artist_name=" + enc(a))), durationMs, mode);
        if (f != null) return f;
        if (!a2.isEmpty() && !a2.equals(a)) {   // "A & B" -> try just "A"
            f = pickBest(search("track_name=" + enc(t2) + "&artist_name=" + enc(a2)), durationMs, mode);
            if (f != null) return f;
        }

        // 3. free-text search as a last resort
        return pickBest(search("q=" + enc((a2 + " " + t2).trim())), durationMs, mode);
    }
    /**
     * BiniLyrics (lrc.red): searches, picks the result that really is this song (title, artist and
     * length are compared; the first hit is often a "(Slowed)" or another version), downloads its
     * TTML and converts it to word-timed LRC.
     */
    @Nullable
    private static Found fetchBini(String title, String artist, @Nullable String album, long durationMs)
            throws IOException, JSONException {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty() || isUnknown(artist)) return null;

        String searchResponse = httpUrl("https://lrc.red/api/v1?q=" + enc(t + " " + firstArtist(artist)), 20000);
        if (searchResponse == null) return null;
        JSONArray results = new JSONObject(searchResponse).optJSONArray("results");
        if (results == null || results.length() == 0) return null;

        String wantTitle = norm(t);
        String wantArtist = norm(artist);
        String lyricsUrl = null;
        int bestScore = Integer.MIN_VALUE;
        long bestDur = 0;
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("lyricsUrl", "");
            if (url.isEmpty()) continue;
            String name = norm(item.optString("track_name", ""));
            int score;
            if (name.isEmpty()) score = 0;
            else if (name.equals(wantTitle)) score = 4;
            else if (name.contains(wantTitle) || wantTitle.contains(name)) score = 2;
            else continue;                                   // a different song
            String who = norm(item.optString("artist_name", ""));
            if (!who.isEmpty() && !wantArtist.isEmpty()) {
                if (who.contains(wantArtist) || wantArtist.contains(who)) score += 2;
                else {
                    for (String part : ARTIST_SPLIT.split(artist)) {
                        String pn = norm(part);
                        if (!pn.isEmpty() && who.contains(pn)) { score += 2; break; }
                    }
                }
            }
            long dur = Math.round(item.optDouble("duration", 0) * 1000);
            if (dur > 0 && durationMs > 0) {
                long diff = Math.abs(dur - durationMs);
                score += diff <= 3000 ? 4 : diff <= 8000 ? 2 : diff > 15000 ? -2 : 0;
            }
            if (score > bestScore) {
                bestScore = score;
                lyricsUrl = url;
                bestDur = dur;
            }
        }
        if (lyricsUrl == null || bestScore < 3) return null;

        String ttml = httpUrl(lyricsUrl, 20000);
        if (ttml == null) return null;
        String lrc = BiniLyricsParser.toEnhancedLrc(ttml);
        return lrc == null ? null : new Found(lrc, null, false, bestDur);
    }

    /**
     * Kugou lookup. Resolve a track hash first, then ask Kugou for matching lyric candidates.
     * Candidate title, artist and duration are checked before any lyric is accepted. KRC is
     * preferred because it contains word timings; LRC is the fallback when KRC cannot be decoded.
     */
    @Nullable
    private static Found fetchKugou(String title, String artist, long durationMs, boolean relaxed)
            throws IOException, JSONException {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return null;

        String wantedArtist = isUnknown(artist) ? "" : artist.trim();

        // The tested song-search endpoint returns the hash needed by Kugou's lyric catalogue.
        // Try several query orders because the search ranking changes with query composition.
        String[] queries = wantedArtist.isEmpty()
                ? new String[]{t}
                : new String[]{t + " " + wantedArtist, wantedArtist + " " + t, t};
        Set<String> seenHashes = new HashSet<>();
        List<JSONObject> songMatches = new ArrayList<>();
        List<Long> songScores = new ArrayList<>();

        for (String query : queries) {
            String songSearchUrl = "https://mobileservice.kugou.com/api/v3/search/song?keyword="
                    + enc(query) + "&page=1&pagesize=20&showtype=1";
            String songSearchBody = httpUrl(songSearchUrl, 20000);
            if (songSearchBody == null) continue;

            JSONObject songSearch = parseJsonObject(songSearchBody);
            JSONObject data = songSearch.optJSONObject("data");
            JSONArray info = data == null ? null : data.optJSONArray("info");
            if (info == null) continue;

            for (int i = 0; i < info.length(); i++) {
                JSONObject item = info.optJSONObject(i);
                if (item == null) continue;
                String hash = item.optString("hash", "");
                if (hash.isEmpty() || !seenHashes.add(hash)) continue;

                String songTitle = item.optString("songname", "");
                if (songTitle.isEmpty()) songTitle = item.optString("songname_original", "");
                String songArtist = item.optString("singername", "");
                if (songArtist.isEmpty()) {
                    String filename = item.optString("filename", "");
                    int dash = filename.indexOf(" - ");
                    if (dash >= 0) songArtist = filename.substring(0, dash).trim();
                }

                int ts = titleScore(songTitle, t);
                int as = artistScore(songArtist, wantedArtist);
                long songDuration = durationToMs(item.optLong("duration", 0));
                long score = trackScore(ts, as, songDuration, durationMs, relaxed, wantedArtist.isEmpty());
                if (score == Long.MIN_VALUE) continue;

                // Keep this list ranked; an irrelevant remix must not hide a later exact result.
                int insertAt = songScores.size();
                while (insertAt > 0 && songScores.get(insertAt - 1) < score) insertAt--;
                songScores.add(insertAt, score);
                songMatches.add(insertAt, item);
            }
        }

        // A few best song hashes are enough; every lyric candidate is independently validated.
        for (int i = 0; i < Math.min(songMatches.size(), 8); i++) {
            JSONObject song = songMatches.get(i);
            String hash = song.optString("hash", "");
            long songDuration = durationToMs(song.optLong("duration", 0));
            String keyword = t + (wantedArtist.isEmpty() ? "" : " - " + wantedArtist);
            String lyricSearchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword="
                    + enc(keyword) + "&duration=" + Math.max(0, songDuration > 0 ? songDuration : durationMs)
                    + "&hash=" + enc(hash);
            Found found = fetchKugouLyricsFromSearch(lyricSearchUrl, t, wantedArtist,
                    durationMs, relaxed);
            if (found != null) return found;
        }

        return null;
    }

    /** Selects a matching lyric candidate and tries KRC (word timing) before ordinary LRC. */
    @Nullable
    private static Found fetchKugouLyricsFromSearch(String searchUrl, String title, String artist,
                                                     long durationMs, boolean relaxed)
            throws IOException, JSONException {
        String searchBody = httpUrl(searchUrl, 20000);
        if (searchBody == null) return null;

        JSONObject search = parseJsonObject(searchBody);
        JSONArray candidates = search.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return null;

        List<JSONObject> ranked = new ArrayList<>();
        List<Long> scores = new ArrayList<>();
        for (int i = 0; i < candidates.length(); i++) {
            JSONObject item = candidates.optJSONObject(i);
            if (item == null) continue;
            if (item.optString("id", "").isEmpty() || item.optString("accesskey", "").isEmpty()) continue;

            int ts = titleScore(item.optString("song", ""), title);
            int as = artistScore(item.optString("singer", ""), artist);
            long candidateDuration = item.optLong("duration", 0); // Kugou lyric duration is ms.
            long score = trackScore(ts, as, candidateDuration, durationMs, relaxed, isUnknown(artist));
            if (score == Long.MIN_VALUE) continue;

            int insertAt = scores.size();
            while (insertAt > 0 && scores.get(insertAt - 1) < score) insertAt--;
            scores.add(insertAt, score);
            ranked.add(insertAt, item);
        }

        for (JSONObject candidate : ranked) {
            String id = candidate.optString("id", "");
            String accessKey = candidate.optString("accesskey", "");
            long lyricDuration = candidate.optLong("duration", 0);

            // KRC payload is Base64(krc1 + XOR-encrypted zlib stream).
            String krcBody = downloadKugouPayload(id, accessKey, "krc");
            if (krcBody != null) {
                JSONObject payload = parseJsonObject(krcBody);
                String krc = decodeKugouKrc(payload.optString("content", ""));
                String enhancedLrc = krcToEnhancedLrc(krc);
                if (hasTimedLyrics(enhancedLrc)) {
                    return new Found(enhancedLrc, null, false, lyricDuration);
                }
            }

            // LRC is less detailed but is often available even when the KRC candidate is not.
            String lrcBody = downloadKugouPayload(id, accessKey, "lrc");
            if (lrcBody != null) {
                JSONObject payload = parseJsonObject(lrcBody);
                String lrc = decodeBase64Text(payload.optString("content", ""));
                lrc = decodeHtmlEntities(lrc);
                if (hasTimedLyrics(lrc)) return new Found(lrc, null, false, lyricDuration);
            }
        }
        return null;
    }

    @Nullable
    private static String downloadKugouPayload(String id, String accessKey, String format) {
        try {
            String url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=" + enc(id)
                    + "&accesskey=" + enc(accessKey) + "&fmt=" + enc(format) + "&charset=utf8";
            String body = httpUrl(url, 20000);
            if (body == null) return null;
            JSONObject response = parseJsonObject(body);
            if (response.optInt("status", 200) != 200 || response.optInt("error_code", 0) != 0
                    || !notEmpty(response.optString("content", ""))) return null;
            return body;
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    /** Decodes the Kugou KRC binary payload. The first four bytes are "krc1". */
    @Nullable
    private static String decodeKugouKrc(String base64) {
        if (!notEmpty(base64)) return null;
        try {
            byte[] file = Base64.decode(base64, Base64.DEFAULT);
            if (file.length < 8 || file[0] != 'k' || file[1] != 'r' || file[2] != 'c' || file[3] != '1') {
                return null;
            }

            // Kugou applies a fixed 16-byte XOR key, then wraps the result in a zlib stream.
            final byte[] key = new byte[]{
                    0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
                    0x51, 0x36, 0x31, 0x2d, (byte) 0xce, (byte) 0xd2, 0x6e, 0x69
            };
            byte[] encrypted = new byte[file.length - 4];
            for (int i = 4; i < file.length; i++) encrypted[i - 4] = (byte) (file[i] ^ key[(i - 4) % key.length]);

            try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(encrypted));
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int total = 0;
                int n;
                while ((n = inflater.read(buffer)) != -1) {
                    total += n;
                    if (total > 8 * 1024 * 1024) return null;
                    output.write(buffer, 0, n);
                }
                return output.toString("UTF-8");
            }
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    /** Converts KRC line/word offsets into enhanced-LRC absolute word stamps understood by LrcParser. */
    @Nullable
    private static String krcToEnhancedLrc(@Nullable String krc) {
        if (!notEmpty(krc)) return null;
        Pattern linePattern = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
        Pattern wordPattern = Pattern.compile("<(\\d+),(\\d+)(?:,\\d+)?>");
        StringBuilder out = new StringBuilder(krc.length());

        for (String raw : krc.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher lineMatcher = linePattern.matcher(line);
            if (!lineMatcher.matches()) {
                // Preserve standard LRC metadata; LrcParser safely ignores other KRC tags.
                if (line.startsWith("[ti:") || line.startsWith("[ar:")
                        || line.startsWith("[al:") || line.startsWith("[by:")
                        || line.startsWith("[offset:")) out.append(line).append('\n');
                continue;
            }

            long lineStart = parseLongSafe(lineMatcher.group(1));
            String content = lineMatcher.group(3);
            Matcher matcher = wordPattern.matcher(content);
            List<Long> starts = new ArrayList<>();
            List<Long> durations = new ArrayList<>();
            List<String> segments = new ArrayList<>();
            int previousEnd = 0;
            int firstMarkerStart = -1;

            while (matcher.find()) {
                if (firstMarkerStart < 0) {
                    firstMarkerStart = matcher.start();
                } else {
                    segments.add(content.substring(previousEnd, matcher.start()));
                }
                starts.add(parseLongSafe(matcher.group(1)));
                durations.add(parseLongSafe(matcher.group(2)));
                previousEnd = matcher.end();
            }

            if (starts.isEmpty()) {
                if (!content.isEmpty()) out.append('[').append(formatLrcTime(lineStart)).append(']')
                        .append(content).append('\n');
                continue;
            }

            segments.add(content.substring(previousEnd));
            if (segments.size() == starts.size() && firstMarkerStart > 0) {
                segments.set(0, content.substring(0, firstMarkerStart) + segments.get(0));
            }

            StringBuilder enhanced = new StringBuilder();
            enhanced.append('[').append(formatLrcTime(lineStart)).append(']');
            boolean wroteWord = false;
            for (int i = 0; i < starts.size() && i < segments.size(); i++) {
                String word = segments.get(i);
                if (word.isEmpty()) continue;
                long wordStart = lineStart + starts.get(i);
                long wordEnd = wordStart + Math.max(1, durations.get(i));
                // Explicit end markers preserve KRC's per-word duration and any pauses between words.
                enhanced.append('<').append(formatLrcTime(wordStart)).append('>')
                        .append(word)
                        .append('<').append(formatLrcTime(wordEnd)).append('>');
                wroteWord = true;
            }

            if (wroteWord) out.append(enhanced).append('\n');
            else if (!content.isEmpty()) out.append('[').append(formatLrcTime(lineStart)).append(']')
                    .append(content).append('\n');
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static long parseLongSafe(String value) {
        try { return Long.parseLong(value); }
        catch (RuntimeException ignored) { return 0; }
    }

    private static String formatLrcTime(long timeMs) {
        long safe = Math.max(0, timeMs);
        long minutes = safe / 60000L;
        long seconds = (safe / 1000L) % 60L;
        long milliseconds = safe % 1000L;
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, milliseconds);
    }

    private static long durationToMs(long value) {
        if (value <= 0) return 0;
        return value < 10000 ? value * 1000L : value;
    }

    /** QQ Music search and its public LRC endpoint. */
    @Nullable
    private static Found fetchQqMusic(String title, String artist, long durationMs, boolean relaxed)
            throws IOException, JSONException {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return null;

        String[] queries = isUnknown(artist)
                ? new String[]{t}
                : new String[]{t + " " + artist.trim(), artist.trim() + " " + t};
        Set<String> seen = new HashSet<>();
        JSONObject best = null;
        long bestScore = Long.MIN_VALUE;

        for (String query : queries) {
            // This is the same public search route and parameter shape tested by lyrics_probe.py.
            String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" + enc(query)
                    + "&format=json&n=20&p=1";
            String searchBody = httpUrl(searchUrl, 20000, "https://y.qq.com/");
            if (searchBody == null) continue;

            JSONObject search = parseJsonObject(searchBody);
            JSONObject data = search.optJSONObject("data");
            JSONObject songData = data == null ? null : data.optJSONObject("song");
            JSONArray songs = songData == null ? null : songData.optJSONArray("list");
            if (songs == null) continue;

            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.optJSONObject(i);
                if (item == null) continue;
                String mid = item.optString("songmid", "");
                if (mid.isEmpty() || !seen.add(mid)) continue;

                int ts = titleScore(item.optString("songname", ""), t);
                int as = qqArtistScore(item.optJSONArray("singer"), artist);
                long candidateDuration = item.optLong("interval", 0) * 1000L;
                long score = trackScore(ts, as, candidateDuration, durationMs, relaxed, isUnknown(artist));
                if (score > bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
        }
        if (best == null) return null;

        String lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="
                + enc(best.optString("songmid", "")) + "&format=json&nobase64=1";
        String lyricBody = httpUrl(lyricUrl, 20000, "https://y.qq.com/");
        if (lyricBody == null) return null;

        JSONObject response = parseJsonObject(lyricBody);
        if (response.optInt("retcode", 0) != 0 || response.optInt("code", 0) != 0
                || response.optInt("subcode", 0) != 0) return null;
        String lrc = decodeBase64Text(response.optString("lyric", ""));
        lrc = decodeHtmlEntities(lrc);
        if (!hasTimedLyrics(lrc)) return null;
        return new Found(lrc, null, false, best.optLong("interval", 0) * 1000L);
    }

    /** NetEase Cloud Music search and LRC endpoint. */
    @Nullable
    private static Found fetchNetease(String title, String artist, long durationMs, boolean relaxed)
            throws IOException, JSONException {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) return null;

        String[] queries = isUnknown(artist)
                ? new String[]{t}
                : new String[]{t + " " + artist.trim(), artist.trim() + " " + t};
        Set<Long> seen = new HashSet<>();
        JSONObject best = null;
        long bestScore = Long.MIN_VALUE;

        for (String query : queries) {
            // Match the known-working endpoint used by the supplied probe.
            String searchUrl = "https://music.163.com/api/search/get?s=" + enc(query)
                    + "&type=1&limit=30&offset=0";
            String searchBody = httpUrl(searchUrl, 20000, "https://music.163.com/");
            if (searchBody == null) continue;

            JSONObject search = parseJsonObject(searchBody);
            JSONObject result = search.optJSONObject("result");
            JSONArray songs = result == null ? null : result.optJSONArray("songs");
            if (songs == null) continue;

            for (int i = 0; i < songs.length(); i++) {
                JSONObject item = songs.optJSONObject(i);
                if (item == null) continue;
                long songId = item.optLong("id", 0);
                if (songId <= 0 || !seen.add(songId)) continue;

                int ts = titleScore(item.optString("name", ""), t);
                int as = neteaseArtistScore(item.optJSONArray("artists"), artist);
                long candidateDuration = item.optLong("duration", 0);
                long score = trackScore(ts, as, candidateDuration, durationMs, relaxed, isUnknown(artist));
                if (score > bestScore) {
                    bestScore = score;
                    best = item;
                }
            }
        }
        if (best == null) return null;

        String lyricUrl = "https://music.163.com/api/song/lyric?id="
                + best.optLong("id") + "&lv=1&kv=1&tv=-1&yv=1";
        String lyricBody = httpUrl(lyricUrl, 20000, "https://music.163.com/");
        if (lyricBody == null) return null;
        JSONObject response = parseJsonObject(lyricBody);
        JSONObject lrcObject = response.optJSONObject("lrc");
        String lrc = lrcObject == null ? "" : lrcObject.optString("lyric", "");
        lrc = decodeHtmlEntities(lrc);
        if (!hasTimedLyrics(lrc)) return null;
        return new Found(lrc, null, false, best.optLong("duration", 0));
    }

    private static int titleScore(String actual, String wanted) {
        String a = titleForMatching(actual);
        String w = titleForMatching(wanted);
        if (a.isEmpty() || w.isEmpty()) return -1;
        // Do not treat "Faded (Remix)" or "Numb Live" as an exact match for the original.
        return a.equals(w) ? 5 : -1;
    }

    private static String titleForMatching(@Nullable String value) {
        if (value == null) return "";
        String title = value.trim();
        title = title.replaceFirst("(?i)\\s*[\\[(]?\\s*(feat\\.?|ft\\.?|featuring)\\s+.*?[\\])]?$", "");
        title = title.replaceFirst("(?i)\\s*[\\[(]\\s*(official\\s+(audio|video)|lyrics?(\\s+video)?|visualizer|lyric video)\\s*[\\])]\\s*$", "");
        return norm(title);
    }

    private static int artistScore(String actual, String wanted) {
        String a = norm(actual);
        String w = norm(wanted);
        if (w.isEmpty()) return 0;
        if (a.isEmpty()) return -1;
        if (a.equals(w)) return 4;

        String[] actualParts = ARTIST_SPLIT.split(actual == null ? "" : actual);
        String[] wantedParts = ARTIST_SPLIT.split(wanted == null ? "" : wanted);
        int wantedCount = 0;
        int matched = 0;
        for (String wantedPart : wantedParts) {
            String wp = norm(wantedPart);
            if (wp.isEmpty()) continue;
            wantedCount++;
            for (String actualPart : actualParts) {
                if (wp.equals(norm(actualPart))) {
                    matched++;
                    break;
                }
            }
        }
        if (wantedCount > 0 && matched == wantedCount) return 4;
        return matched > 0 ? 2 : -1;
    }

    private static long trackScore(int titleScore, int artistScore, long candidateDuration,
                                   long wantedDuration, boolean relaxed, boolean artistUnknown) {
        if (titleScore < 0 || (!artistUnknown && artistScore < 0)) return Long.MIN_VALUE;
        long score = titleScore * 100000L + (artistUnknown ? 0 : Math.max(artistScore, 0) * 20000L);
        if (candidateDuration > 0 && wantedDuration > 0) {
            long diff = Math.abs(candidateDuration - wantedDuration);
            if (!relaxed && diff > MAX_DURATION_DIFF_MS) return Long.MIN_VALUE;
            score -= Math.min(diff, 300000L) * (relaxed ? 1L : 2L);
        } else {
            score -= 1000L;
        }
        return score;
    }

    private static int qqArtistScore(@Nullable JSONArray artists, String wanted) {
        if (isUnknown(wanted)) return 0;
        if (artists == null || artists.length() == 0) return -1;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            if (names.length() > 0) names.append(" & ");
            names.append(artist.optString("name", ""));
        }
        return artistScore(names.toString(), wanted);
    }

    private static int neteaseArtistScore(@Nullable JSONArray artists, String wanted) {
        if (wanted.isEmpty()) return 0;
        if (artists == null || artists.length() == 0) return -1;
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < artists.length(); i++) {
            JSONObject artist = artists.optJSONObject(i);
            if (artist == null) continue;
            if (names.length() > 0) names.append(" & ");
            names.append(artist.optString("name", ""));
        }
        return artistScore(names.toString(), wanted);
    }

    private static boolean hasTimedLyrics(@Nullable String lrc) {
        if (!notEmpty(lrc)) return false;
        return STAMP.matcher(lrc).find();
    }

    private static JSONObject parseJsonObject(String body) throws JSONException {
        String value = body == null ? "" : body.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) value = value.substring(start, end + 1);
        return new JSONObject(value);
    }

    private static String decodeBase64Text(String value) {
        if (!notEmpty(value)) return "";
        String text = value.trim();
        if (text.startsWith("[") || text.startsWith("<?xml") || text.startsWith("<Qrc")) return text;
        try {
            byte[] decoded = Base64.decode(text, Base64.DEFAULT);
            String result = new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
            return result.indexOf('\u0000') >= 0 ? text : result;
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    private static String decodeHtmlEntities(String value) {
        if (value == null || value.indexOf('&') < 0) return value == null ? "" : value;
        String decoded = value.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
        Matcher m = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(decoded);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            try {
                String code = m.group(1);
                int cp = code.charAt(0) == 'x' || code.charAt(0) == 'X'
                        ? Integer.parseInt(code.substring(1), 16) : Integer.parseInt(code);
                m.appendReplacement(out, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (RuntimeException e) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Letters and digits only, lower case, "(Slowed)"-style tags kept (they are part of the title). */
    private static String norm(@Nullable String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    @Nullable
    private static JSONObject getOne(String t, String a, String al, long secs)
            throws IOException, JSONException {
        String q = "get?track_name=" + enc(t) + "&artist_name=" + enc(a)
                + (al.isEmpty() ? "" : "&album_name=" + enc(al))
                + "&duration=" + secs;
        String body = http(q);
        return body == null ? null : new JSONObject(body);
    }

    private static JSONArray search(String query) throws IOException, JSONException {
        String body = http("search?" + query);
        return body == null ? new JSONArray() : new JSONArray(body);
    }

    @Nullable
    private static Found pickBest(JSONArray results, long durationMs, int mode) {
        JSONObject best = null;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            boolean synced = notEmpty(str(o, "syncedLyrics"));
            boolean plain = notEmpty(str(o, "plainLyrics"));
            if (!synced && !plain) continue;
            long diff = Math.abs(Math.round(o.optDouble("duration", 0) * 1000) - durationMs);
            long score;
            if (mode == STRICT) {
                if (diff > MAX_DURATION_DIFF_MS) continue;
                score = diff + (synced ? 0 : 3000);   // prefer synced unless it's clearly worse
            } else if (mode == CLOSEST) {              // remixes etc.: any length, closest wins
                score = (synced ? 0 : 1_000_000L) + diff;
            } else {                                   // slowed / sped up: length says nothing
                score = (synced ? 0 : 1_000_000L) + i;
            }
            if (score < bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return fromRecord(best);
    }

    @Nullable
    private static Found fromRecord(@Nullable JSONObject o) {
        if (o == null) return null;
        if (o.optBoolean("instrumental", false)) return new Found(null, null, true);
        String synced = str(o, "syncedLyrics");
        String plain = str(o, "plainLyrics");
        if (!notEmpty(synced) && !notEmpty(plain)) return null;
        return new Found(notEmpty(synced) ? synced : null, notEmpty(plain) ? plain : null, false,
                Math.round(o.optDouble("duration", 0) * 1000));
    }

    // ---------- plumbing ----------

    /** org.json's optString turns JSON null into the text "null", so check first. */
    @Nullable
    private static String str(JSONObject o, String key) {
        return o.isNull(key) ? null : o.optString(key, null);
    }

    private static boolean notEmpty(@Nullable String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static boolean isUnknown(@Nullable String s) {
        if (s == null) return true;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() || t.contains("unknown");
    }

    private static String enc(String s) throws IOException {
        return URLEncoder.encode(s, "UTF-8");
    }

    /** GET; returns the body, or null on 404. Throws on rate limits / server errors / no network. */
    @Nullable
    private static String http(String pathAndQuery) throws IOException {
        return httpUrl(BASE + pathAndQuery, 12000);
    }

    @Nullable
    private static String httpUrl(String url, int readTimeoutMs) throws IOException {
        return httpUrl(url, readTimeoutMs, null);
    }

    @Nullable
    private static String httpUrl(String url, int readTimeoutMs, @Nullable String referer) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(readTimeoutMs);
            boolean lrclibRequest = url.startsWith(BASE);
            if (lrclibRequest) {
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Lrclib-Client", USER_AGENT);
                conn.setRequestProperty("Accept", "application/json");
            } else {
                // The other public endpoints were tested with a standard Android browser UA.
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36");
                conn.setRequestProperty("Accept", "*/*");
            }
            if (referer != null) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            conn.disconnect();
        }
    }
}
