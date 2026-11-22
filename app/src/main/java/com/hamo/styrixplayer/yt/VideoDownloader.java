package com.hamo.styrixplayer.yt;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saves a YouTube video (picture + sound) into Movies/StyrixPlayer. Qualities that YouTube serves
 * as one file are copied as they are; the sharper ones come as separate picture and sound files
 * and are joined here with the phone's own MediaMuxer. H.264 / AAC only, so every phone plays it.
 */
public final class VideoDownloader {

    public interface Progress {
        void onProgress(long done, long total);

        /** The download is done and the pieces are being joined (no percentage). */
        void onMerging();
    }

    public static final class Option {
        public int height;
        public String videoUrl, audioUrl;     // audioUrl != null: separate sound file, joined after
        public long videoBytes = -1, audioBytes = -1;

        public boolean merge() { return audioUrl != null; }

        public long totalBytes() {
            long t = Math.max(videoBytes, 0) + (merge() ? Math.max(audioBytes, 0) : 0);
            return t;
        }

        /** "1080p  ·  about 142 MB" */
        public String label() {
            long b = totalBytes();
            String size = b > 0 ? "  ·  ~" + (b >= 1_000_000_000L
                    ? String.format(java.util.Locale.US, "%.1f GB", b / 1e9)
                    : (b / 1_000_000L) + " MB") : "";
            return height + "p" + size;
        }
    }

    public static final class Plan {
        public String videoId, title, author;
        public final List<Option> options = new ArrayList<>();
    }

    private static final String FOLDER = "StyrixPlayer";
    private static final long CHUNK = 8L * 1024 * 1024;     // YouTube throttles unbounded requests
    private static final int MAX_HEIGHT = 1080;

    private VideoDownloader() {}

