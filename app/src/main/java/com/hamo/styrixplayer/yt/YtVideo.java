package com.hamo.styrixplayer.yt;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Finds a small picture-only video stream for a YouTube video (the player shows it muted over the audio). */
public final class YtVideo {

    private static final long TTL_MS = 30L * 60_000L;
    private static final int MAX_HEIGHT = 360;

    private static final class Entry {
        final String url;
        final long at;
        Entry(String url, long at) { this.url = url; this.at = at; }
    }

    private static final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    private YtVideo() {}

    /** Blocking. Call from a worker thread. */
    public static String urlFor(String videoId) throws IOException {
        Entry e = cache.get(videoId);
        long now = System.currentTimeMillis();
        if (e != null && now - e.at < TTL_MS) return e.url;
        String url = find(videoId);
        cache.put(videoId, new Entry(url, now));
        return url;
    }

    public static void forget(String videoId) {
        cache.remove(videoId);
    }

    private static String find(String videoId) throws IOException {
        YouTubeResolver.ensureInit();
        StreamInfo si;
        try {
            si = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + videoId);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            String m = ex.getMessage();
            throw new YouTubeResolver.YtException(m == null || m.isEmpty() ? ex.getClass().getSimpleName() : m);
        }
        // muxed 360p mp4 first: YouTube does not throttle those, the picture-only ones often crawl
        VideoStream best = pick(si.getVideoStreams());
        if (best == null) best = pick(si.getVideoOnlyStreams());
        if (best == null) throw new YouTubeResolver.YtException("No video stream for this song");
        return best.getContent();
    }

    /** MP4 (plays everywhere) up to 480p, the sharpest one; otherwise the smallest that exists. */
    private static VideoStream pick(List<VideoStream> list) {
        VideoStream best = null, smallest = null;
        int bestScore = -1;
        for (VideoStream v : list) {
            if (!v.isUrl() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            int h = v.getHeight();
            if (smallest == null || h < smallest.getHeight()) smallest = v;
            if (h > MAX_HEIGHT || h <= 0) continue;
            MediaFormat f = v.getFormat();
            int score = (f == MediaFormat.MPEG_4 ? 100_000 : 0) + h;
            if (score > bestScore) { bestScore = score; best = v; }
        }
        return best != null ? best : smallest;
    }
}
