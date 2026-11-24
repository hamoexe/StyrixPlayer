package com.hamo.styrixplayer.yt;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * YouTube's own "Most replayed" graph for a video: how many people re-watch each part. Read from
 * the same watch-page data the YouTube website uses, so it needs no sign-in. Many songs have none
 * (YouTube only shows it for videos with enough views); that answer is remembered for 3 days.
 */
public final class YtReplays {
    private static final long RETRY_MS = 3L * 24 * 3600 * 1000;

    private YtReplays() {}

    private static SharedPreferences store(Context c) {
        return c.getApplicationContext().getSharedPreferences("replays", Context.MODE_PRIVATE);
    }

    /**
     * {@code slices} values 1..100 (taller = replayed more), or null when YouTube has no graph for
     * this video. Does network work: call it off the main thread.
     */
    @Nullable
    public static int[] get(Context c, String videoId, int slices) {
        SharedPreferences sp = store(c);
        String saved = sp.getString(videoId, null);
        if (saved != null) {
            int bar = saved.indexOf('|');
            try {
                long when = Long.parseLong(saved.substring(0, bar));
                String data = saved.substring(bar + 1);
                if (!data.isEmpty()) {
                    String[] parts = data.split(",");
                    if (parts.length == slices) {
                        int[] out = new int[slices];
                        for (int i = 0; i < slices; i++) out[i] = Integer.parseInt(parts[i]);
                        return out;
                    }
                } else if (System.currentTimeMillis() - when < RETRY_MS) {
                    return null;     // asked recently, there was none
                }
            } catch (RuntimeException ignored) {
                // fall through and ask again
            }
        }
        int[] fresh;
        try {
            fresh = parse(fetch(videoId), slices);
        } catch (IOException | JSONException e) {
            return null;             // network trouble: say nothing, try again next time
        }
        StringBuilder sb = new StringBuilder().append(System.currentTimeMillis()).append('|');
        if (fresh != null) for (int i = 0; i < fresh.length; i++) sb.append(i == 0 ? "" : ",").append(fresh[i]);
        sp.edit().putString(videoId, sb.toString()).apply();
        return fresh;
    }

    private static String clientVersion() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "2." + f.format(new Date(System.currentTimeMillis() - 3L * 24 * 3600 * 1000)) + ".00.00";
    }

    private static JSONObject fetch(String videoId) throws IOException, JSONException {
        String gl = Locale.getDefault().getCountry();
        if (gl == null || gl.length() != 2) gl = "US";
        JSONObject client = new JSONObject().put("clientName", "WEB")
                .put("clientVersion", clientVersion()).put("hl", "en").put("gl", gl);
        JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client))
                .put("videoId", videoId);
        HttpURLConnection h = (HttpURLConnection)
                new URL("https://www.youtube.com/youtubei/v1/next?prettyPrint=false").openConnection();
        try {
            h.setRequestMethod("POST");
            h.setConnectTimeout(15_000);
            h.setReadTimeout(25_000);
            h.setDoOutput(true);
            h.setRequestProperty("User-Agent", NpDownloader.UA);
            h.setRequestProperty("Accept-Encoding", "gzip");
            h.setRequestProperty("Content-Type", "application/json");
            h.setRequestProperty("Origin", "https://www.youtube.com");
            h.setRequestProperty("X-Youtube-Client-Name", "1");
            h.setRequestProperty("X-Youtube-Client-Version", clientVersion());
            h.setRequestProperty("Cookie", "SOCS=CAI");
            try (OutputStream os = h.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = h.getResponseCode();
            if (code >= 400) throw new IOException("YouTube error " + code);
            InputStream in = h.getInputStream();
            if ("gzip".equalsIgnoreCase(h.getContentEncoding())) in = new GZIPInputStream(in);
            try (InputStream is = in; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                return new JSONObject(bo.toString("UTF-8"));
            }
        } finally {
            h.disconnect();
        }
    }

    /** start, length (ms) and strength (0..1) of one replay marker. */
    private static final class Mark {
        final long start, len;
        final double v;

        Mark(long start, long len, double v) {
            this.start = start;
            this.len = len;
            this.v = v;
        }
    }

    /** Finds the markers in either of the two shapes YouTube uses, then resamples them. */
    @Nullable
    static int[] parse(@Nullable JSONObject root, int slices) {
        if (root == null) return null;
        List<Mark> marks = new ArrayList<>();
        collect(root, marks);
        if (marks.size() < 4) return null;
        long end = 0;
        double top = 0;
        for (Mark m : marks) {
            end = Math.max(end, m.start + m.len);
            top = Math.max(top, m.v);
        }
        if (end <= 0 || top <= 0) return null;
        int[] out = new int[slices];
        for (int i = 0; i < slices; i++) {
            long mid = (long) ((i + 0.5) / slices * end);
            double best = 0;
            for (Mark m : marks) {
                if (mid >= m.start && mid < m.start + Math.max(1, m.len)) { best = m.v; break; }
            }
            out[i] = 1 + (int) Math.round(Math.max(0, Math.min(1, best / top)) * 99);
        }
        return out;
    }

    private static void collect(@Nullable Object o, List<Mark> out) {
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            // older shape: heatMarkerRenderer { timeRangeStartMillis, markerDurationMillis, heatMarkerIntensityScoreNormalized }
            // newer shape: markers[] { startMillis, durationMillis, intensityScoreNormalized }
            if (j.has("heatMarkerIntensityScoreNormalized") || j.has("intensityScoreNormalized")) {
                double v = j.has("heatMarkerIntensityScoreNormalized")
                        ? j.optDouble("heatMarkerIntensityScoreNormalized", 0)
                        : j.optDouble("intensityScoreNormalized", 0);
                long s = num(j, "timeRangeStartMillis", "startMillis");
                long l = num(j, "markerDurationMillis", "durationMillis");
                if (l > 0) out.add(new Mark(s, l, v));
                return;
            }
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) collect(j.opt(it.next()), out);
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out);
        }
    }

    private static long num(JSONObject j, String a, String b) {
        String s = j.optString(a, "");
        if (s.isEmpty()) s = j.optString(b, "");
        try {
            return s.isEmpty() ? 0 : (long) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
