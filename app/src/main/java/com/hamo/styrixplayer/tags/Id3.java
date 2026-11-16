package com.hamo.styrixplayer.tags;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MP3: reads ID3v2.2/2.3/2.4 and writes v2.3 (or v2.4 when the file already had it). */
final class Id3 {

    private static final class Frame {
        String id;
        int flags;
        byte[] data;
    }

    private static final class Tag {
        int major;                 // 0 = no tag
        long audioStart;
        List<Frame> frames = new ArrayList<>();
    }

    private static final Map<String, String> V22 = new HashMap<>();

    static {
        V22.put("TT2", "TIT2");
        V22.put("TP1", "TPE1");
        V22.put("TAL", "TALB");
        V22.put("TP2", "TPE2");
        V22.put("TYE", "TYER");
        V22.put("TRK", "TRCK");
        V22.put("TCO", "TCON");
    }

    private Id3() {}

    static boolean matches(byte[] head) {
        if (head.length >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') return true;
        return head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
    }

    // ---------- read ----------

    static TagData read(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            Tag t = parse(f);
            TagData d = new TagData();
            for (Frame fr : t.frames) {
                switch (fr.id) {
                    case "TIT2": d.title = text(fr.data); break;
                    case "TPE1": d.artist = text(fr.data); break;
                    case "TALB": d.album = text(fr.data); break;
                    case "TPE2": d.albumArtist = text(fr.data); break;
                    case "TYER": if (d.year == null) d.year = text(fr.data); break;
                    case "TDRC": d.year = text(fr.data); break;
                    case "TRCK": d.track = text(fr.data); break;
                    case "TCON": d.genre = text(fr.data); break;
                    case "APIC": {
                        byte[] img = apicImage(fr.data);
                        if (img != null && d.cover == null) d.cover = img;
                        break;
                    }
                    default: break;
                }
            }
            return d;
        }
    }

    private static Tag parse(RandomAccessFile f) throws IOException {
        Tag t = new Tag();
        if (f.length() < 10) return t;
        f.seek(0);
        byte[] h = Bin.readFully(f, 10);
        if (h[0] != 'I' || h[1] != 'D' || h[2] != '3') return t;
        int major = h[3] & 0xFF;
        int flags = h[5] & 0xFF;
        int size = syncsafe(h, 6);
        if (major < 2 || major > 4 || size < 0 || size > f.length()) return t;
        byte[] body = Bin.readFully(f, size);
        t.major = major;
        t.audioStart = 10L + size + (major == 4 && (flags & 0x10) != 0 ? 10 : 0);
        if ((flags & 0x80) != 0) body = deunsync(body);

        int pos = 0;
        if ((flags & 0x40) != 0 && major >= 3 && body.length >= 4) {
            pos = major == 4 ? syncsafe(body, 0) : (int) Bin.be32(body, 0) + 4;
        }
        int idLen = major == 2 ? 3 : 4;
        int hdr = major == 2 ? 6 : 10;
        while (pos + hdr <= body.length) {
            if (body[pos] == 0) break;                         // padding
            String id = new String(body, pos, idLen, Bin.LATIN1);
            int sz = major == 2 ? Bin.be24(body, pos + 3)
                    : major == 4 ? syncsafe(body, pos + 4) : (int) Bin.be32(body, pos + 4);
            int fl = major == 2 ? 0 : Bin.be16(body, pos + 8);
            pos += hdr;
            if (sz < 0 || pos + sz > body.length) break;
            byte[] data = Arrays.copyOfRange(body, pos, pos + sz);
            pos += sz;

            Frame fr = new Frame();
            fr.flags = fl;
            fr.data = data;
            if (major == 2) {
                if (id.equals("PIC")) {                        // v2.2 picture -> APIC
                    byte[] apic = picToApic(data);
                    if (apic == null) continue;
                    fr.id = "APIC";
                    fr.data = apic;
                } else if (V22.containsKey(id)) {
                    fr.id = V22.get(id);
                } else {
                    continue;                                  // other v2.2 frames can't be carried over
                }
            } else {
                fr.id = id;
            }
            t.frames.add(fr);
        }
        return t;
    }

