package com.hamo.styrixplayer.tags;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Ogg Vorbis and Ogg Opus. The comment header lives in its own page(s) near the start; we
 * rebuild those pages and renumber + re-checksum every page after them.
 */
final class OggTags {

    private static final int[] CRC = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int k = 0; k < 8; k++) r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            CRC[i] = r;
        }
    }

    private static final class Page {
        int type;
        long granule;
        long serial;
        long seq;
        int[] lacing;
        byte[] data;
    }

    private OggTags() {}

    static boolean matches(byte[] head) {
        return Bin.startsWith(head, "OggS");
    }

    private static Page readPage(RandomAccessFile f) throws IOException {
        byte[] h = new byte[27];
        int first = f.read(h, 0, 27);
        if (first < 0) return null;
        if (first < 27) throw new EOFException();
        if (h[0] != 'O' || h[1] != 'g' || h[2] != 'g' || h[3] != 'S') throw new IOException("Lost Ogg sync");
        Page p = new Page();
        p.type = h[5] & 0xFF;
        p.granule = Bin.le32(h, 6) | (Bin.le32(h, 10) << 32);
        p.serial = Bin.le32(h, 14);
        p.seq = Bin.le32(h, 18);
        int n = h[26] & 0xFF;
        byte[] lac = Bin.readFully(f, n);
        p.lacing = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            p.lacing[i] = lac[i] & 0xFF;
            total += p.lacing[i];
        }
        p.data = Bin.readFully(f, total);
        return p;
    }

    private static void writePage(OutputStream os, int type, long granule, long serial, long seq,
                                  int[] lacing, int nLacing, byte[] data, int dataOff, int dataLen)
            throws IOException {
        byte[] page = new byte[27 + nLacing + dataLen];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[5] = (byte) type;
        Bin.putLe32(page, 6, granule & 0xFFFFFFFFL);
        Bin.putLe32(page, 10, granule >>> 32);
        Bin.putLe32(page, 14, serial);
        Bin.putLe32(page, 18, seq);
        page[26] = (byte) nLacing;
        for (int i = 0; i < nLacing; i++) page[27 + i] = (byte) lacing[i];
        System.arraycopy(data, dataOff, page, 27 + nLacing, dataLen);
        int crc = 0;
        for (byte b : page) crc = (crc << 8) ^ CRC[((crc >>> 24) & 0xFF) ^ (b & 0xFF)];
        Bin.putLe32(page, 22, crc & 0xFFFFFFFFL);
        os.write(page);
    }

    /** Header packets + where the audio pages start. */
    private static final class Headers {
        List<byte[]> packets = new ArrayList<>();
        long audioStart;
        long serial;
        boolean opus;
    }

    private static Headers readHeaders(RandomAccessFile f) throws IOException {
        Headers hd = new Headers();
        f.seek(0);
        ByteArrayOutputStream cur = new ByteArrayOutputStream();
        int need = -1;
        while (true) {
            Page p = readPage(f);
            if (p == null) throw new IOException("Ogg ended inside the headers");
            if (hd.packets.isEmpty() && cur.size() == 0 && need < 0) hd.serial = p.serial;
            if (p.serial != hd.serial) throw new IOException("Multiplexed Ogg isn't supported");
            int off = 0;
            for (int i = 0; i < p.lacing.length; i++) {
                cur.write(p.data, off, p.lacing[i]);
                off += p.lacing[i];
                if (p.lacing[i] < 255) {
                    hd.packets.add(cur.toByteArray());
                    cur.reset();
                    if (need < 0) {
                        byte[] first = hd.packets.get(0);
                        if (Bin.startsWith(first, "OpusHead")) {
                            hd.opus = true;
                            need = 2;
                        } else if (first.length > 7 && first[0] == 1 && Bin.startsWith(slice(first, 1), "vorbis")) {
                            need = 3;
                        } else {
                            throw new IOException("Only Vorbis and Opus Ogg files can be tagged");
                        }
                    }
                    if (hd.packets.size() == need) {
                        if (i != p.lacing.length - 1) throw new IOException("Unusual Ogg layout");
                        hd.audioStart = f.getFilePointer();
                        return hd;
                    }
                }
            }
        }
    }

    private static byte[] slice(byte[] b, int from) {
        byte[] o = new byte[b.length - from];
        System.arraycopy(b, from, o, 0, o.length);
        return o;
    }

    private static VorbisComments commentsOf(Headers hd) throws IOException {
        byte[] c = hd.packets.get(1);
        return VorbisComments.parse(c, hd.opus ? 8 : 7);
    }

    static TagData read(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            Headers hd = readHeaders(f);
            VorbisComments vc = commentsOf(hd);
            TagData d = new TagData();
            vc.toTagData(d);
            String pic = vc.get("METADATA_BLOCK_PICTURE");
            if (pic != null) {
                try {
                    d.cover = Picture.parse(java.util.Base64.getDecoder().decode(pic)).data;
                } catch (RuntimeException | IOException ignored) {
                }
            }
            return d;
        }
    }

    static void write(File in, File out, TagData e) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(in, "r");
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            Headers hd = readHeaders(f);
            VorbisComments vc = commentsOf(hd);
            vc.applyEdit(e);
            if (e.coverMode != TagData.CoverMode.KEEP) {
                vc.remove("METADATA_BLOCK_PICTURE");
                vc.remove("COVERART");
                vc.remove("COVERARTMIME");
                if (e.coverMode == TagData.CoverMode.SET && e.cover != null) {
                    String b64 = java.util.Base64.getEncoder().encodeToString(Picture.of(e.cover).build());
                    vc.entries.add(new String[]{"METADATA_BLOCK_PICTURE", b64});
                }
            }

            // new comment packet
            ByteArrayOutputStream cp = new ByteArrayOutputStream();
            if (hd.opus) {
                cp.write("OpusTags".getBytes(Bin.LATIN1));
                cp.write(vc.build());
            } else {
                cp.write(3);
                cp.write("vorbis".getBytes(Bin.LATIN1));
                cp.write(vc.build());
                cp.write(1);                                   // framing bit
            }

            long seq = 0;
            // page 0: the identification header alone (BOS)
            List<byte[]> first = new ArrayList<>();
            first.add(hd.packets.get(0));
            seq = paginate(os, first, hd.serial, seq, true);
            // then comment (+ setup header for Vorbis)
            List<byte[]> rest = new ArrayList<>();
            rest.add(cp.toByteArray());
            for (int i = 2; i < hd.packets.size(); i++) rest.add(hd.packets.get(i));
            seq = paginate(os, rest, hd.serial, seq, false);

            // audio pages: same content, new sequence numbers + checksums
            f.seek(hd.audioStart);
            while (true) {
                Page p = readPage(f);
                if (p == null) break;
                if (p.serial != hd.serial) throw new IOException("Chained Ogg isn't supported");
                writePage(os, p.type, p.granule, p.serial, seq++, p.lacing, p.lacing.length,
                        p.data, 0, p.data.length);
            }
        }
    }

    /** Splits packets into pages (header granule 0) and writes them. Returns the next sequence number. */
    private static long paginate(OutputStream os, List<byte[]> packets, long serial, long seq,
                                 boolean bos) throws IOException {
        // lay out all lacing values and the bytes they cover
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Integer> lacing = new ArrayList<>();
        for (byte[] p : packets) {
            int full = p.length / 255;
            for (int i = 0; i < full; i++) lacing.add(255);
            lacing.add(p.length % 255);
            bytes.write(p, 0, p.length);
        }
        byte[] all = bytes.toByteArray();
        int li = 0, off = 0;
        boolean continued = false;
        boolean firstPage = true;
        while (li < lacing.size()) {
            int n = Math.min(255, lacing.size() - li);
            int[] lac = new int[n];
            int len = 0;
            for (int i = 0; i < n; i++) {
                lac[i] = lacing.get(li + i);
                len += lac[i];
            }
            boolean endsOpen = lac[n - 1] == 255;
            boolean anyEnds = false;
            for (int v : lac) if (v < 255) anyEnds = true;
            int type = (continued ? 1 : 0) | (bos && firstPage ? 2 : 0);
            long granule = anyEnds ? 0 : -1L;
            writePage(os, type, granule, serial, seq++, lac, n, all, off, len);
            off += len;
            li += n;
            continued = endsOpen;
            firstPage = false;
        }
        return seq;
    }
}
