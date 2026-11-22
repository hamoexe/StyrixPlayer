package com.hamo.styrixplayer.lyrics;

import android.content.Context;

import androidx.annotation.Nullable;

import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Song;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Lrclib-Client", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json");
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
