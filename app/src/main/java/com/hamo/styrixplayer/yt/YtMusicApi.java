package com.hamo.styrixplayer.yt;

import android.content.Context;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Minimal client for the YouTube Music web API (the same "InnerTube" calls music.youtube.com makes).
 * It only reads: home, liked songs, library playlists, playlist contents and search.
 * Everything is blocking; call it from a background thread.
 */
public final class YtMusicApi {

    private static final String BASE = "https://music.youtube.com/youtubei/v1/";
    private static final String SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF";
    private static final int MAX_PAGES = 60;
    private static final int STATION_PAGES = 4;
    private static final String RADIO_PREFIX = "radio:";
    private static final Pattern DURATION = Pattern.compile("^\\d{1,2}(:\\d{2}){1,2}$");
    private static final Pattern THUMB_SIZE = Pattern.compile("=w\\d+-h\\d+");

    private YtMusicApi() {}

    // ---------- public calls ----------

    public static List<YtItem> home(Context c) throws IOException {
        return dedupe(parseAll(browse(c, "FEmusic_home", null)));
    }

    public static List<YtItem> liked(Context c) throws IOException {
        return paged(c, "FEmusic_liked_videos");
    }

    public static List<YtItem> libraryPlaylists(Context c) throws IOException {
        List<YtItem> all = parseAll(browse(c, "FEmusic_liked_playlists", null));
        List<YtItem> out = new ArrayList<>();
        for (YtItem i : all) if (!i.isSong()) out.add(i);
        return dedupe(out);
    }

    public static List<YtItem> playlist(Context c, String browseId) throws IOException {
        if (browseId.startsWith(RADIO_PREFIX)) {
            String[] parts = browseId.substring(RADIO_PREFIX.length()).split("\\|", -1);
            return station(c, parts[0], parts.length > 1 ? parts[1] : null);
        }
        return paged(c, normaliseBrowseId(browseId));
    }

