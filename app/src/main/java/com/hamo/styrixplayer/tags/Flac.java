package com.hamo.styrixplayer.tags;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** FLAC: metadata blocks (STREAMINFO, VORBIS_COMMENT, PICTURE ...) in front of the audio frames. */
final class Flac {

    private static final class Block {
        int type;
        byte[] data;

        Block(int type, byte[] data) {
            this.type = type;
            this.data = data;
        }
    }

    private Flac() {}

    static boolean matches(byte[] head) {
        return Bin.startsWith(head, "fLaC");
    }

    private static long readBlocks(RandomAccessFile f, List<Block> out) throws IOException {
        f.seek(4);
        boolean last;
        do {
            int h = f.read();
            if (h < 0) throw new IOException("Truncated FLAC");
            last = (h & 0x80) != 0;
            byte[] len = Bin.readFully(f, 3);
            int n = Bin.be24(len, 0);
            out.add(new Block(h & 0x7F, Bin.readFully(f, n)));
        } while (!last);
        return f.getFilePointer();
    }

    static TagData read(File file) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            List<Block> blocks = new ArrayList<>();
            readBlocks(f, blocks);
            TagData d = new TagData();
            for (Block b : blocks) {
                if (b.type == 4) {
                    VorbisComments.parse(b.data, 0).toTagData(d);
                } else if (b.type == 6 && d.cover == null) {
                    d.cover = Picture.parse(b.data).data;
                }
            }
            return d;
        }
    }

    static void write(File in, File out, TagData e) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(in, "r");
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16)) {
            List<Block> blocks = new ArrayList<>();
            long audioStart = readBlocks(f, blocks);

            VorbisComments vc = null;
            List<Block> keep = new ArrayList<>();
            List<Block> pictures = new ArrayList<>();
            for (Block b : blocks) {
                if (b.type == 4) vc = VorbisComments.parse(b.data, 0);
                else if (b.type == 6) pictures.add(b);
                else if (b.type != 1) keep.add(b);              // padding is dropped
            }
            if (vc == null) {
                vc = new VorbisComments();
                vc.vendor = "Styrix Player";
            }
            vc.applyEdit(e);
            if (e.coverMode != TagData.CoverMode.KEEP) {
                pictures.clear();
                if (e.coverMode == TagData.CoverMode.SET && e.cover != null) {
                    pictures.add(new Block(6, Picture.of(e.cover).build()));
                }
            }

            List<Block> all = new ArrayList<>(keep);            // STREAMINFO is first in keep
            all.add(new Block(4, vc.build()));
            all.addAll(pictures);

            os.write('f');
            os.write('L');
            os.write('a');
            os.write('C');
            for (int i = 0; i < all.size(); i++) {
                Block b = all.get(i);
                if (b.data.length >= (1 << 24)) throw new IOException("Metadata block too large");
                os.write(b.type | (i == all.size() - 1 ? 0x80 : 0));
                os.write((b.data.length >> 16) & 0xFF);
                os.write((b.data.length >> 8) & 0xFF);
                os.write(b.data.length & 0xFF);
                os.write(b.data);
            }
            Bin.copyRest(f, audioStart, os);
        }
    }
}
