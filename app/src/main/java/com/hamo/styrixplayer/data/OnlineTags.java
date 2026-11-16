package com.hamo.styrixplayer.data;

import androidx.annotation.Nullable;

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
import java.util.List;

/** Looks songs up in the iTunes catalogue (free, no key) to fill in tags and a cover. Off the main thread. */
public final class OnlineTags {

    public static final class Hit {
        public String title = "", artist = "", album = "", albumArtist = "", year = "", genre = "";
        public int track, trackCount;
        @Nullable public String artworkUrl;

        public String label() {
            String s = title + " — " + artist;
            if (!album.isEmpty()) s += "\n" + album + (year.isEmpty() ? "" : " (" + year + ")");
            return s;
        }

        public String trackText() {
            if (track <= 0) return "";
            return trackCount > 0 ? track + "/" + trackCount : String.valueOf(track);
        }
    }

    private static final String UA = "StyrixPlayer/1.0 (https://github.com/hamoexe)";

    private OnlineTags() {}

    /** iTunes first (has year / genre / track), then Deezer (finds more indie songs); duplicates dropped. */
    public static List<Hit> search(String term) throws IOException, JSONException {
        List<Hit> out = new ArrayList<>();
        IOException failure = null;
        try {
            out.addAll(searchItunes(term));
        } catch (IOException | JSONException e) {
            failure = e instanceof IOException ? (IOException) e : new IOException(e);
        }
        try {
            for (Hit h : searchDeezer(term)) {
                boolean dup = false;
                for (Hit o : out) {
                    if (o.title.equalsIgnoreCase(h.title) && o.artist.equalsIgnoreCase(h.artist)) { dup = true; break; }
                }
                if (!dup) out.add(h);
            }
        } catch (IOException | JSONException e) {
            if (out.isEmpty() && failure != null) throw failure;
        }
        if (out.isEmpty() && failure != null) throw failure;
        return out;
    }

    private static List<Hit> searchDeezer(String term) throws IOException, JSONException {
        String url = "https://api.deezer.com/search?limit=10&q=" + URLEncoder.encode(term, "UTF-8");
        JSONArray arr = new JSONObject(new String(get(url), "UTF-8")).optJSONArray("data");
        List<Hit> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Hit h = new Hit();
            h.title = o.optString("title", "");
            JSONObject ar = o.optJSONObject("artist");
            JSONObject al = o.optJSONObject("album");
            h.artist = ar == null ? "" : ar.optString("name", "");
            h.album = al == null ? "" : al.optString("title", "");
            String art = al == null ? "" : al.optString("cover_xl", "");
            if (art.isEmpty() && al != null) art = al.optString("cover_big", "");
            h.artworkUrl = art.isEmpty() ? null : art;
            if (!h.title.isEmpty()) out.add(h);
        }
        return out;
    }

    private static List<Hit> searchItunes(String term) throws IOException, JSONException {
        String url = "https://itunes.apple.com/search?media=music&entity=song&limit=10&term="
                + URLEncoder.encode(term, "UTF-8");
        JSONArray arr = new JSONObject(new String(get(url), "UTF-8")).optJSONArray("results");
        List<Hit> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Hit h = new Hit();
            h.title = o.optString("trackName", "");
            h.artist = o.optString("artistName", "");
            h.album = o.optString("collectionName", "");
            h.albumArtist = o.optString("collectionArtistName", "");
            String date = o.optString("releaseDate", "");
            h.year = date.length() >= 4 ? date.substring(0, 4) : "";
            h.genre = o.optString("primaryGenreName", "");
            h.track = o.optInt("trackNumber", 0);
            h.trackCount = o.optInt("trackCount", 0);
            String art = o.optString("artworkUrl100", "");
            h.artworkUrl = art.isEmpty() ? null : art.replace("100x100bb", "1000x1000bb");
            if (!h.title.isEmpty()) out.add(h);
        }
        return out;
    }

    /** The cover image bytes (as the server sent them), or null. */
    @Nullable
    public static byte[] cover(Hit h) throws IOException {
        return h.artworkUrl == null ? null : get(h.artworkUrl);
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
