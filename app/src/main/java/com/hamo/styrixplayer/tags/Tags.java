package com.hamo.styrixplayer.tags;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads and writes the tags inside audio files. Pure Java (no Android classes).
 * write() never touches the input: it produces a complete new file.
 */
public final class Tags {

    public enum Format {
        MP3("MP3"), M4A("M4A / MP4"), FLAC("FLAC"), OGG("Ogg Vorbis / Opus"), UNSUPPORTED("this format");

        public final String label;

        Format(String label) {
            this.label = label;
        }

        public boolean writable() {
            return this != UNSUPPORTED;
        }
    }

    private Tags() {}

    /** Needs the first ~16 bytes of the file. */
    public static Format detect(byte[] head) {
        if (Flac.matches(head)) return Format.FLAC;
        if (OggTags.matches(head)) return Format.OGG;
        if (Mp4Tags.matches(head)) return Format.M4A;
        if (Id3.matches(head)) return Format.MP3;
        return Format.UNSUPPORTED;
    }

    public static Format detect(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] head = new byte[16];
            int n = 0, r;
            while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
            return detect(java.util.Arrays.copyOf(head, n));
        }
    }

    public static TagData read(File f) throws IOException {
        switch (detect(f)) {
            case MP3: return Id3.read(f);
            case M4A: return Mp4Tags.read(f);
            case FLAC: return Flac.read(f);
            case OGG: return OggTags.read(f);
            default: throw new IOException("Tags in this format can't be read");
        }
    }

    public static void write(File in, File out, TagData edit) throws IOException {
        switch (detect(in)) {
            case MP3: Id3.write(in, out, edit); break;
            case M4A: Mp4Tags.write(in, out, edit); break;
            case FLAC: Flac.write(in, out, edit); break;
            case OGG: OggTags.write(in, out, edit); break;
            default: throw new IOException("Tags in this format can't be written");
        }
    }
}
