package com.hamo.styrixplayer.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns LRC text ("[01:23.45] some words") into a sorted list of timed lines. Pure Java. */
public final class LrcParser {

    /** One word / syllable of a word-synced line: absolute time, length and where it sits in text. */
    public static final class Syl {
        public final long timeMs, durMs;
        public final int start, end;

        public Syl(long timeMs, long durMs, int start, int end) {
            this.timeMs = timeMs;
            this.durMs = durMs;
            this.start = start;
            this.end = end;
        }
    }

    public static final class Line {
        public final long timeMs;
        /** Empty text means an instrumental gap. */
        public final String text;
        /** Word timings (karaoke), or null when the line is only line-synced. */
        public final Syl[] syl;

        public Line(long timeMs, String text) {
            this(timeMs, text, null);
        }

        public Line(long timeMs, String text, Syl[] syl) {
            this.timeMs = timeMs;
            this.text = text;
            this.syl = syl;
        }
    }

    private static final Pattern TIME =
            Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    /** Enhanced-LRC word stamps like <00:12.30>; parsed into the word timings used by LyricsView. */
    private static final Pattern WORD_STAMP =
            Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern OFFSET =
            Pattern.compile("^\\[offset:\\s*(-?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);

    private LrcParser() {}

    public static List<Line> parse(String lrc) {
        List<Line> out = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) return out;

        long offset = 0;
        for (String raw : lrc.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.isEmpty()) continue;

            Matcher om = OFFSET.matcher(s);
            if (om.find()) {
                try {
                    offset = Long.parseLong(om.group(1));
                } catch (NumberFormatException ignored) {
                }
                continue;
            }

            // one line may carry several stamps: [00:10.00][01:30.00] chorus
            List<Long> stamps = new ArrayList<>();
            int pos = 0;
            Matcher m = TIME.matcher(s);
            while (pos < s.length() && m.find(pos) && m.start() == pos) {
                stamps.add(toMs(m.group(1), m.group(2), m.group(3)));
                pos = m.end();
            }
            if (stamps.isEmpty()) continue;   // [ar:..], [ti:..] and other tags

            String rest = s.substring(pos);
            String text;
            Syl[] syl = null;
            if (WORD_STAMP.matcher(rest).find()) {
                Object[] built = buildWords(rest);
                text = (String) built[0];
                if (stamps.size() == 1) syl = (Syl[]) built[1];   // word times only make sense once
            } else {
                text = rest.trim();
            }
            for (long t : stamps) out.add(new Line(t, text, syl));
        }

        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });

        // [offset:+500] means the lyrics should appear 500 ms sooner
        List<Line> cleaned = new ArrayList<>(out.size());
        for (Line l : out) {
            long t = Math.max(0, l.timeMs - offset);
            Syl[] sy = l.syl;
            if (sy != null && offset != 0) {
                Syl[] shifted = new Syl[sy.length];
                for (int k = 0; k < sy.length; k++) {
                    shifted[k] = new Syl(Math.max(0, sy[k].timeMs - offset), sy[k].durMs, sy[k].start, sy[k].end);
                }
                sy = shifted;
            }
            // collapse runs of gap markers
            if (l.text.isEmpty() && !cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).text.isEmpty()) {
                continue;
            }
            cleaned.add(new Line(t, l.text, sy));
        }
        // a gap marker before the first real line is just noise
        while (!cleaned.isEmpty() && cleaned.get(0).text.isEmpty()) cleaned.remove(0);
        return cleaned;
    }

    /**
     * "<00:01.00>Hel<00:01.30>lo <00:01.80>world<00:02.40>" -> text "Hello world" plus word timings.
     * A stamp with nothing after it just marks where the previous word ends.
     */
    private static Object[] buildWords(String rest) {
        Matcher m = WORD_STAMP.matcher(rest);
        List<Long> times = new ArrayList<>();
        List<String> segs = new ArrayList<>();
        int last = 0;
        String lead = null;
        while (m.find()) {
            if (times.isEmpty()) lead = rest.substring(0, m.start());
            else segs.add(rest.substring(last, m.start()));
            times.add(toMs(m.group(1), m.group(2), m.group(3)));
            last = m.end();
        }
        segs.add(rest.substring(last));

        StringBuilder sb = new StringBuilder(lead == null ? "" : lead);
        int[] starts = new int[times.size()], ends = new int[times.size()];
        for (int i = 0; i < times.size(); i++) {
            starts[i] = sb.length();
            sb.append(segs.get(i));
            ends[i] = sb.length();
        }
        String full = sb.toString();
        int shift = 0;
        while (shift < full.length() && Character.isWhitespace(full.charAt(shift))) shift++;
        String text = full.trim();

        List<Syl> out = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            int st = Math.max(0, starts[i] - shift), en = Math.min(text.length(), ends[i] - shift);
            if (en <= st) continue;
            long next = i + 1 < times.size() ? times.get(i + 1) : times.get(i) + 500;
            out.add(new Syl(times.get(i), Math.max(1, next - times.get(i)), st, en));
        }
        return new Object[]{text, out.isEmpty() ? null : out.toArray(new Syl[0])};
    }

    /** Plain lyrics (no timestamps) to lines with time -1. */
    public static List<Line> fromPlain(String plain) {
        List<Line> out = new ArrayList<>();
        if (plain == null) return out;
        boolean lastBlank = true;
        for (String raw : plain.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                if (!lastBlank) out.add(new Line(-1, ""));
                lastBlank = true;
            } else {
                out.add(new Line(-1, s));
                lastBlank = false;
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).text.isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** Index of the last line whose time is <= posMs, or -1 if the song hasn't reached line 0. */
    public static int indexAt(List<Line> lines, long posMs) {
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    private static long toMs(String min, String sec, String frac) {
        long ms = Long.parseLong(min) * 60_000L + Long.parseLong(sec) * 1000L;
        if (frac != null && !frac.isEmpty()) {
            long f = Long.parseLong(frac);
            if (frac.length() == 1) f *= 100;
            else if (frac.length() == 2) f *= 10;
            ms += f;
        }
        return ms;
    }
}