    // ---------- write ----------

    static void write(File in, File out, TagData e) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(in, "r");
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            Tag t = parse(f);
            int major = t.major == 4 ? 4 : 3;
            List<Frame> frames = new ArrayList<>(t.frames);

            if (e.title != null) replace(frames, major, e.title, "TIT2");
            if (e.artist != null) replace(frames, major, e.artist, "TPE1");
            if (e.album != null) replace(frames, major, e.album, "TALB");
            if (e.albumArtist != null) replace(frames, major, e.albumArtist, "TPE2");
            if (e.track != null) replace(frames, major, e.track, "TRCK");
            if (e.genre != null) replace(frames, major, e.genre, "TCON");
            if (e.year != null) {
                remove(frames, "TYER", "TDAT", "TIME", "TRDA", "TDRC");
                String y = e.year.trim();
                if (!y.isEmpty()) {
                    if (major == 4) {
                        frames.add(textFrame("TDRC", y, major));
                    } else {
                        String digits = y.length() >= 4 ? y.substring(0, 4) : y;
                        frames.add(textFrame("TYER", digits, major));
                    }
                }
            }
            if (e.coverMode != TagData.CoverMode.KEEP) {
                remove(frames, "APIC");
                if (e.coverMode == TagData.CoverMode.SET && e.cover != null) {
                    frames.add(apicFrame(e.cover));
                }
            }

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (Frame fr : frames) {
                byte[] id = fr.id.getBytes(Bin.LATIN1);
                body.write(id, 0, 4);
                byte[] sz = new byte[4];
                if (major == 4) putSyncsafe(sz, 0, fr.data.length);
                else Bin.putBe32(sz, 0, fr.data.length);
                body.write(sz, 0, 4);
                body.write((fr.flags >> 8) & 0xFF);
                body.write(fr.flags & 0xFF);
                body.write(fr.data, 0, fr.data.length);
            }
            byte[] hdr = new byte[10];
            hdr[0] = 'I';
            hdr[1] = 'D';
            hdr[2] = '3';
            hdr[3] = (byte) major;
            hdr[4] = 0;
            hdr[5] = 0;
            putSyncsafe(hdr, 6, body.size());
            os.write(hdr);
            body.writeTo(os);
            Bin.copyRest(f, t.audioStart, os);
        }
    }

    private static void replace(List<Frame> frames, int major, String value, String id) {
        remove(frames, id);
        if (!value.trim().isEmpty()) frames.add(textFrame(id, value, major));
    }

    private static void remove(List<Frame> frames, String... ids) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            for (String id : ids) {
                if (frames.get(i).id.equals(id)) {
                    frames.remove(i);
                    break;
                }
            }
        }
    }

    private static Frame textFrame(String id, String value, int major) {
        Frame fr = new Frame();
        fr.id = id;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        boolean ascii = true;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 126) ascii = false;
        if (ascii) {
            o.write(0);                                        // ISO-8859-1
            byte[] b = value.getBytes(Bin.LATIN1);
            o.write(b, 0, b.length);
        } else if (major == 4) {
            o.write(3);                                        // UTF-8
            byte[] b = value.getBytes(Bin.UTF8);
            o.write(b, 0, b.length);
        } else {
            o.write(1);                                        // UTF-16 with BOM
            o.write(0xFF);
            o.write(0xFE);
            byte[] b = value.getBytes(java.nio.charset.Charset.forName("UTF-16LE"));
            o.write(b, 0, b.length);
        }
        fr.data = o.toByteArray();
        return fr;
    }

    private static Frame apicFrame(byte[] image) {
        Frame fr = new Frame();
        fr.id = "APIC";
        ImageInfo info = ImageInfo.probe(image);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        byte[] mime = info.mime.getBytes(Bin.LATIN1);
        o.write(mime, 0, mime.length);
        o.write(0);
        o.write(3);                                            // front cover
        o.write(0);                                            // empty description
        o.write(image, 0, image.length);
        fr.data = o.toByteArray();
        return fr;
    }

    // ---------- helpers ----------

    private static String text(byte[] d) {
        if (d.length < 2) return "";
        int enc = d[0] & 0xFF;
        String s;
        try {
            switch (enc) {
                case 1: s = decodeUtf16(d, 1); break;
                case 2: s = new String(d, 1, d.length - 1, "UTF-16BE"); break;
                case 3: s = new String(d, 1, d.length - 1, Bin.UTF8); break;
                default: s = new String(d, 1, d.length - 1, Bin.LATIN1); break;
            }
        } catch (java.io.UnsupportedEncodingException ex) {
            s = new String(d, 1, d.length - 1, Bin.LATIN1);
        }
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
    }

    private static String decodeUtf16(byte[] d, int off) {
        boolean le = true;
        if (d.length - off >= 2) {
            if ((d[off] & 0xFF) == 0xFE && (d[off + 1] & 0xFF) == 0xFF) {
                le = false;
                off += 2;
            } else if ((d[off] & 0xFF) == 0xFF && (d[off + 1] & 0xFF) == 0xFE) {
                off += 2;
            }
        }
        return new String(d, off, d.length - off,
                java.nio.charset.Charset.forName(le ? "UTF-16LE" : "UTF-16BE"));
    }

    /** Picture bytes of an APIC frame, or null. */
    private static byte[] apicImage(byte[] d) {
        if (d.length < 4) return null;
        int enc = d[0] & 0xFF;
        int p = 1;
        while (p < d.length && d[p] != 0) p++;                 // mime
        p += 2;                                                // its terminator + picture type
        if (p >= d.length) return null;
        if (enc == 1 || enc == 2) {
            while (p + 1 < d.length && !(d[p] == 0 && d[p + 1] == 0)) p += 2;
            p += 2;
        } else {
            while (p < d.length && d[p] != 0) p++;
            p += 1;
        }
        return p < d.length ? Arrays.copyOfRange(d, p, d.length) : null;
    }

    private static byte[] picToApic(byte[] d) {
        if (d.length < 6) return null;
        int enc = d[0] & 0xFF;
        String fmt = new String(d, 1, 3, Bin.LATIN1).toUpperCase(java.util.Locale.ROOT);
        String mime = fmt.equals("PNG") ? "image/png" : "image/jpeg";
        int p = 5;
        if (enc == 1 || enc == 2) {
            while (p + 1 < d.length && !(d[p] == 0 && d[p + 1] == 0)) p += 2;
            p += 2;
        } else {
            while (p < d.length && d[p] != 0) p++;
            p += 1;
        }
        if (p >= d.length) return null;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        byte[] m = mime.getBytes(Bin.LATIN1);
        o.write(m, 0, m.length);
        o.write(0);
        o.write(d[4]);
        o.write(0);
        o.write(d, p, d.length - p);
        return o.toByteArray();
    }

    private static int syncsafe(byte[] b, int o) {
        return ((b[o] & 0x7F) << 21) | ((b[o + 1] & 0x7F) << 14) | ((b[o + 2] & 0x7F) << 7) | (b[o + 3] & 0x7F);
    }

    private static void putSyncsafe(byte[] b, int o, int v) {
        b[o] = (byte) ((v >> 21) & 0x7F);
        b[o + 1] = (byte) ((v >> 14) & 0x7F);
        b[o + 2] = (byte) ((v >> 7) & 0x7F);
        b[o + 3] = (byte) (v & 0x7F);
    }

    private static byte[] deunsync(byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(b.length);
        for (int i = 0; i < b.length; i++) {
            o.write(b[i]);
            if ((b[i] & 0xFF) == 0xFF && i + 1 < b.length && b[i + 1] == 0) i++;
        }
        return o.toByteArray();
    }
}
