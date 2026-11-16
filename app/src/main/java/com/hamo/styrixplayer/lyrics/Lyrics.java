package com.hamo.styrixplayer.lyrics;

import java.util.ArrayList;
import java.util.List;

/** What the player shows for one song. */
public final class Lyrics {
    public enum State { SYNCED, PLAIN, INSTRUMENTAL, NOT_FOUND, ERROR }

    public final State state;
    /** Timed lines for SYNCED, untimed (time = -1) for PLAIN, empty otherwise. */
    public final List<LrcParser.Line> lines;
    /** Shift applied when showing: positive = lyrics appear later. */
    public final long offsetMs;
    /** Why a lookup failed (ERROR state only). */
    public final String message;

    public Lyrics(State state, List<LrcParser.Line> lines, long offsetMs) {
        this(state, lines, offsetMs, "");
    }

    private Lyrics(State state, List<LrcParser.Line> lines, long offsetMs, String message) {
        this.state = state;
        this.lines = lines;
        this.offsetMs = offsetMs;
        this.message = message;
    }

    public static Lyrics of(State state) {
        return new Lyrics(state, new ArrayList<LrcParser.Line>(), 0);
    }

    public static Lyrics error(String message) {
        return new Lyrics(State.ERROR, new ArrayList<LrcParser.Line>(), 0,
                message == null ? "" : message);
    }
}