    /** Blocking. Looks the video up and lists the qualities that can be saved. */
    public static Plan plan(String input) throws IOException {
        String id = YouTubeResolver.parseVideoId(input);
        if (id == null) throw new YouTubeResolver.YtException("That doesn't look like a YouTube link");
        YouTubeResolver.ensureInit();
        StreamInfo si;
        try {
            si = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + id);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            String m = e.getMessage();
            throw new YouTubeResolver.YtException(m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
        }

        // best AAC (m4a) sound for the joined qualities
        AudioStream aud = null;
        for (AudioStream a : si.getAudioStreams()) {
            if (!a.isUrl() || a.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            if (a.getFormat() != org.schabi.newpipe.extractor.MediaFormat.M4A) continue;
            if (aud == null || a.getAverageBitrate() > aud.getAverageBitrate()) aud = a;
        }

        Map<Integer, Option> byHeight = new TreeMap<>(java.util.Collections.reverseOrder());
        for (VideoStream v : si.getVideoStreams()) {          // one file, picture + sound
            if (!usable(v)) continue;
            Option o = new Option();
            o.height = v.getHeight();
            o.videoUrl = v.getContent();
            o.videoBytes = clen(o.videoUrl);
            Option old = byHeight.get(o.height);
            if (old == null) byHeight.put(o.height, o);
        }
        if (aud != null) {
            long ab = clen(aud.getContent());
            for (VideoStream v : si.getVideoOnlyStreams()) {   // joined with the sound afterwards
                if (!usable(v) || byHeight.containsKey(v.getHeight())) continue;
                Option o = new Option();
                o.height = v.getHeight();
                o.videoUrl = v.getContent();
                o.videoBytes = clen(o.videoUrl);
                o.audioUrl = aud.getContent();
                o.audioBytes = ab;
                byHeight.put(o.height, o);
            }
        }
        if (byHeight.isEmpty()) throw new YouTubeResolver.YtException("No MP4 video for this link");

        Plan p = new Plan();
        p.videoId = id;
        p.title = si.getName();
        String up = si.getUploaderName();
        p.author = up == null ? "" : up.replace(" - Topic", "");
        p.options.addAll(byHeight.values());
        return p;
    }

    /** MP4 + H.264 (AV1 / VP9 can't be joined by the phone's muxer on older Android). */
    private static boolean usable(VideoStream v) {
        if (!v.isUrl() || v.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) return false;
        if (v.getFormat() != org.schabi.newpipe.extractor.MediaFormat.MPEG_4) return false;
        int h = v.getHeight();
        if (h <= 0 || h > MAX_HEIGHT) return false;
        String codec = v.getCodec();
        return codec != null && codec.startsWith("avc1");
    }

    private static long clen(String url) {
        Matcher m = Pattern.compile("[?&]clen=(\\d+)").matcher(url);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    // ---------- download ----------

    private static final class Target {
        Uri uri;
        File file;
    }

    /** Blocking. Call off the main thread. */
    public static void download(Context ctx, Plan plan, Option opt, Progress progress,
                                AtomicBoolean cancel) throws IOException {
        Context app = ctx.getApplicationContext();
        String name = safeName(plan.title) + " (" + opt.height + "p).mp4";

        File dir = new File(app.getCacheDir(), "dlvid");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File v = new File(dir, "v.mp4"), a = new File(dir, "a.m4a"), joined = new File(dir, "out.mp4");
        try {
            if (!opt.merge()) {
                Target t = create(app, name, plan.title);
                boolean ok = false;
                try (OutputStream out = open(app, t)) {
                    copyUrl(opt.videoUrl, opt.videoBytes, out, 0, opt.videoBytes, progress, cancel);
                    ok = true;
                } finally {
                    if (!ok) discard(app, t);
                }
                finish(app, t);
                return;
            }
            long total = opt.totalBytes();
            try (OutputStream out = new FileOutputStream(v)) {
                copyUrl(opt.videoUrl, opt.videoBytes, out, 0, total, progress, cancel);
            }
            try (OutputStream out = new FileOutputStream(a)) {
                copyUrl(opt.audioUrl, opt.audioBytes, out, Math.max(opt.videoBytes, 0), total, progress, cancel);
            }
            progress.onMerging();
            mux(v, a, joined, cancel);

            Target t = create(app, name, plan.title);
            boolean ok = false;
            try (OutputStream out = open(app, t); InputStream in = new FileInputStream(joined)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                ok = true;
            } finally {
                if (!ok) discard(app, t);
            }
            finish(app, t);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            v.delete();
            //noinspection ResultOfMethodCallIgnored
            a.delete();
            //noinspection ResultOfMethodCallIgnored
            joined.delete();
        }
    }

    private static Target create(Context app, String name, String title) throws IOException {
        Target t = new Target();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER);
            cv.put(MediaStore.Video.Media.TITLE, title);
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            t.uri = app.getContentResolver().insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            if (t.uri == null) throw new IOException("Couldn't create the file in Movies");
        } else {
            File d = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), FOLDER);
            if (!d.exists() && !d.mkdirs()) throw new IOException("Couldn't create " + d);
            t.file = new File(d, name);
            t.uri = Uri.fromFile(t.file);
        }
        return t;
    }

    private static OutputStream open(Context app, Target t) throws IOException {
        OutputStream os = t.file != null ? new FileOutputStream(t.file)
                : app.getContentResolver().openOutputStream(t.uri);
        if (os == null) throw new IOException("Couldn't open the file for writing");
        return os;
    }

    private static void discard(Context app, Target t) {
        try {
            if (t.file != null) //noinspection ResultOfMethodCallIgnored
                t.file.delete();
            else app.getContentResolver().delete(t.uri, null, null);
        } catch (RuntimeException ignored) {
            // nothing more to clean
        }
    }

    private static void finish(Context app, Target t) {
        if (t.file != null) {
            MediaScannerConnection.scanFile(app, new String[]{t.file.getAbsolutePath()}, null, null);
        } else {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            app.getContentResolver().update(t.uri, done, null, null);
        }
    }

    private static void copyUrl(String url, long total, OutputStream out, long base, long grand,
                                Progress progress, AtomicBoolean cancel) throws IOException {
        long pos = 0;
        byte[] buf = new byte[64 * 1024];
        while (total < 0 || pos < total) {
            if (cancel.get()) throw new AudioDownloader.Cancelled();
            long end = total < 0 ? pos + CHUNK - 1 : Math.min(pos + CHUNK, total) - 1;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(15_000);
                c.setReadTimeout(20_000);
                c.setRequestProperty("User-Agent", NpDownloader.UA);
                c.setRequestProperty("Range", "bytes=" + pos + "-" + end);
                int code = c.getResponseCode();
                if (code == 416 && pos > 0 && total < 0) break;
                if (code != 200 && code != 206) throw new IOException("Download refused (HTTP " + code + ")");
                long got = 0;
                try (InputStream in = c.getInputStream()) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel.get()) throw new AudioDownloader.Cancelled();
                        out.write(buf, 0, n);
                        got += n;
                        pos += n;
                        progress.onProgress(base + pos, grand);
                    }
                }
                if (got == 0) break;
                if (total < 0 && got < CHUNK) break;
            } finally {
                c.disconnect();
            }
        }
        if (total > 0 && pos < total) throw new IOException("Download ended early");
    }

    // ---------- joining picture and sound ----------

    private static int track(MediaExtractor e, String prefix) {
        for (int i = 0; i < e.getTrackCount(); i++) {
            String m = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static void mux(File video, File audio, File out, AtomicBoolean cancel) throws IOException {
        MediaExtractor ve = new MediaExtractor();
        MediaExtractor ae = new MediaExtractor();
        MediaMuxer mm = null;
        boolean started = false;
        try {
            ve.setDataSource(video.getAbsolutePath());
            ae.setDataSource(audio.getAbsolutePath());
            int vi = track(ve, "video/"), ai = track(ae, "audio/");
            if (vi < 0 || ai < 0) throw new IOException("Couldn't read the downloaded video");
            ve.selectTrack(vi);
            ae.selectTrack(ai);
            mm = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt = mm.addTrack(ve.getTrackFormat(vi));
            int at = mm.addTrack(ae.getTrackFormat(ai));
            mm.start();
            started = true;

            ByteBuffer buf = ByteBuffer.allocate(6 * 1024 * 1024);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean vDone = false, aDone = false;
            while (!vDone || !aDone) {
                if (cancel.get()) throw new AudioDownloader.Cancelled();
                // keep picture and sound interleaved by time, so the file plays smoothly
                boolean useV = !vDone && (aDone || ve.getSampleTime() <= ae.getSampleTime());
                MediaExtractor e = useV ? ve : ae;
                int n = e.readSampleData(buf, 0);
                if (n < 0) {
                    if (useV) vDone = true; else aDone = true;
                    continue;
                }
                int flags = (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                bi.set(0, n, e.getSampleTime(), flags);
                mm.writeSampleData(useV ? vt : at, buf, bi);
                e.advance();
            }
            mm.stop();
            started = false;
        } catch (RuntimeException e) {
            throw new IOException("Couldn't join picture and sound: " + e.getMessage());
        } finally {
            try { if (mm != null && started) mm.stop(); } catch (RuntimeException ignored) { }
            try { if (mm != null) mm.release(); } catch (RuntimeException ignored) { }
            ve.release();
            ae.release();
        }
    }

    private static String safeName(String s) {
        String n = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.isEmpty() ? "youtube_video" : n;
    }
}
