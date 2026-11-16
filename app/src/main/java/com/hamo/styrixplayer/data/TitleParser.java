package com.hamo.styrixplayer.data;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits messy titles like "Ariana Grande - bye [Altare Remix]" or "FUNK SIGILO (SLOWED)"
 * into a clean title, an artist line and small labels ("Altare Remix", "Slowed").
 */
public final class TitleParser {

    /** Turned off from Settings. */
    public static volatile boolean enabled = true;

    public static final class Result {
        public final String title;
        public final String artistLine;
        public final List<String> tags;

        Result(String title, String artistLine, List<String> tags) {
            this.title = title;
            this.artistLine = artistLine;
            this.tags = tags;
        }
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Words that make a (bracketed) group look like a version label. */
    private static final Pattern TAG = Pattern.compile(
            "\\b(slowed|sped|speed(ed)? up|reverb|remix|mix|version|edit|remaster(ed)?|instrumental|"
                    + "nightcore|lo-?fi|bass boost(ed)?|extended|acoustic|live|cover|daycore|8d|16d|"
                    + "slow|fast|radio|ultra|mega|super)\\b", FLAGS);

    /** Stricter list, used for the "Title - Slowed" style suffix. */
    private static final Pattern STRONG = Pattern.compile(
            "\\b(slowed|sped|speed(ed)? up|reverb|remix|nightcore|instrumental|version|edit|"
                    + "remaster(ed)?|daycore|acoustic)\\b", FLAGS);

    /** "(Official Video)" and friends are dropped instead of labelled. */
    private static final Pattern NOISE = Pattern.compile(
            "\\b(official|lyrics?|video|audio|visuali[sz]er|hd|hq|4k|explicit)\\b", FLAGS);

    private static final Pattern EDGE_SEPARATORS = Pattern.compile(
            "^[\\s\\-\\u2013\\u2014|:\\u00B7]+|[\\s\\-\\u2013\\u2014|:\\u00B7]+$");

    private static final String[] DASHES = {" - ", " \u2013 ", " \u2014 "};

    private TitleParser() {}

    public static Result parse(@Nullable String rawTitle, @Nullable String rawArtist) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        String artist = rawArtist == null ? "" : rawArtist.trim();
        List<String> tags = new ArrayList<>();
        if (!enabled || title.isEmpty()) return new Result(title, artist, tags);

        // 1. pull bracketed groups out of the title
        StringBuilder rest = new StringBuilder();
        int i = 0;
        while (i < title.length()) {
            char ch = title.charAt(i);
            char close = closer(ch);
            int end = close != 0 ? title.indexOf(close, i + 1) : -1;
            if (end > i) {
                String inner = title.substring(i + 1, end).trim();
                boolean always = ch == '[' || ch == '\u3010';
                if (!inner.isEmpty() && (always || looksLikeTag(inner))) {
                    boolean strong = STRONG.matcher(inner).find();
                    if (strong || !NOISE.matcher(inner).find()) addTag(tags, inner);
                    i = end + 1;
                    continue;
                }
            }
            rest.append(ch);
            i++;
        }
        title = tidy(rest.toString());

        // 2. "Left - Right" patterns
        String artistLine = artist;
        int dash = -1;
        int dashLen = 3;
        for (String d : DASHES) {
            int idx = title.indexOf(d);
            if (idx > 0 && (dash < 0 || idx < dash)) dash = idx;
        }
        if (dash > 0) {
            String left = title.substring(0, dash).trim();
            String right = title.substring(dash + dashLen).trim();
            if (!left.isEmpty() && !right.isEmpty()) {
                if (wordCount(right) <= 4 && STRONG.matcher(right).find()) {
                    addTag(tags, right);                       // "MEIA NOITE - Mega Slowed"
                    title = left;
                } else if (noArtist(artist)) {
                    artistLine = left;                         // "Artist - Track", no artist tag
                    title = right;
                } else if (sameArtist(left, artist)) {
                    title = right;                             // artist repeated in the title
                } else if (hasRemixTag(tags)) {
                    title = right;                             // "Original Artist - Track [X Remix]"
                    artistLine = artist + " \u00B7 orig. " + left;
                }
            }
        }

