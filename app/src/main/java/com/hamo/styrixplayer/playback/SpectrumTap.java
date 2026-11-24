package com.hamo.styrixplayer.playback;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Listens to the audio the player is about to play and turns it into a handful of frequency
 * bands for the visualizer. It reads the player's own audio, so it needs no microphone
 * permission and works the same for phone songs and streamed ones.
 * Does nothing (no CPU) unless a visualizer is on screen ({@link #setActive}).
 */
@OptIn(markerClass = UnstableApi.class)
public final class SpectrumTap implements TeeAudioProcessor.AudioBufferSink {
    public static final int BANDS = 32;
    public static final float LOW_HZ = 20f, HIGH_HZ = 14000f;
    private static final int N = 4096;            // FFT size: ~10.8 Hz per bin, so the deep bass gets its own bars
    private static final int HOP = 1024;          // new analysis every 1024 samples (~23 ms)
    private static final int KEEP = 48;           // frames kept for the delay

    private static final SpectrumTap INSTANCE = new SpectrumTap();

    public static SpectrumTap get() { return INSTANCE; }

    private volatile boolean active;
    private int sampleRate = 44100, channels = 2, encoding = C.ENCODING_INVALID;

    private final float[] ring = new float[N];
    private int ringPos, sinceLast;
    private final float[] re = new float[N], im = new float[N], window = new float[N];
    private final float[] cos = new float[N / 2], sin = new float[N / 2];
    private final int[] bandLo = new int[BANDS], bandHi = new int[BANDS];
    private boolean bandsFor;
    private int bandsRate;

    // finished frames, newest last; the view reads one that is a little old to line up with the speaker
    private final float[][] frames = new float[KEEP][BANDS];
    private final long[] frameTime = new long[KEEP];
    private int head;                              // next slot to write
    private final Object lock = new Object();

    private SpectrumTap() {
        for (int i = 0; i < N; i++) window[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        for (int i = 0; i < N / 2; i++) {
            cos[i] = (float) Math.cos(-2 * Math.PI * i / N);
            sin[i] = (float) Math.sin(-2 * Math.PI * i / N);
        }
    }

    public void setActive(boolean on) {
        if (active == on) return;
        active = on;
        if (!on) synchronized (lock) {
            head = 0;
            java.util.Arrays.fill(frameTime, 0L);
        }
    }

    @Override
    public void flush(int sampleRateHz, int channelCount, int enc) {
        sampleRate = sampleRateHz;
        channels = Math.max(1, channelCount);
        encoding = enc;
        ringPos = 0;
        sinceLast = 0;
    }

    @Override
    public void handleBuffer(ByteBuffer buffer) {
        if (!active || encoding != C.ENCODING_PCM_16BIT) return;
        ByteBuffer b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int frameBytes = 2 * channels;
        while (b.remaining() >= frameBytes) {
            float sum = 0;
            for (int c = 0; c < channels; c++) sum += b.getShort();
            ring[ringPos] = sum / (channels * 32768f);
            ringPos = (ringPos + 1) % N;
            if (++sinceLast >= HOP) {
                sinceLast = 0;
                analyse();
            }
        }
    }

    private void layoutBands() {
        // log-spaced bands from ~20 Hz to ~14 kHz
        float binHz = sampleRate / (float) N;
        float lo = LOW_HZ, hi = Math.min(HIGH_HZ, sampleRate / 2f - 1);
        int prev = Math.max(1, Math.round(lo / binHz));
        for (int i = 0; i < BANDS; i++) {
            float f = (float) (lo * Math.pow(hi / lo, (i + 1) / (double) BANDS));
            int end = Math.max(prev + 1, Math.round(f / binHz));
            bandLo[i] = prev;
            bandHi[i] = Math.min(N / 2, end);
            prev = bandHi[i];
        }
        bandsRate = sampleRate;
        bandsFor = true;
    }

    private void analyse() {
        if (!bandsFor || bandsRate != sampleRate) layoutBands();
        for (int i = 0; i < N; i++) {
            re[i] = ring[(ringPos + i) % N] * window[i];
            im[i] = 0f;
        }
        fft();
        float[] out = new float[BANDS];
        for (int b = 0; b < BANDS; b++) {
            float peak = 0;
            for (int k = bandLo[b]; k < bandHi[b]; k++) {
                float m = (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) * 4f / N;
                if (m > peak) peak = m;
            }
            // raw level in dB (0 = full scale); the view decides how loud is "full"
            float db = 20f * (float) Math.log10(peak + 1e-9f) + b * 0.25f; // slight tilt: highs are quieter
            out[b] = Math.max(-100f, Math.min(0f, db));
        }
        long now = System.nanoTime();
        synchronized (lock) {
            System.arraycopy(out, 0, frames[head], 0, BANDS);
            frameTime[head] = now;
            head = (head + 1) % KEEP;
        }
    }

    /** In-place radix-2 FFT of re/im (size N). */
    private void fft() {
        for (int i = 1, j = 0; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1, step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step], wi = sin[k * step];
                    int a = i + k, c = a + half;
                    float xr = re[c] * wr - im[c] * wi;
                    float xi = re[c] * wi + im[c] * wr;
                    re[c] = re[a] - xr; im[c] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                }
            }
        }
    }

    /**
     * Copies into {@code out} the newest frame that is at least {@code delayNanos} old.
     * Returns false when there is nothing (playback stopped or paused for a while).
     */
    public boolean read(float[] out, long delayNanos) {
        long cutoff = System.nanoTime() - delayNanos;
        synchronized (lock) {
            int best = -1;
            long bestTime = 0;
            for (int i = 0; i < KEEP; i++) {
                long t = frameTime[i];
                if (t != 0 && t <= cutoff && t > bestTime) { best = i; bestTime = t; }
            }
            if (best < 0 || cutoff - bestTime > 250_000_000L) return false; // stale: the music stopped
            System.arraycopy(frames[best], 0, out, 0, BANDS);
            return true;
        }
    }
}
