package com.hamo.styrixplayer.ui;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Library;
import com.hamo.styrixplayer.data.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates line-synced LRC or word-synced Enhanced LRC. In either mode TAP stamps the next
 * item from the current song position. The list also shows the lyric currently under playback.
 */
public class LyricsSyncActivity extends BaseActivity {

    public static final String EXTRA_SONG_ID = "song_id";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_TEXT = "text";

    /** Tap reaction compensation: place the stamp slightly before the physical tap. */
    private static final long REACTION_MS = 150;

    private static final Pattern STAMP = Pattern.compile("^\\s*\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern ANY_TAG = Pattern.compile("^\\s*\\[[a-zA-Z]+:[^\\]]*]\\s*$");
    private static final Pattern WORD_STAMP = Pattern.compile("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static final Pattern WORD = Pattern.compile("\\S+");

    private static final class WordToken {
        final String before;
        final String text;
        long time = -1;

        WordToken(String before, String text, long time) {
            this.before = before;
            this.text = text;
            this.time = time;
        }
    }

    private static final class Row {
        String trailing = "";
        final List<WordToken> words = new ArrayList<>();
        long time = -1;

        Row(long time) {
            this.time = time;
        }

        String text() {
            StringBuilder out = new StringBuilder();
            for (WordToken word : words) out.append(word.before).append(word.text);
            return out.append(trailing).toString();
        }

        boolean isEmpty() {
            return text().trim().isEmpty();
        }

        int timedWords() {
            int n = 0;
            for (WordToken word : words) if (word.time >= 0) n++;
            return n;
        }

        int firstUntimedWord(int from) {
            for (int i = Math.max(0, from); i < words.size(); i++) {
                if (words.get(i).time < 0) return i;
            }
            return -1;
        }

        long firstWordTime() {
            long first = Long.MAX_VALUE;
            for (WordToken word : words) if (word.time >= 0) first = Math.min(first, word.time);
            return first == Long.MAX_VALUE ? -1 : first;
        }

        void setPlainText(String value) {
            words.clear();
            trailing = "";
            Matcher m = WORD.matcher(value == null ? "" : value);
            int cursor = 0;
            String source = value == null ? "" : value;
            while (m.find()) {
                words.add(new WordToken(source.substring(cursor, m.start()), m.group(), -1));
                cursor = m.end();
            }
            trailing = source.substring(cursor);
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private int next = 0;
    private int wordRow = 0;
    private int wordIndex = 0;
    private boolean wordMode = true;
    private boolean autoNextLine = true;

    private final Handler ui = new Handler(Looper.getMainLooper());
    @Nullable private MediaPlayer player;
    private boolean prepared;
    private boolean seeking;
    private boolean dirty;

    private RecyclerView list;
    private Adapter adapter;
    private TextView pos, dur, wordStatus, hint;
    private TextView previewTitle, wordPreview, lastTime, nextWord;
    private SeekBar seek;
    private MaterialButton play, mode, tapButton;
    private MaterialButton undoWordButton, skipWordButton, autoNextButton;
    private View wordPanel, wordActions, lineActions;
    private long playheadMs;
    private int activeRow = -1;
    private int activeWord = -1;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (player != null && prepared && !seeking) {
                int p = player.getCurrentPosition();
                playheadMs = p;
                seek.setProgress(p);
                pos.setText(clock(p));
                updatePlaybackIndicator(p);
            }
            ui.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lyrics_sync);
        padSystemBars(findViewById(R.id.ls_root), true, true);

        final long id = getIntent().getLongExtra(EXTRA_SONG_ID, -1);
        final String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name != null) ((TextView) findViewById(R.id.ls_title)).setText(name);
        parse(getIntent().getStringExtra(EXTRA_TEXT));
        if (rows.isEmpty()) {
            Toast.makeText(this, "Add some lyric lines first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        pos = findViewById(R.id.ls_pos);
        dur = findViewById(R.id.ls_dur);
        seek = findViewById(R.id.ls_seek);
        play = findViewById(R.id.ls_play);
        list = findViewById(R.id.ls_list);
        wordStatus = findViewById(R.id.ls_word_status);
        hint = findViewById(R.id.ls_hint);
        mode = findViewById(R.id.ls_mode);
        tapButton = findViewById(R.id.ls_tap);
        previewTitle = findViewById(R.id.ls_preview_title);
        wordPreview = findViewById(R.id.ls_word_preview);
        lastTime = findViewById(R.id.ls_last_time);
        nextWord = findViewById(R.id.ls_next_word);
        undoWordButton = findViewById(R.id.ls_undo_word);
        skipWordButton = findViewById(R.id.ls_skip_word);
        autoNextButton = findViewById(R.id.ls_auto_next);
        wordPanel = findViewById(R.id.ls_word_panel);
        wordActions = findViewById(R.id.ls_word_actions);
        lineActions = findViewById(R.id.ls_line_actions);

        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
        moveNextToFirstUntimed();
        findNextWord(0, 0);
        updateModeUi();

        findViewById(R.id.ls_back).setOnClickListener(v -> confirmClose());
        findViewById(R.id.ls_done).setOnClickListener(v -> finishWithResult());
        tapButton.setOnClickListener(v -> tap());
        findViewById(R.id.ls_undo).setOnClickListener(v -> undo());
        findViewById(R.id.ls_gap).setOnClickListener(v -> addGap());
        undoWordButton.setOnClickListener(v -> undoWord());
        skipWordButton.setOnClickListener(v -> skipWord());
        autoNextButton.setOnClickListener(v -> {
            autoNextLine = !autoNextLine;
            if (autoNextLine) advanceWordCursor();
            updateAutoNextUi();
            updateStatus();
            updateWordPanel();
            adapter.notifyDataSetChanged();
            scrollToCursor();
        });
        findViewById(R.id.ls_back5).setOnClickListener(v -> skip(-5000));
        findViewById(R.id.ls_fwd5).setOnClickListener(v -> skip(5000));
        play.setOnClickListener(v -> togglePlay());
        mode.setOnClickListener(v -> {
            wordMode = !wordMode;
            if (wordMode) findNextWord(0, 0);
            updateModeUi();
            adapter.notifyDataSetChanged();
            scrollToCursor();
        });
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) pos.setText(clock(p));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (player != null && prepared) {
                    player.seekTo(s.getProgress());
                    playheadMs = s.getProgress();
                    updatePlaybackIndicator(playheadMs);
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmClose();
            }
        });