        title = tidy(title);
        if (title.isEmpty()) title = rawTitle == null ? "" : rawTitle.trim();
        return new Result(title, artistLine, tags);
    }

    // ---------- helpers ----------

    private static char closer(char open) {
        switch (open) {
            case '(': return ')';
            case '[': return ']';
            case '\u3010': return '\u3011';
            case '\uFF08': return '\uFF09';
            default: return 0;
        }
    }

    private static boolean looksLikeTag(String inner) {
        return wordCount(inner) <= 6 && (TAG.matcher(inner).find() || NOISE.matcher(inner).find());
    }

    private static boolean hasRemixTag(List<String> tags) {
        for (String t : tags) {
            String l = t.toLowerCase(Locale.ROOT);
            if (l.contains("mix") || l.contains("cover") || l.contains("edit")) return true;
        }
        return false;
    }

    private static boolean noArtist(String artist) {
        return artist.isEmpty() || artist.equalsIgnoreCase("Unknown artist")
                || artist.equalsIgnoreCase("<unknown>");
    }

    private static boolean sameArtist(String left, String artist) {
        String l = left.toLowerCase(Locale.ROOT).trim();
        String a = artist.toLowerCase(Locale.ROOT).trim();
        if (l.equals(a)) return true;
        return l.length() >= 2 && a.length() >= 2 && (a.contains(l) || l.contains(a));
    }

    private static int wordCount(String s) {
        String t = s.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    private static String tidy(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return EDGE_SEPARATORS.matcher(t).replaceAll("").trim();
    }

    private static void addTag(List<String> tags, String inner) {
        if (tags.size() >= 4) return;
        String t = canonicalTag(displayCase(inner.replaceAll("\\s+", " ").trim()));
        if (t.isEmpty()) return;
        for (String existing : tags) if (existing.equalsIgnoreCase(t)) return;
        tags.add(t);
    }

    /** "Slow Version", "Slowed To Perfection", "Slowed & Reverb" ... one spelling per idea. */
    private static String canonicalTag(String s) {
        String l = s.toLowerCase(Locale.ROOT).replace('&', '+').replace(" and ", " + ").replace(" x ", " + ");
        l = l.replaceAll("\\s*\\+\\s*", " + ").replaceAll("[-_]", " ").replaceAll("\\s+", " ").trim();
        boolean reverb = l.contains("reverb");
        boolean sped = l.contains("sped up") || l.contains("speed up") || l.contains("speedup")
                || l.contains("sped") || l.contains("nightcore");
        boolean slow = l.contains("slow");
        if (!slow && !sped) return s;
        String base;
        if (sped) base = "Sped Up";
        else if (l.contains("ultra") || l.contains("mega")) base = "Ultra Slowed";
        else if (l.contains("super")) base = "Super Slowed";
        else base = "Slowed";
        return reverb ? base + " + Reverb" : base;
    }

    /** "SLOWED" and "slowed + reverb" become "Slowed" and "Slowed + Reverb"; mixed case is left alone. */
    private static String displayCase(String s) {
        boolean allUpper = s.equals(s.toUpperCase(Locale.ROOT));
        boolean allLower = s.equals(s.toLowerCase(Locale.ROOT));
        if (!allUpper && !allLower) return s;
        StringBuilder sb = new StringBuilder();
        String[] words = s.split(" ");
        for (int w = 0; w < words.length; w++) {
            String word = words[w];
            if (w > 0) sb.append(' ');
            boolean hasDigit = false;
            for (int k = 0; k < word.length(); k++) if (Character.isDigit(word.charAt(k))) hasDigit = true;
            if (hasDigit || word.isEmpty()) {
                sb.append(word);
            } else {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }
}