    public static List<YtItem> search(Context c, String query) throws IOException {
        try {
            JSONObject body = baseBody();
            body.put("query", query);
            body.put("params", SONGS_FILTER);
            JSONObject res = post(c, "search", body, null);
            List<YtItem> songs = new ArrayList<>();
            for (YtItem i : parseAll(res)) if (i.isSong()) songs.add(i);
            return dedupe(songs);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * Search with sections: a few artists, albums and playlists first (from the general search),
     * then the full list of songs. If the general search fails, the songs are still returned.
     */
    public static List<YtItem> searchAll(Context c, String query) throws IOException {
        List<YtItem> songs = search(c, query);
        List<YtItem> others = new ArrayList<>();
        try {
            JSONObject body = baseBody();
            body.put("query", query);
            JSONObject res = post(c, "search", body, null);
            List<Object[]> shelves = new ArrayList<>();
            findShelves(res, shelves);
            String[] wanted = {"artist", "album", "playlist"};
            String[] labels = {"Artists", "Albums", "Playlists"};
            for (int w = 0; w < wanted.length; w++) {
                List<YtItem> part = new ArrayList<>();
                for (Object[] sh : shelves) {
                    String t = ((String) sh[0]).toLowerCase(java.util.Locale.ROOT);
                    if (!t.contains(wanted[w])) continue;
                    if (w == 0) {
                        part.addAll(parseArtists((JSONObject) sh[1]));
                        continue;
                    }
                    for (YtItem i : parseAll((JSONObject) sh[1])) if (!i.isSong() && part.size() < 3) part.add(i);
                }
                if (!part.isEmpty()) {
                    others.add(YtItem.header(labels[w]));
                    others.addAll(part);
                }
            }
        } catch (Exception ignored) {
            // sections are a bonus; songs alone are fine
        }
        if (others.isEmpty()) return songs;
        List<YtItem> out = new ArrayList<>(others);
        if (!songs.isEmpty()) {
            out.add(YtItem.header("Songs"));
            out.addAll(songs);
        }
        return out;
    }

    /** Artist rows of a search shelf (the normal list parser skips them: they are not playlists). */
    private static List<YtItem> parseArtists(JSONObject shelf) {
        List<YtItem> out = new ArrayList<>();
        JSONArray rows = shelf.optJSONArray("contents");
        if (rows == null) return out;
        String col0 = "musicResponsiveListItemFlexColumnRenderer";
        for (int i = 0; i < rows.length() && out.size() < 3; i++) {
            JSONObject r = rows.optJSONObject(i) == null ? null
                    : rows.optJSONObject(i).optJSONObject("musicResponsiveListItemRenderer");
            if (r == null) continue;
            String browseId = s(r, "navigationEndpoint", "browseEndpoint", "browseId");
            String name = s(r, "flexColumns", 0, col0, "text", "runs", 0, "text");
            if (browseId == null || !browseId.startsWith("UC") || name == null || name.isEmpty()) continue;
            String thumb = bestThumb(p(r, "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"));
            out.add(YtItem.folder(browseId, name, "Artist", thumb));
        }
        return out;
    }

    private static void findShelves(@Nullable Object node, List<Object[]> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject shelf = o.optJSONObject("musicShelfRenderer");
            if (shelf != null) {
                String title = s(shelf, "title", "runs", 0, "text");
                out.add(new Object[]{title == null ? "" : title, shelf});
                return;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) findShelves(o.opt(it.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) findShelves(arr.opt(i), out);
        }
    }

    /** {name, photoUrl} of the signed-in Google account (either may be empty). */
    public static String[] accountInfo(Context c) throws IOException {
        try {
            JSONObject res = post(c, "account/account_menu", baseBody(), null);
            Object hdr = p(res, "actions", 0, "openPopupAction", "popup", "multiPageMenuRenderer",
                    "header", "activeAccountHeaderRenderer");
            String name = s(hdr, "accountName", "runs", 0, "text");
            Object th = p(hdr, "accountPhoto", "thumbnails");
            String photo = null;
            if (th instanceof JSONArray && ((JSONArray) th).length() > 0) {
                JSONArray a = (JSONArray) th;
                photo = s(a.opt(a.length() - 1), "url");
            }
            if (photo != null) photo = photo.replaceFirst("=s\\d+", "=s192");
            return new String[]{name == null ? "" : name, photo == null ? "" : photo};
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Likes or un-likes a song on the signed-in account. */
    public static void setLiked(Context c, String videoId, boolean liked) throws IOException {
        if (!YtAuth.isSignedIn(c)) throw new YouTubeResolver.YtException("Sign in to like songs");
        try {
            JSONObject body = baseBody();
            body.put("target", new JSONObject().put("videoId", videoId));
            post(c, liked ? "like/like" : "like/removelike", body, null);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    // ---------- requests ----------

    private static List<YtItem> paged(Context c, String browseId) throws IOException {
        List<YtItem> out = new ArrayList<>();
        JSONObject res = browse(c, browseId, null);
        // album pages leave the artist out of every row: it is only in the header
        String headArtist = browseId.startsWith("MPRE") ? headerArtist(res) : null;
        String lastToken = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<YtItem> got = parseAll(res);
            out.addAll(got);
            String token = findToken(res);
            // no token, the same token twice, or an empty page: the list has ended
            if (token == null || token.equals(lastToken) || (page > 0 && got.isEmpty())) break;
            lastToken = token;
            res = browse(c, null, token);
        }
        out = dedupe(out);
        if (headArtist != null && !headArtist.isEmpty()) {
            for (int i = 0; i < out.size(); i++) {
                YtItem it = out.get(i);
                if (it.isSong() && it.track.artist.trim().isEmpty()) {
                    YtTrack t = it.track;
                    out.set(i, YtItem.song(new YtTrack(t.videoId, t.title, headArtist, t.album,
                            t.durationMs, t.thumbUrl)));
                }
            }
        }
        return out;
    }

    /** The artist(s) named in an album page's header, or null. */
    @Nullable
    private static String headerArtist(Object o) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            for (String key : new String[]{"musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer"}) {
                JSONObject h = j.optJSONObject(key);
                if (h == null) continue;
                Meta m = new Meta();
                m.read(p(h, "straplineTextOne", "runs"));
                if (m.artists.isEmpty()) m.read(p(h, "subtitle", "runs"));
                if (!m.artists.isEmpty()) return join(m.artists);
            }
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String r = headerArtist(j.opt(it.next()));
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String r = headerArtist(a.opt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * A radio / station: the songs YouTube Music queues up for a seed song or mix. The list is
     * endless on YouTube's side, so it stops after a few pages.
     */
    private static List<YtItem> station(Context c, String playlistId, @Nullable String videoId)
            throws IOException {
        try {
            JSONObject body = baseBody();
            body.put("playlistId", playlistId);
            if (videoId != null && !videoId.isEmpty()) body.put("videoId", videoId);
            body.put("params", "wAEB");
            body.put("isAudioOnly", true);
            body.put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL");
            JSONObject res = post(c, "next", body, null);
            List<YtItem> out = new ArrayList<>();
            String lastToken = null;
            for (int page = 0; page < STATION_PAGES; page++) {
                List<YtItem> got = parseAll(res);
                out.addAll(got);
                String token = findToken(res);
                if (token == null || token.equals(lastToken) || got.isEmpty()) break;
                lastToken = token;
                res = post(c, "next", body, token);
            }
            return dedupe(out);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, String> videoCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Already looked up: the music-video id, "" when YouTube has none, null when not asked yet. */
    @Nullable
    public static String peekVideo(String videoId) {
        return videoCache.get(videoId);
    }

    private static android.content.SharedPreferences videoStore(Context c) {
        return c.getApplicationContext().getSharedPreferences("video_ids", Context.MODE_PRIVATE);
    }

    /** Like {@link #peekVideo(String)}, but also remembers answers from earlier runs of the app. */
    @Nullable
    public static String peekVideo(Context c, String videoId) {
        String k = videoCache.get(videoId);
        if (k != null) return k;
        k = videoStore(c).getString(videoId, null);
        if (k != null) videoCache.put(videoId, k);
        return k;
    }

    /**
     * The id of the real music video for a song, or "" when YouTube Music has none. A song's own id
     * is usually the audio-only "art track"; YouTube lists the video as its "counterpart" (the
     * Song / Video switch in YouTube Music).
     */
    public static String musicVideoFor(Context c, String videoId) throws IOException {
        String known = videoCache.get(videoId);
        if (known != null) return known;
        String found = "";
        try {
            JSONObject body = baseBody();
            body.put("videoId", videoId);
            JSONObject res = post(c, "next", body, null);
            JSONObject node = findPanel(res, videoId);
            if (node != null) {
                if (isVideoType(typeOf(node.optJSONObject("navigationEndpoint")))) {
                    found = videoId;
                } else {
                    JSONArray cp = node.optJSONArray("counterpart");
                    for (int i = 0; cp != null && i < cp.length() && found.isEmpty(); i++) {
                        JSONObject r = cp.optJSONObject(i);
                        r = r == null ? null : r.optJSONObject("counterpartRenderer");
                        JSONObject pv = r == null ? null : r.optJSONObject("playlistPanelVideoRenderer");
                        if (pv == null) continue;
                        String id = pv.optString("videoId", "");
                        if (!id.isEmpty() && isVideoType(typeOf(pv.optJSONObject("navigationEndpoint")))) {
                            found = id;
                        }
                    }
                }
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        // no official video: play whatever YouTube itself plays for this id (art track, lyric video, upload)
        if (found.isEmpty()) found = videoId;
        videoCache.put(videoId, found);
        videoStore(c).edit().putString(videoId, found).apply();
        return found;
    }

    private static boolean isVideoType(@Nullable String t) {
        return "MUSIC_VIDEO_TYPE_OMV".equals(t) || "MUSIC_VIDEO_TYPE_UGC".equals(t);
    }

    /** First musicVideoType found inside a navigation endpoint. */
    @Nullable
    private static String typeOf(@Nullable Object o) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String t = j.optString("musicVideoType", "");
            if (!t.isEmpty()) return t;
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String r = typeOf(j.opt(it.next()));
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String r = typeOf(a.opt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The playlistPanelVideoRenderer of the song itself (not of its counterpart). */
    @Nullable
    private static JSONObject findPanel(@Nullable Object o, String videoId) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            JSONObject p = j.optJSONObject("playlistPanelVideoRenderer");
            if (p != null && videoId.equals(p.optString("videoId"))) return p;
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) {
                JSONObject r = findPanel(j.opt(it.next()), videoId);
                if (r != null) return r;
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                JSONObject r = findPanel(a.opt(i), videoId);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static JSONObject browse(Context c, @Nullable String browseId, @Nullable String token)
            throws IOException {
        try {
            JSONObject body = baseBody();
            if (browseId != null) body.put("browseId", browseId);
            return post(c, "browse", body, token);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static String clientVersion() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "1." + f.format(new Date(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)) + ".01.00";
    }

    private static JSONObject baseBody() throws JSONException {
        String gl = Locale.getDefault().getCountry();
        if (gl == null || gl.length() != 2) gl = "US";
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", clientVersion())
                .put("hl", "en")
                .put("gl", gl);
        return new JSONObject().put("context", new JSONObject().put("client", client));
    }

    private static JSONObject post(Context c, String endpoint, JSONObject body, @Nullable String token)
            throws IOException {
        String url = BASE + endpoint + "?prettyPrint=false";
        if (token != null) {
            String t = URLEncoder.encode(token, "UTF-8");
            url += "&ctoken=" + t + "&continuation=" + t;
        }
        String cookies = YtAuth.cookies(c);
        String auth = cookies == null ? null : YtAuth.authorization(cookies);

        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setRequestMethod("POST");
            h.setConnectTimeout(20_000);
            h.setReadTimeout(30_000);
            h.setDoOutput(true);
            h.setRequestProperty("User-Agent", NpDownloader.UA);
            h.setRequestProperty("Accept", "*/*");
            h.setRequestProperty("Accept-Encoding", "gzip");
            h.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            h.setRequestProperty("Content-Type", "application/json");
            h.setRequestProperty("Origin", YtAuth.ORIGIN);
            h.setRequestProperty("Referer", YtAuth.ORIGIN + "/");
            h.setRequestProperty("X-Goog-AuthUser", "0");
            h.setRequestProperty("X-Origin", YtAuth.ORIGIN);
            h.setRequestProperty("X-Youtube-Client-Name", "67");
            h.setRequestProperty("X-Youtube-Client-Version", clientVersion());
            String ck = cookies == null ? "" : cookies;
            if (!ck.contains("SOCS=")) ck = ck.isEmpty() ? "SOCS=CAI" : ck + "; SOCS=CAI";
            h.setRequestProperty("Cookie", ck);
            if (auth != null) h.setRequestProperty("Authorization", auth);

            try (OutputStream os = h.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = h.getResponseCode();
            InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
            String text = read(in, "gzip".equalsIgnoreCase(h.getContentEncoding()));
            if (code == 401 || code == 403) {
                throw new YouTubeResolver.YtException(auth != null
                        ? "YouTube rejected your session. Sign in again from Settings."
                        : "YouTube asked for a sign-in.");
            }
            if (code >= 400) throw new YouTubeResolver.YtException("YouTube Music error " + code);
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new YouTubeResolver.YtException("YouTube sent something unexpected");
        } finally {
            h.disconnect();
        }
    }

    private static String read(@Nullable InputStream in, boolean gzip) throws IOException {
        if (in == null) return "";
        if (gzip) in = new GZIPInputStream(in);
        try (InputStream is = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    // ---------- JSON helpers ----------

    @Nullable
    private static Object p(@Nullable Object o, Object... keys) {
        for (Object k : keys) {
            if (o == null) return null;
            if (k instanceof Integer) {
                if (!(o instanceof JSONArray)) return null;
                o = ((JSONArray) o).opt((Integer) k);
            } else {
                if (!(o instanceof JSONObject)) return null;
                o = ((JSONObject) o).opt((String) k);
            }
        }
        return o == JSONObject.NULL ? null : o;
    }

    @Nullable
    private static String s(@Nullable Object o, Object... keys) {
        Object v = p(o, keys);
        return v instanceof String ? (String) v : null;
    }

    /** Every song/list row anywhere in the response, in page order. */
    private static void collect(@Nullable Object node, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject a = o.optJSONObject("musicResponsiveListItemRenderer");
            if (a != null) {
                try { a.put("__kind", "list"); } catch (JSONException ignored) { }
                out.add(a);
                return;
            }
            JSONObject w = o.optJSONObject("playlistPanelVideoWrapperRenderer");
            if (w != null) {
                // a queue entry plus alternate versions of it; only the main one is wanted
                collect(w.opt("primaryRenderer"), out);
                return;
            }
            JSONObject q = o.optJSONObject("playlistPanelVideoRenderer");
            if (q != null) {
                try { q.put("__kind", "panel"); } catch (JSONException ignored) { }
                out.add(q);
                return;
            }
            JSONObject b = o.optJSONObject("musicTwoRowItemRenderer");
            if (b != null) {
                try { b.put("__kind", "two"); } catch (JSONException ignored) { }
                out.add(b);
                return;
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) collect(o.opt(it.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collect(arr.opt(i), out);
        }
    }

    @Nullable
    private static String findToken(@Nullable Object node) {
        List<String> all = new ArrayList<>();
        findTokens(node, all);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static void findTokens(@Nullable Object node, List<String> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String t = s(o, "nextContinuationData", "continuation");
            if (t == null) t = s(o, "nextRadioContinuationData", "continuation");
            if (t == null) t = s(o, "continuationCommand", "token");
            if (t != null) out.add(t);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) findTokens(o.opt(it.next()), out);
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) findTokens(arr.opt(i), out);
        }
    }

    // ---------- parsing ----------

    private static List<YtItem> parseAll(JSONObject response) {
        List<JSONObject> rows = new ArrayList<>();
        collect(response, rows);
        List<YtItem> out = new ArrayList<>();
        for (JSONObject r : rows) {
            String kind = r.optString("__kind");
            YtItem it = "two".equals(kind) ? parseTwoRow(r)
                    : "panel".equals(kind) ? parsePanelRow(r) : parseListRow(r);
            if (it != null) out.add(it);
        }
        return out;
    }

    private static List<YtItem> dedupe(List<YtItem> in) {
        Set<String> seen = new HashSet<>();
        List<YtItem> out = new ArrayList<>();
        for (YtItem i : in) {
            String key = i.isSong() ? "s:" + i.track.videoId : "b:" + i.browseId;
            if (seen.add(key)) out.add(i);
        }
        return out;
    }

    private static String normaliseBrowseId(String id) {
        if (id.startsWith("VL") || id.startsWith("MPRE") || id.startsWith("FE") || id.startsWith("UC")) return id;
        return "VL" + id;
    }

    @Nullable
    private static String bestThumb(@Nullable Object thumbs) {
        if (!(thumbs instanceof JSONArray) || ((JSONArray) thumbs).length() == 0) return null;
        JSONArray a = (JSONArray) thumbs;
        String url = s(a.opt(a.length() - 1), "url");
        if (url == null) return null;
        Matcher m = THUMB_SIZE.matcher(url);
        return m.find() ? m.replaceFirst("=w544-h544") : url;
    }

    private static String pageType(@Nullable Object run) {
        String t = s(run, "navigationEndpoint", "browseEndpoint", "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig", "pageType");
        return t == null ? "" : t;
    }

    private static long parseDuration(String text) {
        if (!DURATION.matcher(text).matches()) return 0;
        long total = 0;
        for (String part : text.split(":")) total = total * 60 + Long.parseLong(part);
        return total * 1000L;
    }

    /** Reads artist(s), album and duration out of a row of text runs like "Artist • Album • 3:45". */
    private static final class Meta {
        final List<String> artists = new ArrayList<>();
        String album = "";
        long durationMs;
        String firstPlain = "";

        void read(@Nullable Object runsObj) {
            if (!(runsObj instanceof JSONArray)) return;
            JSONArray runs = (JSONArray) runsObj;
            for (int i = 0; i < runs.length(); i++) {
                Object run = runs.opt(i);
                String text = s(run, "text");
                if (text == null) continue;
                String type = pageType(run);
                if (type.equals("MUSIC_PAGE_TYPE_ARTIST") || type.equals("MUSIC_PAGE_TYPE_USER_CHANNEL")) {
                    artists.add(text);
                } else if (type.equals("MUSIC_PAGE_TYPE_ALBUM")) {
                    album = text;
                } else if (DURATION.matcher(text).matches()) {
                    durationMs = parseDuration(text);
                } else {
                    String t = text.trim();
                    if (!t.isEmpty() && !t.equals("•") && !t.equals("Song") && !t.equals("Video")
                            && firstPlain.isEmpty()) firstPlain = t;
                }
            }
        }

        String artist() {
            if (!artists.isEmpty()) return join(artists);
            return firstPlain;
        }
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    @Nullable
    private static YtItem parseListRow(JSONObject r) {
        if (r.optString("musicItemRendererDisplayPolicy").contains("GREY_OUT")) return null;
        String col0 = "musicResponsiveListItemFlexColumnRenderer";
        String title = s(r, "flexColumns", 0, col0, "text", "runs", 0, "text");
        if (title == null || title.isEmpty()) return null;
        String thumb = bestThumb(p(r, "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"));

        String videoId = s(r, "playlistItemData", "videoId");
        if (videoId == null) {
            videoId = s(r, "overlay", "musicItemThumbnailOverlayRenderer", "content",
                    "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint", "videoId");
        }

        if (videoId == null) {
            // an album or playlist hit in a mixed list
            String browseId = s(r, "navigationEndpoint", "browseEndpoint", "browseId");
            if (browseId == null || browseId.startsWith("UC")) return null;
            Meta m = new Meta();
            m.read(p(r, "flexColumns", 1, col0, "text", "runs"));
            return YtItem.folder(browseId, title, m.firstPlain.isEmpty() ? "Playlist" : m.firstPlain, thumb);
        }

        Meta m = new Meta();
        m.read(p(r, "flexColumns", 1, col0, "text", "runs"));
        String album = m.album;
        Object c2 = p(r, "flexColumns", 2, col0, "text", "runs");
        if (c2 != null) {
            Meta m2 = new Meta();
            m2.read(c2);
            if (album.isEmpty()) album = m2.album.isEmpty() ? m2.firstPlain : m2.album;
        }
        long dur = m.durationMs;
        String fixed = s(r, "fixedColumns", 0, "musicResponsiveListItemFixedColumnRenderer",
                "text", "runs", 0, "text");
        if (fixed != null) dur = parseDuration(fixed.trim());
        if (thumb == null) thumb = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        return YtItem.song(new YtTrack(videoId, title, m.artist(), album, dur, thumb));
    }

    @Nullable
    private static YtItem parseTwoRow(JSONObject r) {
        String title = s(r, "title", "runs", 0, "text");
        if (title == null || title.isEmpty()) return null;
        String thumb = bestThumb(p(r, "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"));

        Object runs = p(r, "subtitle", "runs");
        StringBuilder sub = new StringBuilder();
        if (runs instanceof JSONArray) {
            JSONArray a = (JSONArray) runs;
            for (int i = 0; i < a.length(); i++) {
                String t = s(a.opt(i), "text");
                if (t != null) sub.append(t);
            }
        }

        String videoId = s(r, "navigationEndpoint", "watchEndpoint", "videoId");
        // stations and radios (watch endpoint with a radio playlist) open as a list of songs
        String radioId = s(r, "navigationEndpoint", "watchPlaylistEndpoint", "playlistId");
        if (radioId == null) {
            String pl = s(r, "navigationEndpoint", "watchEndpoint", "playlistId");
            if (pl != null && pl.startsWith("RD")) radioId = pl;
        }
        if (radioId != null && radioId.startsWith("RD")) {
            return YtItem.folder(RADIO_PREFIX + radioId + "|" + (videoId == null ? "" : videoId),
                    title, sub.length() == 0 ? "Station" : sub.toString(), thumb);
        }
        if (videoId != null) {
            Meta m = new Meta();
            m.read(runs);
            String artist = m.artists.isEmpty() ? sub.toString() : join(m.artists);
            if (thumb == null) thumb = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            return YtItem.song(new YtTrack(videoId, title, artist, m.album, 0, thumb));
        }
        String browseId = s(r, "navigationEndpoint", "browseEndpoint", "browseId");
        if (browseId == null || browseId.startsWith("UC")) return null;
        return YtItem.folder(browseId, title, sub.toString(), thumb);
    }

    /** One entry of a radio queue (the "next" endpoint). */
    @Nullable
    private static YtItem parsePanelRow(JSONObject r) {
        String videoId = r.optString("videoId", "");
        String title = s(r, "title", "runs", 0, "text");
        if (videoId.isEmpty() || title == null || title.isEmpty()) return null;
        Meta m = new Meta();
        m.read(p(r, "longBylineText", "runs"));
        long dur = m.durationMs;
        String len = s(r, "lengthText", "runs", 0, "text");
        if (len == null) len = s(r, "lengthText", "simpleText");
        if (len != null) {
            long d = parseDuration(len.trim());
            if (d > 0) dur = d;
        }
        String thumb = bestThumb(p(r, "thumbnail", "thumbnails"));
        if (thumb == null) thumb = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        return YtItem.song(new YtTrack(videoId, title, m.artist(), m.album, dur, thumb));
    }
}
