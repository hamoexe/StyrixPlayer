package com.hamo.styrixplayer.tags;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** M4A / MP4: tags live in moov/udta/meta/ilst. Rewrites moov and fixes the chunk offsets. */
final class Mp4Tags {

    private static final String NAM = "©nam", ART = "©ART", ALB = "©alb",
            DAY = "©day", GEN = "©gen";

    private static final class Atom {
        String type;
        byte[] bytes;               // the complete atom, header included
        int header = 8;

        byte[] payload() {
            byte[] p = new byte[bytes.length - header];
            System.arraycopy(bytes, header, p, 0, p.length);
            return p;
        }
    }

    private Mp4Tags() {}

    static boolean matches(byte[] head) {
        return head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
    }

    // ---------- atom helpers ----------

    private static List<Atom> children(byte[] b, int start, int end) throws IOException {
        List<Atom> out = new ArrayList<>();
        int pos = start;
        while (pos + 8 <= end) {
            long size = Bin.be32(b, pos);
            int hdr = 8;
            if (size == 1) {
                size = Bin.be64(b, pos + 8);
                hdr = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < hdr || pos + size > end) throw new IOException("Bad MP4 atom");
            Atom a = new Atom();
            a.type = new String(b, pos + 4, 4, Bin.LATIN1);
            a.header = hdr;
            a.bytes = new byte[(int) size];
            System.arraycopy(b, pos, a.bytes, 0, (int) size);
            out.add(a);
            pos += (int) size;
        }
        return out;
    }

