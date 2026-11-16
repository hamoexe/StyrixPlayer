package com.hamo.styrixplayer.tags;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The "KEY=value" comment list used by FLAC, Ogg Vorbis and Opus. */
final class VorbisComments {
    String vendor = "";
    final List<String[]> entries = new ArrayList<>();   // {KEY, value}

    static VorbisComments parse(byte[] b, int off) throws IOException {
        VorbisComments vc = new VorbisComments();
        try {
            int p = off;
            int vlen = (int) Bin.le32(b, p);
            p += 4;
            vc.vendor = new String(b, p, vlen, Bin.UTF8);
            p += vlen;
            long count = Bin.le32(b, p);
            p += 4;
            for (long i = 0; i < count; i++) {
                int len = (int) Bin.le32(b, p);
                p += 4;
                String s = new String(b, p, len, Bin.UTF8);
                p += len;
                int eq = s.indexOf('=');
                if (eq > 0) vc.entries.add(new String[]{s.substring(0, eq), s.substring(eq + 1)});
            }
        } catch (RuntimeException e) {
            throw new IOException("Bad comment block");
        }
        return vc;
    }

    byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] v = vendor.getBytes(Bin.UTF8);
        Bin.writeLe32(out, v.length);
        out.write(v, 0, v.length);
        Bin.writeLe32(out, entries.size());
        for (String[] e : entries) {
            byte[] s = (e[0] + "=" + e[1]).getBytes(Bin.UTF8);
            Bin.writeLe32(out, s.length);
            out.write(s, 0, s.length);
        }
        return out.toByteArray();
    }

    String get(String key) {
        for (String[] e : entries) if (e[0].equalsIgnoreCase(key)) return e[1];
        return null;
    }

    void remove(String key) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i)[0].equalsIgnoreCase(key)) entries.remove(i);
        }
    }

    /** Replaces every entry with that key; an empty value just removes it. */
    void set(String key, String value) {
        remove(key);
        if (value != null && !value.isEmpty()) entries.add(new String[]{key.toUpperCase(Locale.ROOT), value});
    }

    /** Fills the text fields of a read result. */
    void toTagData(TagData d) {
        d.title = get("TITLE");
        d.artist = get("ARTIST");
        d.album = get("ALBUM");
        d.albumArtist = get("ALBUMARTIST");
        if (d.albumArtist == null) d.albumArtist = get("ALBUM ARTIST");
        String date = get("DATE");
        d.year = date != null ? date : get("YEAR");
        String tn = get("TRACKNUMBER");
        String total = get("TRACKTOTAL");
        if (total == null) total = get("TOTALTRACKS");
        d.track = tn != null && total != null && tn.indexOf('/') < 0 ? tn + "/" + total : tn;
        d.genre = get("GENRE");
    }

    /** Applies the text part of an edit. */
    void applyEdit(TagData e) {
        if (e.title != null) set("TITLE", e.title);
        if (e.artist != null) set("ARTIST", e.artist);
        if (e.album != null) set("ALBUM", e.album);
        if (e.albumArtist != null) {
            remove("ALBUM ARTIST");
            set("ALBUMARTIST", e.albumArtist);
        }
        if (e.year != null) {
            remove("YEAR");
            set("DATE", e.year);
        }
        if (e.track != null) {
            remove("TRACKNUMBER");
            remove("TRACKTOTAL");
            remove("TOTALTRACKS");
            String t = e.track.trim();
            int slash = t.indexOf('/');
            if (slash >= 0) {
                set("TRACKNUMBER", t.substring(0, slash).trim());
                set("TRACKTOTAL", t.substring(slash + 1).trim());
            } else {
                set("TRACKNUMBER", t);
            }
        }
        if (e.genre != null) set("GENRE", e.genre);
    }
}
