package com.hamo.styrixplayer.tags;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/** Small byte helpers shared by the format readers/writers. */
final class Bin {
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private Bin() {}

    static int be16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    static int be24(byte[] b, int o) {
        return ((b[o] & 0xFF) << 16) | ((b[o + 1] & 0xFF) << 8) | (b[o + 2] & 0xFF);
    }

    static long be32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    static long be64(byte[] b, int o) {
        return (be32(b, o) << 32) | be32(b, o + 4);
    }

    static long le32(byte[] b, int o) {
        return ((long) (b[o + 3] & 0xFF) << 24) | ((b[o + 2] & 0xFF) << 16)
                | ((b[o + 1] & 0xFF) << 8) | (b[o] & 0xFF);
    }

    static void putBe32(byte[] b, int o, long v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    static void putBe64(byte[] b, int o, long v) {
        putBe32(b, o, v >>> 32);
        putBe32(b, o + 4, v & 0xFFFFFFFFL);
    }

    static void putLe32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    static void writeBe32(OutputStream os, long v) throws IOException {
        os.write((int) (v >>> 24) & 0xFF);
        os.write((int) (v >>> 16) & 0xFF);
        os.write((int) (v >>> 8) & 0xFF);
        os.write((int) v & 0xFF);
    }

    static void writeLe32(ByteArrayOutputStream os, long v) {
        os.write((int) v & 0xFF);
        os.write((int) (v >>> 8) & 0xFF);
        os.write((int) (v >>> 16) & 0xFF);
        os.write((int) (v >>> 24) & 0xFF);
    }

    static byte[] readFully(RandomAccessFile f, int len) throws IOException {
        byte[] b = new byte[len];
        f.readFully(b);
        return b;
    }

    /** Copies [from, end of file) of f to os. */
    static void copyRest(RandomAccessFile f, long from, OutputStream os) throws IOException {
        copyRange(f, from, f.length() - from, os);
    }

    static void copyRange(RandomAccessFile f, long from, long len, OutputStream os) throws IOException {
        f.seek(from);
        byte[] buf = new byte[1 << 16];
        long left = len;
        while (left > 0) {
            int n = f.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new EOFException();
            os.write(buf, 0, n);
            left -= n;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static boolean startsWith(byte[] b, String ascii) {
        if (b.length < ascii.length()) return false;
        for (int i = 0; i < ascii.length(); i++) if (b[i] != (byte) ascii.charAt(i)) return false;
        return true;
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