        play.setEnabled(false);
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Library.ensureLoaded(app);
            final Song s = Library.get(id);
            runOnUiThread(() -> openPlayer(s));
        }).start();
    }

    private void openPlayer(@Nullable Song s) {
        if (s == null || isDestroyed()) {
            if (!isDestroyed()) Toast.makeText(this, "Couldn't open the song", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(this, s.uri);
            mp.setOnPreparedListener(m -> {
                prepared = true;
                seek.setMax(m.getDuration());
                dur.setText(clock(m.getDuration()));
                play.setEnabled(true);
                updatePlaybackIndicator(0);
            });
            mp.setOnCompletionListener(m -> {
                play.setText("Play");
                playheadMs = m.getDuration();
                updatePlaybackIndicator(playheadMs);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Toast.makeText(this, "Playback error", Toast.LENGTH_LONG).show();
                return true;
            });
            player = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't play the song: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        ui.removeCallbacks(tick);
        if (player != null && prepared && player.isPlaying()) {
            player.pause();
            play.setText("Play");
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(tick);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    // ---------- input LRC / Enhanced LRC ----------

    private void parse(@Nullable String source) {
        if (source == null) return;
        for (String raw : source.split("\\r?\\n")) {
            String s = raw.trim();
            if (s.isEmpty() || ANY_TAG.matcher(s).matches()) continue;

            long lineTime = -1;
            Matcher lineStamp = STAMP.matcher(s);
            int textStart = 0;
            if (lineStamp.find()) {
                lineTime = toMs(lineStamp.group(1), lineStamp.group(2), lineStamp.group(3));
                textStart = lineStamp.end();
                // Ignore additional leading timestamps, retaining the first line time as before.
                while (textStart < s.length()) {
                    Matcher more = STAMP.matcher(s);
                    more.region(textStart, s.length());
                    if (!more.lookingAt()) break;
                    textStart = more.end();
                }
            }

            String body = s.substring(textStart).trim();
            if (body.isEmpty() && lineTime < 0) continue;
            Row row = new Row(lineTime);
            parseWordBody(row, body);
            if (row.time < 0) row.time = row.firstWordTime();
            if (!row.isEmpty() || row.time >= 0) rows.add(row);
        }
    }

    /** Word markers start the text segment that follows them, as defined by Enhanced LRC. */
    private void parseWordBody(Row row, String body) {
        Matcher matcher = WORD_STAMP.matcher(body);
        List<Integer> markerStarts = new ArrayList<>();
        List<Integer> markerEnds = new ArrayList<>();
        List<Long> markerTimes = new ArrayList<>();
        while (matcher.find()) {
            markerStarts.add(matcher.start());
            markerEnds.add(matcher.end());
            markerTimes.add(toMs(matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        if (markerStarts.isEmpty()) {
            row.setPlainText(body);
            return;
        }

        StringBuilder plain = new StringBuilder();
        Map<Integer, Long> timesByWordStart = new HashMap<>();
        plain.append(body, 0, markerStarts.get(0));
        for (int i = 0; i < markerStarts.size(); i++) {
            int chunkStart = markerEnds.get(i);
            int chunkEnd = i + 1 < markerStarts.size() ? markerStarts.get(i + 1) : body.length();
            String chunk = body.substring(chunkStart, chunkEnd);
            int plainStart = plain.length();
            plain.append(chunk);
            Matcher firstWord = WORD.matcher(chunk);
            if (firstWord.find()) {
                timesByWordStart.put(plainStart + firstWord.start(), markerTimes.get(i));
            }
        }

        row.words.clear();
        Matcher words = WORD.matcher(plain);
        int cursor = 0;
        while (words.find()) {
            Long time = timesByWordStart.get(words.start());
            row.words.add(new WordToken(plain.substring(cursor, words.start()), words.group(),
                    time == null ? -1 : time));
            cursor = words.end();
        }
        row.trailing = plain.substring(cursor);
    }

    private static long toMs(String m, String s, @Nullable String f) {
        long ms = Long.parseLong(m) * 60000L + Long.parseLong(s) * 1000L;
        if (f != null) {
            String frac = (f + "00").substring(0, 3);
            ms += Long.parseLong(frac);
        }
        return ms;
    }

    private static String stamp(long ms) {
        long m = ms / 60000, s = (ms / 1000) % 60, cs = (ms % 1000) / 10;
        return String.format(Locale.US, "%02d:%02d.%02d", m, s, cs);
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private String buildLrc() {
        List<Row> timed = new ArrayList<>();
        for (Row row : rows) {
            if (row.time < 0) row.time = row.firstWordTime();
            if (row.time >= 0) timed.add(row);
        }
        Collections.sort(timed, (a, b) -> Long.compare(a.time, b.time));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < timed.size(); i++) {
            Row row = timed.get(i);
            out.append('[').append(stamp(row.time)).append(']');
            int lastTimedWord = -1;
            boolean allWordsTimed = !row.words.isEmpty();
            for (int w = 0; w < row.words.size(); w++) {
                WordToken word = row.words.get(w);
                out.append(word.before);
                if (word.time >= 0) {
                    out.append('<').append(stamp(word.time)).append('>');
                    lastTimedWord = w;
                } else {
                    allWordsTimed = false;
                }
                out.append(word.text);
            }
            out.append(row.trailing);

            // A closing Enhanced-LRC marker gives the final word a useful duration instead
            // of relying on the parser's short fallback duration.
            if (allWordsTimed && lastTimedWord == row.words.size() - 1) {
                long lastWordTime = row.words.get(lastTimedWord).time;
                long endTime = lastWordTime + 700;
                if (i + 1 < timed.size() && timed.get(i + 1).time > lastWordTime) {
                    endTime = Math.min(endTime, timed.get(i + 1).time);
                }
                if (endTime <= lastWordTime) endTime = lastWordTime + 500;
                out.append('<').append(stamp(endTime)).append('>');
            }
            out.append('\n');
        }
        return out.toString().trim();
    }

    // ---------- playback position and live lyric indicator ----------

    private int findActiveRow(long position) {
        int found = -1;
        long best = Long.MIN_VALUE;
        for (int i = 0; i < rows.size(); i++) {
            long time = rows.get(i).time;
            if (time >= 0 && time <= position && time >= best) {
                best = time;
                found = i;
            }
        }
        return found;
    }

    private int findActiveWord(int rowIndex, long position) {
        if (rowIndex < 0 || rowIndex >= rows.size()) return -1;
        List<WordToken> words = rows.get(rowIndex).words;
        int found = -1;
        long best = Long.MIN_VALUE;
        for (int i = 0; i < words.size(); i++) {
            long time = words.get(i).time;
            if (time >= 0 && time <= position && time >= best) {
                best = time;
                found = i;
            }
        }
        return found;
    }

    private void updatePlaybackIndicator(long position) {
        int oldRow = activeRow;
        int oldWord = activeWord;
        activeRow = findActiveRow(position);
        activeWord = findActiveWord(activeRow, position);
        playheadMs = position;

        if (adapter != null) {
            if (oldRow >= 0 && oldRow < rows.size()) adapter.notifyItemChanged(oldRow);
            if (activeRow >= 0 && activeRow < rows.size() && activeRow != oldRow) {
                adapter.notifyItemChanged(activeRow);
            } else if (activeRow == oldRow && activeWord != oldWord && activeRow >= 0) {
                adapter.notifyItemChanged(activeRow);
            }
        }
        if (player != null && player.isPlaying() && activeRow >= 0 && activeRow != oldRow) {
            list.smoothScrollToPosition(activeRow);
        }
        if (wordMode && activeRow != oldRow) updateWordPanel();
        updateStatus();
    }

    private void updateStatus() {
        if (wordMode) {
            if (activeRow >= 0) {
                wordStatus.setText("NOW PLAYING · line " + (activeRow + 1) + " of " + rows.size());
            } else {
                wordStatus.setText("Word sync · ready when you are");
            }
        } else if (activeRow >= 0) {
            wordStatus.setText("NOW PLAYING · line " + (activeRow + 1) + " of " + rows.size());
        } else {
            wordStatus.setText("Play the song to see the current lyric here");
        }
    }

    private void updateModeUi() {
        mode.setText(wordMode ? "Word timing ✓" : "Line timing ✓");
        tapButton.setText(wordMode ? "Tap word" : "Tap line");
        wordPanel.setVisibility(wordMode ? View.VISIBLE : View.GONE);
        wordActions.setVisibility(wordMode ? View.VISIBLE : View.GONE);
        lineActions.setVisibility(wordMode ? View.GONE : View.VISIBLE);
        if (wordMode) {
            hint.setText("Play the song and tap “Tap word” exactly when each word is heard. The next word is highlighted for you.");
        } else {
            hint.setText("Play the song and press “Tap line” when each lyric line begins. Tap a line for more options.");
        }
        updateAutoNextUi();
        updateStatus();
        updateWordPanel();
    }

    private void updateAutoNextUi() {
        autoNextButton.setText(autoNextLine ? "Auto next line ✓" : "Auto next line off");
    }

    private long latestWordTimestamp() {
        long latest = -1;
        for (Row row : rows) {
            for (WordToken word : row.words) {
                if (word.time > latest) latest = word.time;
            }
        }
        return latest;
    }

    /** Shows the line being edited, highlights the next word, and keeps the last stamp visible. */
    private void updateWordPanel() {
        if (!wordMode || wordPreview == null) return;

        long latest = latestWordTimestamp();
        lastTime.setText(latest >= 0 ? "Last timestamp: " + stamp(latest) : "Last timestamp: —");

        if (wordRow >= rows.size()) {
            previewTitle.setText("Word sync complete");
            wordPreview.setText("Every word has a timestamp.");
            nextWord.setText("All words timed");
            return;
        }

        Row row = rows.get(wordRow);
        String body = row.text();
        previewTitle.setText((activeRow == wordRow ? "Now playing · " : "Current line · ")
                + (wordRow + 1) + " of " + rows.size());

        SpannableString styled = new SpannableString(body.isEmpty() ? "♪  Instrumental gap" : body);
        int target = Math.min(wordIndex, row.words.size() - 1);
        if (target >= 0) {
            int cursor = 0;
            for (int i = 0; i < row.words.size(); i++) {
                WordToken token = row.words.get(i);
                cursor += token.before.length();
                int start = cursor;
                cursor += token.text.length();
                if (i == target && token.time < 0 && start < cursor) {
                    int container = MaterialColors.getColor(wordPreview,
                            com.google.android.material.R.attr.colorPrimaryContainer, 0xFF394C78);
                    int onContainer = MaterialColors.getColor(wordPreview,
                            com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE);
                    styled.setSpan(new BackgroundColorSpan(container), start, cursor,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                    styled.setSpan(new ForegroundColorSpan(onContainer), start, cursor,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
            }
        }
        wordPreview.setText(styled);

        if (wordIndex < row.words.size()) {
            nextWord.setText("Next: " + row.words.get(wordIndex).text);
        } else {
            nextWord.setText("Line complete · tap to continue");
        }
    }

    // ---------- line / word timestamp actions ----------

    private long position() {
        return player != null && prepared ? player.getCurrentPosition() : 0;
    }

    private void tap() {
        if (!prepared) {
            Toast.makeText(this, "The song isn't ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (wordMode) {
            tapWord();
            return;
        }
        if (next >= rows.size()) {
            Toast.makeText(this, "Every line has a time. Press Done.", Toast.LENGTH_SHORT).show();
            return;
        }
        Row row = rows.get(next);
        row.time = Math.max(0, position() - REACTION_MS);
        dirty = true;
        int changed = next;
        next++;
        while (next < rows.size() && rows.get(next).time >= 0) next++;
        adapter.notifyItemChanged(changed);
        adapter.notifyDataSetChanged();
        scrollToCursor();
        updateStatus();
    }

    private void tapWord() {
        advanceWordCursor();
        if (wordRow >= rows.size()) {
            Toast.makeText(this, "Every word has a time. Press Done.", Toast.LENGTH_SHORT).show();
            updateStatus();
            return;
        }
        Row row = rows.get(wordRow);
        if (wordIndex >= row.words.size()) {
            findNextWord(wordRow + 1, 0);
            tapWord();
            return;
        }
        long at = Math.max(0, position() - REACTION_MS);
        WordToken token = row.words.get(wordIndex);
        token.time = at;
        if (wordIndex == 0) row.time = at;
        else if (row.time < 0) row.time = row.firstWordTime();
        dirty = true;
        int changed = wordRow;
        wordIndex++;
        advanceWordCursor();
        adapter.notifyItemChanged(changed);
        if (wordRow < rows.size() && wordRow != changed) adapter.notifyItemChanged(wordRow);
        updateStatus();
        updateWordPanel();
        scrollToCursor();
    }

    private void findNextWord(int startRow, int startWord) {
        wordRow = rows.size();
        wordIndex = 0;
        for (int i = Math.max(0, startRow); i < rows.size(); i++) {
            int from = i == startRow ? startWord : 0;
            int found = rows.get(i).firstUntimedWord(from);
            if (found >= 0) {
                wordRow = i;
                wordIndex = found;
                return;
            }
        }
    }

    private void advanceWordCursor() {
        if (wordRow >= rows.size()) return;
        int found = rows.get(wordRow).firstUntimedWord(wordIndex);
        if (found >= 0) {
            wordIndex = found;
            return;
        }
        if (autoNextLine) {
            findNextWord(wordRow + 1, 0);
        } else {
            // Leave the cursor at the end of this line. The next TAP WORD continues manually.
            wordIndex = rows.get(wordRow).words.size();
        }
    }

    /** Moves past the current word without assigning a timestamp. */
    private void skipWord() {
        advanceWordCursor();
        if (wordRow >= rows.size()) {
            Toast.makeText(this, "There are no more words to skip", Toast.LENGTH_SHORT).show();
            updateWordPanel();
            return;
        }
        Row row = rows.get(wordRow);
        if (wordIndex >= row.words.size()) {
            if (autoNextLine) findNextWord(wordRow + 1, 0);
        } else {
            String skipped = row.words.get(wordIndex).text;
            int nextIndex = row.firstUntimedWord(wordIndex + 1);
            if (nextIndex >= 0) {
                wordIndex = nextIndex;
            } else if (autoNextLine) {
                findNextWord(wordRow + 1, 0);
            } else {
                wordIndex = row.words.size();
            }
            Toast.makeText(this, "Skipped “" + skipped + "”", Toast.LENGTH_SHORT).show();
        }
        updateStatus();
        updateWordPanel();
        adapter.notifyDataSetChanged();
        scrollToCursor();
    }

    private void undo() {
        if (wordMode) {
            undoWord();
            return;
        }
        int i = next - 1;
        while (i >= 0 && rows.get(i).time < 0) i--;
        if (i < 0) return;
        Row row = rows.get(i);
        long back = row.time;
        row.time = -1;
        // Undoing a line in Line timing mode also clears any word stamps on that line,
        // so the line does not remain implicitly timed by its first word.
        for (WordToken word : row.words) word.time = -1;
        dirty = true;
        next = i;
        adapter.notifyDataSetChanged();
        scrollToCursor();
        if (player != null && prepared) player.seekTo((int) Math.max(0, back - 2000));
    }

    private void undoWord() {
        int fromRow = Math.min(wordRow, rows.size() - 1);
        for (int i = fromRow; i >= 0; i--) {
            Row row = rows.get(i);
            int from = i == fromRow && wordIndex > 0
                    ? Math.min(wordIndex - 1, row.words.size() - 1)
                    : row.words.size() - 1;
            for (int j = from; j >= 0; j--) {
                WordToken token = row.words.get(j);
                if (token.time < 0) continue;
                long back = token.time;
                token.time = -1;
                if (j == 0 && row.time == back) row.time = row.firstWordTime();
                dirty = true;
                wordRow = i;
                wordIndex = j;
                adapter.notifyItemChanged(i);
                if (player != null && prepared) player.seekTo((int) Math.max(0, back - 2000));
                updateStatus();
                updateWordPanel();
                scrollToCursor();
                return;
            }
        }
    }

    private void addGap() {
        if (!prepared || wordMode) {
            Toast.makeText(this, wordMode ? "Instrumental gaps are added in Line timing mode" : "The song isn't ready yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Row gap = new Row(Math.max(0, position() - REACTION_MS));
        int at = Math.min(next, rows.size());
        rows.add(at, gap);
        next = at + 1;
        dirty = true;
        adapter.notifyDataSetChanged();
        scrollToCursor();
    }

    private void skip(int deltaMs) {
        if (player == null || !prepared) return;
        int p = Math.max(0, Math.min(player.getDuration(), player.getCurrentPosition() + deltaMs));
        player.seekTo(p);
        seek.setProgress(p);
        pos.setText(clock(p));
        updatePlaybackIndicator(p);
    }

    private void togglePlay() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            play.setText("Play");
        } else {
            player.start();
            play.setText("Pause");
        }
    }

    private void moveNextToFirstUntimed() {
        next = 0;
        while (next < rows.size() && rows.get(next).time >= 0) next++;
    }

    private void scrollToCursor() {
        int target;
        if (wordMode) target = Math.min(rows.size() - 1, Math.max(0, wordRow));
        else target = Math.min(rows.size() - 1, Math.max(0, next));
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(target, dp(72));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void rowMenu(final int index) {
        final Row row = rows.get(index);
        final List<String> labels = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>();
        labels.add("Time this line next"); actions.add(0);
        if (row.time >= 0) {
            labels.add("Play from here"); actions.add(1);
            labels.add("Earlier  (-0.1 s)"); actions.add(2);
            labels.add("Later  (+0.1 s)"); actions.add(3);
            labels.add("Clear line time"); actions.add(4);
        }
        if (!row.isEmpty()) {
            labels.add("Time words from here"); actions.add(7);
            if (row.timedWords() > 0) {
                labels.add("Clear word timings"); actions.add(8);
            }
        }
        labels.add("Edit text"); actions.add(5);
        labels.add("Delete line"); actions.add(6);

        new ThemedDialogBuilder(this)
                .setTitle(row.isEmpty() ? "Instrumental gap" : row.text())
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    switch (actions.get(which)) {
                        case 0:
                            row.time = -1;
                            next = index;
                            dirty = true;
                            wordMode = false;
                            updateModeUi();
                            break;
                        case 1:
                            if (player != null && prepared) {
                                player.seekTo((int) Math.max(0, row.time - 1500));
                                if (!player.isPlaying()) togglePlay();
                            }
                            break;
                        case 2:
                            row.time = Math.max(0, row.time - 100);
                            dirty = true;
                            break;
                        case 3:
                            row.time += 100;
                            dirty = true;
                            break;
                        case 4:
                            row.time = -1;
                            dirty = true;
                            moveNextToFirstUntimed();
                            break;
                        case 5:
                            editText(index);
                            return;
                        case 6:
                            rows.remove(index);
                            dirty = true;
                            if (next > index) next--;
                            if (activeRow == index) activeRow = -1;
                            if (wordRow > index) wordRow--;
                            else if (wordRow == index) findNextWord(index, 0);
                            break;
                        case 7:
                            wordMode = true;
                            findNextWord(index, 0);
                            updateModeUi();
                            break;
                        case 8:
                            long firstWordTime = row.firstWordTime();
                            for (WordToken word : row.words) word.time = -1;
                            if (row.time == firstWordTime) row.time = -1;
                            dirty = true;
                            wordMode = true;
                            findNextWord(index, 0);
                            updateModeUi();
                            break;
                    }
                    adapter.notifyDataSetChanged();
                    scrollToCursor();
                    updateStatus();
                    updateWordPanel();
                })
                .show();
    }

    private void editText(final int index) {
        final EditText edit = new EditText(this);
        edit.setText(rows.get(index).text());
        edit.setSingleLine(false);
        int pad = dp(20);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(edit);
        new ThemedDialogBuilder(this)
                .setTitle("Edit line")
                .setView(box)
                .setPositiveButton("OK", (dialog, which) -> {
                    Row row = rows.get(index);
                    row.setPlainText(edit.getText().toString().replace('\n', ' ').trim());
                    if (row.time < 0) row.time = row.firstWordTime();
                    dirty = true;
                    adapter.notifyItemChanged(index);
                    if (wordMode) findNextWord(index, 0);
                    updateStatus();
                    updateWordPanel();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClose() {
        if (!dirty) {
            finish();
            return;
        }
        new ThemedDialogBuilder(this)
                .setTitle("Discard the timing?")
                .setPositiveButton("Discard", (dialog, which) -> finish())
                .setNegativeButton("Keep working", null)
                .show();
    }

    private void finishWithResult() {
        int untimedLines = 0;
        int untimedWordsInStartedLines = 0;
        for (Row row : rows) {
            if (row.time < 0 && row.firstWordTime() < 0) untimedLines++;
            if (row.timedWords() > 0) {
                for (WordToken word : row.words) if (word.time < 0) untimedWordsInStartedLines++;
            }
        }
        if (untimedLines == rows.size()) {
            Toast.makeText(this, "No line has a time yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (untimedLines > 0 || (wordMode && untimedWordsInStartedLines > 0)) {
            String message = "";
            if (untimedLines > 0) message += untimedLines + (untimedLines == 1 ? " line has" : " lines have") + " no line time. They will be left out.";
            if (wordMode && untimedWordsInStartedLines > 0) {
                if (!message.isEmpty()) message += "\n\n";
                message += untimedWordsInStartedLines + (untimedWordsInStartedLines == 1 ? " word has" : " words have")
                        + " no word time. Remaining text will still be kept in the lyric line.";
            }
            new ThemedDialogBuilder(this)
                    .setTitle("Some timing is incomplete")
                    .setMessage(message + " Continue?")
                    .setPositiveButton("Continue", (dialog, which) -> deliver())
                    .setNegativeButton("Keep timing", null)
                    .show();
            return;
        }
        deliver();
    }

    private void deliver() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEXT, buildLrc()));
        finish();
    }

    // ---------- lyric list: current line + current word indicators ----------

    private final class VH extends RecyclerView.ViewHolder {
        final LinearLayout card;
        final TextView label;
        final TextView body;

        VH(LinearLayout card, TextView label, TextView body) {
            super(card);
            this.card = card;
            this.label = label;
            this.body = body;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(11), dp(14), dp(12));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(7);
            card.setLayoutParams(params);

            TextView label = new TextView(parent.getContext());
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            label.setTypeface(null, Typeface.BOLD);
            label.setLetterSpacing(0.035f);
            card.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView body = new TextView(parent.getContext());
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            body.setGravity(Gravity.START);
            body.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyParams.topMargin = dp(4);
            card.addView(body, bodyParams);
            return new VH(card, label, body);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int index) {
            Row row = rows.get(index);
            boolean playingLine = index == activeRow;
            boolean nextLine = !wordMode && index == next && row.time < 0;
            boolean nextWord = wordMode && index == wordRow;

            int primary = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorPrimary, Color.CYAN);
            int onPrimary = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE);
            int primaryContainer = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorPrimaryContainer, 0xFF33445C);
            int secondaryContainer = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorSecondaryContainer, 0xFF3C4050);
            int onSurface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
            int variant = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY);
            int surface = MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorSurfaceContainerLow, 0xFF25252B);

            int background = playingLine ? primaryContainer : nextWord ? secondaryContainer : surface;
            holder.card.setBackground(roundRect(background, dp(16)));
            holder.label.setTextColor(playingLine || nextWord ? onPrimary : variant);
            holder.body.setTextColor(onSurface);

            long rowTime = row.time >= 0 ? row.time : row.firstWordTime();
            String status = rowTime >= 0 ? stamp(rowTime) : "--:--.--";
            if (playingLine) status += "   •   NOW PLAYING";
            if (nextLine) status += "   •   NEXT LINE";
            if (nextWord) status += "   •   Word " + (wordIndex + 1) + " of " + Math.max(1, row.words.size());
            holder.label.setText(status);
            holder.body.setText(bodyWithWordHighlight(row, index, primary, onPrimary));
            holder.card.setOnClickListener(v -> {
                int p = holder.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) rowMenu(p);
            });
            holder.card.setContentDescription((playingLine ? "Currently playing. " : "") + row.text());
        }

        private GradientDrawable roundRect(int color, int radius) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(color);
            drawable.setCornerRadius(radius);
            return drawable;
        }

        private SpannableString bodyWithWordHighlight(Row row, int rowIndex, int primary, int onPrimary) {
            String text = row.text().isEmpty() ? "♪  Instrumental gap" : row.text();
            SpannableString span = new SpannableString(text);
            if (rowIndex != activeRow || activeWord < 0 || activeWord >= row.words.size()) return span;

            int cursor = 0;
            for (int i = 0; i < row.words.size(); i++) {
                WordToken word = row.words.get(i);
                cursor += word.before.length();
                int start = cursor;
                cursor += word.text.length();
                if (i == activeWord && start < cursor && word.time >= 0) {
                    span.setSpan(new BackgroundColorSpan(primary), start, cursor,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                    span.setSpan(new ForegroundColorSpan(onPrimary), start, cursor,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
            }
            return span;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
