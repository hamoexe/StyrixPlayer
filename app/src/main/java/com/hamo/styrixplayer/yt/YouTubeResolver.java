package com.hamo.styrixplayer.yt;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Looks up a YouTube video with NewPipeExtractor and picks its best audio-only stream. */
public final class YouTubeResolver {

    private static final Pattern ID = Pattern.compile(
            "(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/|/v/)([A-Za-z0-9_-]{11})");
    private static final Pattern BARE_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static boolean initialised;

    public static final class Info {
        public String videoId, title, author, mime, streamUrl, thumbUrl;
        /** A square cover to prefer over the video thumbnail (YouTube Music art). */
        public String coverUrl;
        public long contentLength = -1, durationSec;
        public int bitrate;

        public String extension() {
            return mime.contains("mp4") ? "m4a" : mime.contains("webm") ? "webm" : "audio";
        }

        public String baseMime() {
            int i = mime.indexOf(';');
            return (i < 0 ? mime : mime.substring(0, i)).trim();
        }
    }

    public static final class YtException extends IOException {
        public YtException(String m) { super(m); }
    }

    private YouTubeResolver() {}

    @Nullable
    public static String parseVideoId(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (BARE_ID.matcher(t).matches()) return t;
        Matcher m = ID.matcher(t);
        return m.find() ? m.group(1) : null;
    }

    static synchronized void ensureInit() {
        if (!initialised) {
            NewPipe.init(new NpDownloader(), Localization.DEFAULT);
            initialised = true;
        }
    }

    public static Info resolve(String input) throws IOException {
        String id = parseVideoId(input);
        if (id == null) throw new YtException("That doesn't look like a YouTube link");
        ensureInit();

        StreamInfo si;
        try {
            si = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + id);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            String m = e.getMessage();
            throw new YtException(m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
        }

        AudioStream best = null;
        int bestScore = -1;
        for (AudioStream a : si.getAudioStreams()) {
            if (!a.isUrl() || a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            MediaFormat f = a.getFormat();
            String mime = f == null ? "" : f.getMimeType();
            // Prefer AAC in MP4 (plays everywhere), then the highest bitrate.
            int score = (mime.contains("mp4") ? 10_000_000 : 0) + Math.max(a.getAverageBitrate(), 0);
            if (score > bestScore) { bestScore = score; best = a; }
        }
        if (best == null) throw new YtException("No downloadable audio stream for this video");

        Info info = new Info();
        info.videoId = id;
        info.title = si.getName();
        String up = si.getUploaderName();
        info.author = up == null ? "" : up.replace(" - Topic", "");
        info.durationSec = si.getDuration();
        info.mime = best.getFormat() == null ? "audio/mp4" : best.getFormat().getMimeType();
        info.streamUrl = best.getContent();
        info.bitrate = best.getAverageBitrate();
        info.contentLength = clen(info.streamUrl);

        int bestH = -1;
        for (Image img : si.getThumbnails()) {
            if (img.getHeight() >= bestH) { bestH = img.getHeight(); info.thumbUrl = img.getUrl(); }
        }
        return info;
    }

    /** YouTube puts the file size in the stream URL as clen=... */
    private static long clen(String url) {
        Matcher m = Pattern.compile("[?&]clen=(\\d+)").matcher(url);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }
}
