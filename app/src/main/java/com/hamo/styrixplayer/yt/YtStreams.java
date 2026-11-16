package com.hamo.styrixplayer.yt;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/** Turns a video id into a playable audio URL, remembering it for a while (YouTube links expire). */
public final class YtStreams {

    private static final long TTL_MS = 30L * 60_000L;

    private static final class Entry {
        final String url;
        final long at;
        Entry(String url, long at) { this.url = url; this.at = at; }
    }

    private static final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    private YtStreams() {}

    /** Blocking. Call from a loader/worker thread. */
    public static String urlFor(String videoId) throws IOException {
        Entry e = cache.get(videoId);
        long now = System.currentTimeMillis();
        if (e != null && now - e.at < TTL_MS) return e.url;
        YouTubeResolver.Info info = YouTubeResolver.resolve(videoId);
        cache.put(videoId, new Entry(info.streamUrl, now));
        return info.streamUrl;
    }

    /** Drop a link that stopped working so the next attempt asks YouTube again. */
    public static void forget(String videoId) {
        cache.remove(videoId);
    }
}
