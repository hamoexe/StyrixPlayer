package com.hamo.styrixplayer.lyrics;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Converts a LyricsPlus v2 response ({"lyrics":[{"time","duration","text","syllabus":[...]}]})
 * into enhanced LRC, which is what we store and what {@link LrcParser} reads:
 *   [00:01.180]<00:01.180>Hel<00:01.384>lo <00:01.900>
 */
public final class LyricsPlusParser {
    private LyricsPlusParser() {}

    @Nullable
    public static String toEnhancedLrc(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.optJSONArray("lyrics");
        if (arr == null || arr.length() == 0) return null;

        StringBuilder out = new StringBuilder();
        int lines = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long time = o.optLong("time", -1);
            if (time < 0) continue;
            String text = o.isNull("text") ? "" : o.optString("text", "");

            out.append('[').append(stamp(time)).append(']');
            String words = words(o.optJSONArray("syllabus"), text);
            if (words != null) out.append(words);
            else out.append(text.trim());
            out.append('\n');
            lines++;
        }
        return lines == 0 ? null : out.toString();
    }

    /** "<t>syl<t>syl<end>" if the syllables really spell out the line, else null. */
    @Nullable
    private static String words(@Nullable JSONArray syl, String lineText) {
        if (syl == null || syl.length() == 0) return null;
        StringBuilder sb = new StringBuilder();
        StringBuilder spelled = new StringBuilder();
        long end = -1;
        for (int i = 0; i < syl.length(); i++) {
            JSONObject s = syl.optJSONObject(i);
            if (s == null || s.isNull("text")) return null;
            String t = s.optString("text", "");
            long time = s.optLong("time", -1);
            if (time < 0) return null;
            sb.append('<').append(stamp(time)).append('>').append(t);
            spelled.append(t);
            end = time + Math.max(1, s.optLong("duration", 300));
        }
        // sanity: ignore the syllables if they don't match the line text (spacing aside)
        if (!squash(spelled.toString()).equals(squash(lineText))) return null;
        sb.append('<').append(stamp(end)).append('>');
        return sb.toString();
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    static String stamp(long ms) {
        long m = ms / 60000, s = (ms / 1000) % 60, f = ms % 1000;
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, f);
    }
}