    private static Atom make(String type, byte[]... parts) {
        int len = 8;
        for (byte[] p : parts) len += p.length;
        byte[] b = new byte[len];
        Bin.putBe32(b, 0, len);
        byte[] t = type.getBytes(Bin.LATIN1);
        System.arraycopy(t, 0, b, 4, 4);
        int o = 8;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, b, o, p.length);
            o += p.length;
        }
        Atom a = new Atom();
        a.type = type;
        a.bytes = b;
        return a;
    }

    private static byte[] join(List<Atom> atoms) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Atom a : atoms) o.write(a.bytes, 0, a.bytes.length);
        return o.toByteArray();
    }

    private static Atom find(List<Atom> list, String type) {
        for (Atom a : list) if (a.type.equals(type)) return a;
        return null;
    }

    private static final class Top {
        String type;
        long start, size;
    }

    private static List<Top> topLevel(RandomAccessFile f) throws IOException {
        List<Top> out = new ArrayList<>();
        long pos = 0, len = f.length();
        while (pos + 8 <= len) {
            f.seek(pos);
            byte[] h = Bin.readFully(f, 8);
            long size = Bin.be32(h, 0);
            if (size == 1) size = Bin.be64(Bin.readFully(f, 8), 0);
            else if (size == 0) size = len - pos;
            if (size < 8 || pos + size > len) throw new IOException("Bad MP4 layout");
            Top t = new Top();
            t.type = new String(h, 4, 4, Bin.LATIN1);
            t.start = pos;
            t.size = size;
            out.add(t);
            pos += size;
        }
        return out;
    }

    // ---------- read ----------

    static TagData read(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            Top moovT = null;
            for (Top t : topLevel(f)) if (t.type.equals("moov")) moovT = t;
            if (moovT == null) throw new IOException("No moov atom");
            f.seek(moovT.start);
            byte[] moov = Bin.readFully(f, (int) moovT.size);
            TagData d = new TagData();
            Atom ilst = ilstOf(moov);
            if (ilst == null) return d;
            for (Atom item : children(ilst.bytes, ilst.header, ilst.bytes.length)) {
                byte[] val = dataOf(item);
                if (val == null) continue;
                switch (item.type) {
                    case NAM: d.title = utf8(val); break;
                    case ART: d.artist = utf8(val); break;
                    case ALB: d.album = utf8(val); break;
                    case "aART": d.albumArtist = utf8(val); break;
                    case DAY: d.year = utf8(val); break;
                    case GEN: d.genre = utf8(val); break;
                    case "trkn":
                        if (val.length >= 6) {
                            int n = Bin.be16(val, 2), tot = Bin.be16(val, 4);
                            d.track = n == 0 ? null : tot > 0 ? n + "/" + tot : String.valueOf(n);
                        }
                        break;
                    case "covr": d.cover = val; break;
                    default: break;
                }
            }
            return d;
        }
    }

    private static Atom ilstOf(byte[] moov) throws IOException {
        List<Atom> top = children(moov, 8, moov.length);
        Atom udta = find(top, "udta");
        if (udta == null) return null;
        Atom meta = find(children(udta.bytes, udta.header, udta.bytes.length), "meta");
        if (meta == null) return null;
        return find(children(meta.bytes, meta.header + 4, meta.bytes.length), "ilst");
    }

    /** The payload of the first "data" atom inside an item. */
    private static byte[] dataOf(Atom item) throws IOException {
        for (Atom a : children(item.bytes, item.header, item.bytes.length)) {
            if (a.type.equals("data") && a.bytes.length >= 16) {
                byte[] v = new byte[a.bytes.length - 16];
                System.arraycopy(a.bytes, 16, v, 0, v.length);
                return v;
            }
        }
        return null;
    }

    private static String utf8(byte[] b) {
        return new String(b, Bin.UTF8);
    }

    // ---------- write ----------

    static void write(File in, File out, TagData e) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(in, "r");
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            List<Top> tops = topLevel(f);
            Top moovT = null;
            for (Top t : tops) if (t.type.equals("moov")) moovT = t;
            if (moovT == null) throw new IOException("No moov atom");
            if (moovT.size > 64L * 1024 * 1024) throw new IOException("moov too large");

            f.seek(moovT.start);
            byte[] oldMoov = Bin.readFully(f, (int) moovT.size);
            byte[] newMoov = rebuildMoov(oldMoov, e);
            long delta = newMoov.length - oldMoov.length;
            if (delta != 0) patchOffsets(newMoov, moovT.start + moovT.size, delta);

            for (Top t : tops) {
                if (t == moovT) os.write(newMoov);
                else Bin.copyRange(f, t.start, t.size, os);
            }
        }
    }

    private static byte[] rebuildMoov(byte[] moov, TagData e) throws IOException {
        List<Atom> kids = children(moov, 8, moov.length);

        Atom udta = find(kids, "udta");
        List<Atom> udtaKids = udta == null ? new ArrayList<Atom>()
                : children(udta.bytes, udta.header, udta.bytes.length);
        Atom meta = find(udtaKids, "meta");
        Atom hdlr = null;
        List<Atom> items = new ArrayList<>();
        List<Atom> metaOthers = new ArrayList<>();
        if (meta != null) {
            for (Atom a : children(meta.bytes, meta.header + 4, meta.bytes.length)) {
                if (a.type.equals("hdlr")) hdlr = a;
                else if (a.type.equals("ilst")) items = children(a.bytes, a.header, a.bytes.length);
                else metaOthers.add(a);
            }
        }
        if (hdlr == null) hdlr = defaultHdlr();

        if (e.title != null) setText(items, NAM, e.title);
        if (e.artist != null) setText(items, ART, e.artist);
        if (e.album != null) setText(items, ALB, e.album);
        if (e.albumArtist != null) setText(items, "aART", e.albumArtist);
        if (e.year != null) setText(items, DAY, e.year);
        if (e.genre != null) {
            drop(items, "gnre");
            setText(items, GEN, e.genre);
        }
        if (e.track != null) {
            drop(items, "trkn");
            int[] nt = parseTrack(e.track);
            if (nt != null) {
                byte[] p = new byte[8];
                p[2] = (byte) (nt[0] >> 8);
                p[3] = (byte) nt[0];
                p[4] = (byte) (nt[1] >> 8);
                p[5] = (byte) nt[1];
                items.add(item("trkn", 0, p));
            }
        }
        if (e.coverMode != TagData.CoverMode.KEEP) {
            drop(items, "covr");
            if (e.coverMode == TagData.CoverMode.SET && e.cover != null) {
                items.add(item("covr", ImageInfo.isPng(e.cover) ? 14 : 13, e.cover));
            }
        }

        Atom ilst = make("ilst", join(items));
        List<Atom> metaKids = new ArrayList<>();
        metaKids.add(hdlr);
        metaKids.add(ilst);
        metaKids.addAll(metaOthers);
        Atom newMeta = make("meta", new byte[4], join(metaKids));

        List<Atom> newUdtaKids = new ArrayList<>();
        for (Atom a : udtaKids) if (!a.type.equals("meta")) newUdtaKids.add(a);
        newUdtaKids.add(newMeta);
        Atom newUdta = make("udta", join(newUdtaKids));

        List<Atom> newKids = new ArrayList<>();
        boolean placed = false;
        for (Atom a : kids) {
            if (a.type.equals("udta")) {
                newKids.add(newUdta);
                placed = true;
            } else {
                newKids.add(a);
            }
        }
        if (!placed) newKids.add(newUdta);
        return make("moov", join(newKids)).bytes;
    }

    private static Atom defaultHdlr() {
        byte[] p = new byte[25];                               // ver/flags, pre_defined, 'mdir', 'appl', 8 zero, name
        p[8] = 'm';
        p[9] = 'd';
        p[10] = 'i';
        p[11] = 'r';
        p[12] = 'a';
        p[13] = 'p';
        p[14] = 'p';
        p[15] = 'l';
        return make("hdlr", p);
    }

    private static void drop(List<Atom> items, String type) {
        for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).type.equals(type)) items.remove(i);
    }

    private static void setText(List<Atom> items, String type, String value) {
        drop(items, type);
        if (!value.trim().isEmpty()) items.add(item(type, 1, value.getBytes(Bin.UTF8)));
    }

    private static Atom item(String type, int dataType, byte[] payload) {
        byte[] head = new byte[8];                             // version 0, flags = type, locale 0
        head[1] = (byte) (dataType >> 16);
        head[2] = (byte) (dataType >> 8);
        head[3] = (byte) dataType;
        Atom data = make("data", head, payload);
        return make(type, data.bytes);
    }

    private static int[] parseTrack(String s) {
        try {
            String t = s.trim();
            if (t.isEmpty()) return null;
            int slash = t.indexOf('/');
            int n = Integer.parseInt((slash >= 0 ? t.substring(0, slash) : t).trim());
            int total = slash >= 0 && !t.substring(slash + 1).trim().isEmpty()
                    ? Integer.parseInt(t.substring(slash + 1).trim()) : 0;
            return new int[]{n, total};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Adds delta to every chunk offset that points past the old end of moov. */
    private static void patchOffsets(byte[] b, long oldMoovEnd, long delta) throws IOException {
        walk(b, 8, b.length, oldMoovEnd, delta);
    }

    private static void walk(byte[] b, int start, int end, long oldEnd, long delta) throws IOException {
        int pos = start;
        while (pos + 8 <= end) {
            long size = Bin.be32(b, pos);
            if (size < 8 || pos + size > end) return;
            String type = new String(b, pos + 4, 4, Bin.LATIN1);
            int next = pos + (int) size;
            switch (type) {
                case "trak": case "mdia": case "minf": case "stbl":
                    walk(b, pos + 8, next, oldEnd, delta);
                    break;
                case "stco": {
                    long count = Bin.be32(b, pos + 12);
                    for (long i = 0; i < count; i++) {
                        int o = pos + 16 + (int) i * 4;
                        long v = Bin.be32(b, o);
                        if (v >= oldEnd) {
                            long nv = v + delta;
                            if (nv < 0 || nv > 0xFFFFFFFFL) throw new IOException("stco overflow");
                            Bin.putBe32(b, o, nv);
                        }
                    }
                    break;
                }
                case "co64": {
                    long count = Bin.be32(b, pos + 12);
                    for (long i = 0; i < count; i++) {
                        int o = pos + 16 + (int) i * 8;
                        long v = Bin.be64(b, o);
                        if (v >= oldEnd) Bin.putBe64(b, o, v + delta);
                    }
                    break;
                }
                default:
                    break;
            }
            pos = next;
        }
    }
}
