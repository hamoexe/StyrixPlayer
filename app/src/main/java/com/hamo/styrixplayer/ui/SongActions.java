package com.hamo.styrixplayer.ui;

import android.app.Activity;
import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import java.util.Collections;
import com.hamo.styrixplayer.data.Library;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.session.MediaController;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hamo.styrixplayer.R;
import com.hamo.styrixplayer.data.Db;
import com.hamo.styrixplayer.data.Song;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared actions: long-press menu, add-to-playlist dialog, starting playback from a list. */
public final class SongActions {

    public interface NameCallback {
        void onName(String name);
    }

    private SongActions() {}

    public static void playList(MediaController c, List<Song> list, int index, boolean shuffle) {
        if (list.isEmpty()) return;
        List<MediaItem> items = new ArrayList<>(list.size());
        for (Song s : list) items.add(s.toMediaItem());
        if (shuffle) c.setShuffleModeEnabled(true);
        c.setMediaItems(items, index, 0);
        c.prepare();
        c.play();
    }

    private static void queueSong(Activity a, @Nullable MediaController c, Song song, boolean next) {
        if (c == null) {
            Toast.makeText(a, "Player is still connecting, try again", Toast.LENGTH_SHORT).show();
            return;
        }
        MediaItem item = song.toMediaItem();
        if (c.getMediaItemCount() == 0) {
            c.setMediaItem(item);
            c.prepare();
            c.play();
            return;
        }
        if (next) c.addMediaItem(c.getCurrentMediaItemIndex() + 1, item);
        else c.addMediaItem(item);
        Toast.makeText(a, next ? "Playing next" : "Added to queue", Toast.LENGTH_SHORT).show();
    }

