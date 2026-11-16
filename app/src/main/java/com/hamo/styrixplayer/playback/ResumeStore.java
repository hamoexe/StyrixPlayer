package com.hamo.styrixplayer.playback;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;

import java.util.ArrayList;
import java.util.List;

/** Remembers the queue, current song and position so the app can pick up where you left off. */
public final class ResumeStore {
    private static final String PREFS = "resume";

    public static final class State {
        public final List<Long> ids;
        public final long currentId;
        public final long position;
        public final boolean shuffle;
        public final int repeat;

        State(List<Long> ids, long currentId, long position, boolean shuffle, int repeat) {
            this.ids = ids;
            this.currentId = currentId;
            this.position = position;
            this.shuffle = shuffle;
            this.repeat = repeat;
        }
    }

    private ResumeStore() {}

    public static void save(Context c, Player p) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        int n = p.getMediaItemCount();
        if (n == 0) {
            e.clear().apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append(p.getMediaItemAt(i).mediaId);
        }
        MediaItem cur = p.getCurrentMediaItem();
        e.putString("ids", sb.toString())
                .putString("cur", cur != null ? cur.mediaId : "")
                .putLong("pos", Math.max(0, p.getCurrentPosition()))
                .putBoolean("shuffle", p.getShuffleModeEnabled())
                .putInt("repeat", p.getRepeatMode())
                .apply();
    }

    @Nullable
    public static State load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString("ids", "");
        if (raw == null || raw.isEmpty()) return null;
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                ids.add(Long.parseLong(part));
            } catch (NumberFormatException ignored) {
            }
        }
        if (ids.isEmpty()) return null;
        long cur = -1;
        try {
            cur = Long.parseLong(sp.getString("cur", "-1"));
        } catch (NumberFormatException ignored) {
        }
        return new State(ids, cur, sp.getLong("pos", 0),
                sp.getBoolean("shuffle", false), sp.getInt("repeat", Player.REPEAT_MODE_OFF));
    }
}
