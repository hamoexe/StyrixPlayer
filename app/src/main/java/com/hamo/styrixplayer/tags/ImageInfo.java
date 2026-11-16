package com.hamo.styrixplayer.tags;

/** Just enough JPEG / PNG parsing to know the mime type and size of a cover picture. */
final class ImageInfo {
    final String mime;
    final int width, height, depth;

    private ImageInfo(String mime, int w, int h, int depth) {
        this.mime = mime;
        this.width = w;
        this.height = h;
        this.depth = depth;
    }

    static ImageInfo probe(byte[] b) {
        if (b.length > 24 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            int w = (int) Bin.be32(b, 16), h = (int) Bin.be32(b, 20);
            int bitDepth = b[24] & 0xFF, colorType = b[25] & 0xFF;
            int channels = colorType == 2 ? 3 : colorType == 6 ? 4 : colorType == 4 ? 2 : 1;
            return new ImageInfo("image/png", w, h, bitDepth * channels);
        }
        if (b.length > 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            int i = 2;
            while (i + 9 < b.length) {
                if ((b[i] & 0xFF) != 0xFF) {
                    i++;
                    continue;
                }
                int m = b[i + 1] & 0xFF;
                if (m == 0xFF) {
                    i++;
                    continue;
                }
                if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                    i += 2;
                    continue;
                }
                int len = Bin.be16(b, i + 2);
                if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    int precision = b[i + 4] & 0xFF;
                    int h = Bin.be16(b, i + 5), w = Bin.be16(b, i + 7);
                    int comps = b[i + 9] & 0xFF;
                    return new ImageInfo("image/jpeg", w, h, precision * comps);
                }
                i += 2 + len;
            }
            return new ImageInfo("image/jpeg", 0, 0, 24);
        }
        return new ImageInfo("image/jpeg", 0, 0, 24);   // unknown: treat as jpeg
    }

    static boolean isPng(byte[] b) {
        return b.length > 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P';
    }
}