    /**
     * Long-press menu for a song.
     *
     * @param playlistId the playlist being viewed, or -1 when not inside a playlist
     * @param onChanged  called after something changed that lists may need to refresh
     */
    public static void showMenu(Activity a, View anchor, Song song, @Nullable MediaController c,
                                long playlistId, @Nullable Runnable onChanged) {
        ThemedPopupMenu pm = new ThemedPopupMenu(a, anchor);
        Menu m = pm.getMenu();
        boolean fav = Db.get(a).isFavorite(song.id);
        m.add(0, 1, 0, "Play next");
        m.add(0, 2, 1, "Add to queue");
        m.add(0, 3, 2, fav ? "Remove from favorites" : "Add to favorites");
        m.add(0, 4, 3, "Add to playlist…");
        if (playlistId >= 0) m.add(0, 5, 4, "Remove from this playlist");
        m.add(0, 6, 5, "Delete from device");

        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    queueSong(a, c, song, true);
                    break;
                case 2:
                    queueSong(a, c, song, false);
                    break;
                case 3:
                    Db.get(a).toggleFavorite(song.id);
                    com.hamo.styrixplayer.playback.PlaybackService.refreshButtons();
                    if (onChanged != null) onChanged.run();
                    break;
                case 4:
                    addToPlaylistDialog(a, song.id, onChanged);
                    break;
                case 5:
                    Db.get(a).removeFromPlaylist(playlistId, song.id);
                    if (onChanged != null) onChanged.run();
                    break;
                case 6:
                    confirmDelete(a, song, c, onChanged);
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    // ---------- delete from device ----------

    private static final int REQ_DELETE = 4711;
    @Nullable private static Runnable pendingDelete;

    /** Activities forward onActivityResult here so the system's delete confirmation can finish. */
    public static void handleActivityResult(int requestCode, int resultCode) {
        if (requestCode != REQ_DELETE) return;
        Runnable r = pendingDelete;
        pendingDelete = null;
        if (r != null && resultCode == Activity.RESULT_OK) r.run();
    }

    public static void confirmDelete(Activity a, Song song, @Nullable MediaController c,
                                     @Nullable Runnable onChanged) {
        new ThemedDialogBuilder(a)
                .setTitle("Delete song?")
                .setMessage("\"" + song.cleanTitle + "\" will be deleted from your device. "
                        + "This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> deleteFile(a, song, c, onChanged))
                .show();
    }

    private static void deleteFile(Activity a, Song song, @Nullable MediaController c,
                                   @Nullable Runnable onChanged) {
        final Runnable done = () -> {
            Library.remove(song.id);
            Db.get(a).forgetSong(song.id);
            if (c != null) {
                for (int i = c.getMediaItemCount() - 1; i >= 0; i--) {
                    if (c.getMediaItemAt(i).mediaId.equals(String.valueOf(song.id))) {
                        c.removeMediaItem(i);
                    }
                }
            }
            Toast.makeText(a, "Song deleted", Toast.LENGTH_SHORT).show();
            if (onChanged != null) onChanged.run();
        };
        final ContentResolver cr = a.getContentResolver();
        final Uri uri = song.uri;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                // Android shows its own "allow deleting this song?" prompt
                pendingDelete = done;
                a.startIntentSenderForResult(MediaStore.createDeleteRequest(cr,
                        Collections.singletonList(uri)).getIntentSender(), REQ_DELETE,
                        null, 0, 0, 0);
            } else if (cr.delete(uri, null, null) > 0) {
                done.run();
            } else {
                Toast.makeText(a, "Couldn't delete this song", Toast.LENGTH_SHORT).show();
            }
        } catch (RecoverableSecurityException e) {
            try {
                IntentSender sender = e.getUserAction().getActionIntent().getIntentSender();
                pendingDelete = () -> {
                    try {
                        if (cr.delete(uri, null, null) > 0) done.run();
                    } catch (Exception ignored) {
                    }
                };
                a.startIntentSenderForResult(sender, REQ_DELETE, null, 0, 0, 0);
            } catch (Exception ex) {
                Toast.makeText(a, "Couldn't delete this song", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(a, "Couldn't delete this song", Toast.LENGTH_SHORT).show();
        }
    }

    public static void addToPlaylistDialog(Activity a, long songId, @Nullable Runnable onChanged) {
        final List<Db.Playlist> rows = new ArrayList<>();
        rows.add(null); // first row = "New playlist"
        rows.addAll(Db.get(a).playlists());
        final Set<Long> containing = Db.get(a).playlistsContaining(songId);

        ArrayAdapter<Db.Playlist> adapter = new ArrayAdapter<Db.Playlist>(a, 0, rows) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = convertView != null ? convertView
                        : LayoutInflater.from(a).inflate(R.layout.item_playlist_pick, parent, false);
                ImageView icon = v.findViewById(R.id.pick_icon);
                TextView name = v.findViewById(R.id.pick_name);
                TextView count = v.findViewById(R.id.pick_count);
                ImageView check = v.findViewById(R.id.pick_check);

                Db.Playlist p = getItem(position);
                if (p == null) {
                    icon.setImageResource(R.drawable.ic_add);
                    PlaylistStyle.applyColor(icon, PlaylistStyle.NEUTRAL);
                    name.setText("New playlist");
                    name.setTextColor(com.google.android.material.color.MaterialColors.getColor(name,
                            com.google.android.material.R.attr.colorPrimary));
                    count.setVisibility(View.GONE);
                    check.setVisibility(View.GONE);
                } else {
                    icon.setImageResource(R.drawable.ic_queue_music);
                    PlaylistStyle.applyColor(icon, PlaylistStyle.color(p.id));
                    name.setText(p.name);
                    name.setTextColor(com.google.android.material.color.MaterialColors.getColor(name,
                            com.google.android.material.R.attr.colorOnSurface));
                    count.setVisibility(View.VISIBLE);
                    count.setText(containing.contains(p.id) ? "Already added"
                            : p.count + (p.count == 1 ? " song" : " songs"));
                    check.setVisibility(containing.contains(p.id) ? View.VISIBLE : View.GONE);
                }
                return v;
            }
        };

        new ThemedDialogBuilder(a)
                .setTitle("Add to playlist")
                .setAdapter(adapter, (dialog, which) -> {
                    Db.Playlist p = rows.get(which);
                    if (p == null) {
                        promptName(a, "New playlist", "", name -> {
                            long id = Db.get(a).createPlaylist(name);
                            Db.get(a).addToPlaylist(id, songId);
                            Toast.makeText(a, "Added to " + name, Toast.LENGTH_SHORT).show();
                            if (onChanged != null) onChanged.run();
                        });
                    } else {
                        boolean added = Db.get(a).addToPlaylist(p.id, songId);
                        Toast.makeText(a, added ? "Added to " + p.name : "Already in " + p.name,
                                Toast.LENGTH_SHORT).show();
                        if (onChanged != null) onChanged.run();
                    }
                })
                .show();
    }

    public static void promptName(Activity a, String title, String initial, NameCallback cb) {
        final EditText input = new EditText(a);
        input.setSingleLine();
        input.setText(initial);
        input.setSelection(initial.length());
        input.setHint("Name");

        FrameLayout box = new FrameLayout(a);
        int pad = (int) (24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        new ThemedDialogBuilder(a)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) cb.onName(name);
                })
                .show();
    }
}
