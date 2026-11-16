package com.hamo.styrixplayer.lyrics;

import android.util.Log;
import android.util.Xml;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.Locale;

public final class BiniLyricsParser {
    private BiniLyricsParser() {}

    @Nullable
    public static String toEnhancedLrc(String ttml) {
        if (ttml == null || !ttml.contains("<tt")) return null;

        StringBuilder out = new StringBuilder();
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(ttml));

            int eventType = parser.getEventType();
            boolean inLine = false;
            long lineStartMs = -1;
            long lineEndMs = -1;
            long pendingEnd = -1;      // end of the last finished word, written once we know if a gap follows
            StringBuilder lineText = new StringBuilder();
            StringBuilder lineWords = new StringBuilder();
            long curSpanEnd = -1;

            while (eventType != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if (eventType == XmlPullParser.START_TAG) {
                    if ("p".equals(tagName)) {
                        inLine = true;
                        lineStartMs = parseTime(parser.getAttributeValue(null, "begin"));
                        lineEndMs = parseTime(parser.getAttributeValue(null, "end"));
                        pendingEnd = -1;
                        lineText.setLength(0);
                        lineWords.setLength(0);
                    } else if (inLine && "span".equals(tagName)) {
                        // Ignore background vocals
                        String role = parser.getAttributeValue(null, "ttm:role");
                        if ("x-bg".equals(role) || "x-translation".equals(role) || "x-roman".equals(role)) {
                            skip(parser);
                        } else {
                            long spanStart = parseTime(parser.getAttributeValue(null, "begin"));
                            // The previous word ended before this one starts (a pause): say so, otherwise
                            // its highlight would crawl across the whole pause.
                            if (pendingEnd >= 0 && spanStart - pendingEnd > 60) {
                                lineWords.append('<').append(stamp(pendingEnd)).append('>');
                            }
                            pendingEnd = -1;
                            long spanEnd = parseTime(parser.getAttributeValue(null, "end"));
                            if (spanEnd > spanStart) curSpanEnd = spanEnd;
                            else curSpanEnd = -1;
                            if (spanStart >= 0) {
                                lineWords.append('<').append(stamp(spanStart)).append('>');
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.TEXT) {
                    // This captures the text INSIDE the span AND the spaces BETWEEN spans
                    if (inLine) {
                        String text = parser.getText();
                        if (text != null) {
                            lineText.append(text);
                            lineWords.append(text);
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (inLine && "span".equals(tagName) && curSpanEnd >= 0) {
                        pendingEnd = curSpanEnd;
                    }
                    if ("p".equals(tagName) && inLine) {
                        inLine = false;
                        // Without a closing stamp the last word gets a made-up 0.5 s and the sweep races through it.
                        long endStamp = pendingEnd >= 0 ? pendingEnd : lineEndMs;
                        if (endStamp > lineStartMs && lineWords.indexOf("<") >= 0) {
                            lineWords.append('<').append(stamp(endStamp)).append('>');
                        }
                        String finalText = lineText.toString().trim();
                        if (lineStartMs >= 0 && !finalText.isEmpty()) {
                            out.append('[').append(stamp(lineStartMs)).append(']');
                            out.append(lineWords.toString().trim());
                            out.append('\n');
                        }
                    }
                }
                eventType = parser.next();
            }
        } catch (Exception e) {
            Log.e("BiniLyricsParser", "Failed to parse TTML: " + e.getMessage());
            return null;
        }

        return out.length() == 0 ? null : out.toString();
    }

    private static void skip(XmlPullParser parser) throws Exception {
        int depth = 1;
        while (depth != 0) {
            int event = parser.next();
            if (event == XmlPullParser.START_TAG) depth++;
            else if (event == XmlPullParser.END_TAG) depth--;
        }
    }

    private static long parseTime(String timeStr) {
        if (timeStr == null || timeStr.isEmpty()) return -1;
        try {
            if (timeStr.endsWith("s")) {
                return Math.round(Double.parseDouble(timeStr.substring(0, timeStr.length() - 1)) * 1000);
            } else if (timeStr.contains(":")) {
                // Handles "1:00.964"
                String[] parts = timeStr.split(":");
                long h = parts.length > 2 ? Long.parseLong(parts[0]) : 0;
                long m = parts.length > 2 ? Long.parseLong(parts[1]) : Long.parseLong(parts[0]);
                double s = parts.length > 2 ? Double.parseDouble(parts[2]) : Double.parseDouble(parts[1]);
                return (h * 3600000) + (m * 60000) + Math.round(s * 1000);
            } else {
                // Handles raw seconds like "27.395"
                return Math.round(Double.parseDouble(timeStr) * 1000);
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    private static String stamp(long ms) {
        long m = ms / 60000, s = (ms / 1000) % 60, f = ms % 1000;
        return String.format(Locale.US, "%02d:%02d.%03d", m, s, f);
    }
}