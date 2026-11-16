package com.hamo.styrixplayer.tags;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** The FLAC "picture" structure (also used, base64-encoded, for covers in Ogg comments). */
final class Picture {
    int type = 3;            // front cover
    String mime = "image/jpeg";
    byte[] data = new byte[0];

    static Picture of(byte[] image) {
        Picture p = new Picture();
        p.data = image;
        p.mime = ImageInfo.probe(image).mime;
        return p;
    }

    byte[] build() {
        ImageInfo info = ImageInfo.probe(data);
        byte[] m = mime.getBytes(Bin.LATIN1);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        be32(o, type);
        be32(o, m.length);
        o.write(m, 0, m.length);
        be32(o, 0);                      // description length
        be32(o, info.width);
        be32(o, info.height);
        be32(o, info.depth);
        be32(o, 0);                      // indexed colours
        be32(o, data.length);
        o.write(data, 0, data.length);
        return o.toByteArray();
    }

    static Picture parse(byte[] b) throws IOException {
        try {
            Picture p = new Picture();
            int o = 0;
            p.type = (int) Bin.be32(b, o);
            o += 4;
            int ml = (int) Bin.be32(b, o);
            o += 4;
            p.mime = new String(b, o, ml, Bin.LATIN1);
            o += ml;
            int dl = (int) Bin.be32(b, o);
            o += 4 + dl;
            o += 16;                      // width, height, depth, colours
            int len = (int) Bin.be32(b, o);
            o += 4;
            p.data = java.util.Arrays.copyOfRange(b, o, o + len);
            return p;
        } catch (RuntimeException e) {
            throw new IOException("Bad picture block");
        }
    }

    private static void be32(ByteArrayOutputStream o, long v) {
        o.write((int) (v >>> 24) & 0xFF);
        o.write((int) (v >>> 16) & 0xFF);
        o.write((int) (v >>> 8) & 0xFF);
        o.write((int) v & 0xFF);
    }
}
