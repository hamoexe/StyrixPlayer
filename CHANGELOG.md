# Changelog

Newest first. One entry per release you tag on GitHub (not one per build).
Copy the entry into the release notes: the in-app updater shows that text.

## 1.24.4
- New: press the download button again while a song is downloading to cancel it. The button shows a cross inside the progress ring, the media notification's download button turns into "Cancel download", and the download's own notification has a Cancel button. A cancelled download leaves no half file behind.
- Fixed: a one-line "this is instrumental" note sent as lyrics (QQ Music's "此歌曲为没有填词的纯音乐，请您欣赏" and similar) is now treated as an instrumental song instead of being shown as lyrics. The Source tab shows these as "Instrumental".

- New: lyrics files next to songs. Turn on Settings > Playback > "Lyrics files next to songs" (it asks for all-files access), then a file with the same name as the song in the same folder is used first: myMusic.m4a and myMusic.lrc (or .txt for plain text). It also shows up as "Local file" in the lyrics Source tab.
- Changed: the lyrics Source tab remembers what it found for each song (for 14 days), so reopening it is instant. Press Search again to check the sources again. Failed lookups are never remembered.

## 1.24.3
- Changed: in the lyrics Source tab, "Search again" no longer closes the sheet. It checks every source again, then switches to the best result by itself: word by word if any source has it, otherwise line by line, otherwise text only. Tapping a source also keeps the sheet open now.
- Fixed: the "every song" switch used the wrong colours; it follows the cover's accent now.
- Changed: a slow source no longer holds the list up: a source stops trying more title variants after 10 s, and after a fresh search the best word-by-word result is picked after 6 s without waiting for the slowest source. A source that times out shows "Too slow" instead of "Couldn't reach". LyricsPlus keeps its long wait (25 s) because its server is slow but usually answers.

## 1.24.2
- Changed: the lyrics Source tab now checks every source live for the current song. Each one shows Word by word, Line by line, Text only, or greys out when it has nothing or can't be reached. Tap one that has lyrics to switch to it instantly, with no new search. The one showing now is marked. A switch makes the chosen source the default for every song. Automatic is at the top.
- Changed: new look for the lyrics sheet (soft gradient, segmented tabs, glowing offset card).

## 1.24.1
- Changed: the lyrics menu is now a bottom sheet with three tabs. Sync shows the current offset big with -0.5 / -0.1 / +0.1 / +0.5 buttons and Reset. Source lists every lyrics source with short names and a mark on the one in use (tap one to switch). Edit opens the lyrics editor. "Search again" is under Source.
- Added Arabic / Kurdish / Turkish translations for the new menu.

## 1.24.0
- New: Video to audio. Menu (three dots) > Video to audio, or share any video to StyrixPlayer. You name the song (the title starts from the video's name), pick a cover (a frame from the video, or any image) and can use only part of the video. The sound is copied as it is when it is already AAC, otherwise converted to AAC. The song is saved as .m4a in Music/StyrixPlayer with the title, artist and cover inside the file, and appears in your library.
- Changed: while the lyrics are open, the cover now shrinks into a small picture to the left of the title and artist instead of disappearing.

## 1.23.8
- Fixed: Chinese, Japanese and Korean lyrics (word-synced from Kugou) were treated as one giant word, so the word swell and the timing smoothing covered the whole line. Every character now counts as its own word.
- Added Arabic / Kurdish / Turkish translations for the new lyrics source names.

## 1.23.7
- New: lyrics providers for NetEase, Kugou and QQ Music, with individual source selection and automatic fallback.
- Improved: lyric search matching checks song title and artist instead of blindly accepting the first result, reducing unrelated matches and remixes.
- Improved: provider failures and empty lyric responses are handled so the next source can be tried.
- New: Kugou KRC lyrics are decoded and converted while preserving word-level timing.

## 1.23.6
- New: the "Most played part" graph above the seek bar now shows YouTube's own "Most replayed" graph for songs that have one. Songs without one keep your own listening graph.
- Changed: the visualizer now reaches down to 20 Hz (was 45 Hz), so the deep bass has its own bars. The frequency range slider starts at 20 Hz.

## 1.23.5
- Fixed: the first letter of a line (for example the C in "Courtney") was cut off while its word swelled. A swelling word now stays inside the line.

## 1.23.4
- Fixed: a held word (a long note on one syllable) made the word highlight crawl for seconds and then jump to the next word. The highlight now sweeps in a time that fits the word, waits, and carries on from where it stopped.
- Changed: the word swell is now eased (smooth in and out) instead of a straight line.
- New: in lyrics timed per syllable, the syllable being sung swells a little more inside the swelling word.

## 1.23.3
- Fixed: lyrics whose words are split into syllables (some only a few milliseconds long) made the word highlight jump through the first part and finish early. Syllable timing is now smoothed inside each word.
- New: the word being sung swells slightly (about 7%) and settles back as the next word starts.

## 1.23.2
- Changed: the lyrics word-sync screen has a cleaner look. Bigger current-line card, only the playing line is highlighted, larger lyric text, and the player controls sit together in one rounded panel with a bigger Tap button.

## 1.23.1
- Changed: the visualizer reacted far too much on loud songs. It is now calmer by default.
- New: Settings > Playback > Visualizer style. Choose bars or a smooth line, set sensitivity and smoothness, and pick the frequency range so you can hide the part that jumps most with voices. A live preview shows the result.

## 1.23.0
- New: audio visualizer. Settings > Playback > Audio visualizer shows bars above the seek bar that dance to the music. Works for phone songs and YouTube and needs no extra permission.

## 1.22.2
- Fixed: the Settings switches all showed "off" after changing language or the device theme.
- Fixed: after changing language or the device theme the tab bar and the page could disagree (YouTube selected, Songs shown).
- Fixed: the playing song's row could keep the previous song's colour.
- Tapping the song that is playing now opens the player instead of restarting it.
- Swipe the mini player sideways or down to close it and stop playback.
- Mini player: song progress is now a ring around the play button instead of a bar.
- The mini player now floats over the lists instead of having its own dark strip; the lists scroll underneath it.
- Closing the player from the lyrics tab: the mini player slides in with its cover instead of popping up at the end.

## 1.22.1
- Media notification and lock screen: heart button (likes the song on YouTube Music, or adds a phone song to Favorites) and a download button for streamed songs.
- The notification badge is now the StyrixPlayer note-and-ring icon instead of the default.

## 1.22.0
- Choose your app icon: Settings > Appearance > App icon. New Kurdistan icon (flag-colour rings, 21-ray sun).
- Two more icons: Christmas and Chrome.
- Every icon has its own Android 13 themed version.

## 1.21.5
- New app icon: a wavy sound ring with a note, with an Android 13 themed (single colour) version.

## 1.21.4
- Smoother seek bar: the thumb follows playback every frame instead of jumping twice a second.
- Pressing download no longer makes the app feel heavy (the lookup runs at low priority).

## 1.21.3
- "Slowed" / "Sped up" tag now sits in the top bar next to the speed, not as a chip under the title.
- Add to playlist: new look.

## 1.21.2
- The speed panel is a real blur of the cover everywhere (also over lyrics and video).

## 1.21.0 – 1.21.1
- Wavy seek bar: the played part rolls while music plays and goes flat on pause.
- Round wavy progress ring on the download button, then it turns into "add to playlist".
- One download queue for songs, audio and video. Fixed the notification that stayed on "Downloading 100%".
- Songs already on your phone no longer look anything up online (video, lyrics "not found").
- Cover scales in instead of fading. Lyrics under the video have room for three lines.
- Lyrics long-press menu works for streamed songs. Lyrics colours follow the cover.
- Tighter player spacing: the buttons sit higher.

## 1.20.0
- YouTube video download with quality choice (up to 1080p, saved in Movies/StyrixPlayer).
- Download page, Playlists, Edit metadata and Edit lyrics got the bold new headers. Dialog titles are bold.

## 1.19.x
- Colourful redesign: player, Songs / YouTube lists, Settings, Queue, dialogs and menus.
- Video mode: video edge to edge on top, cover card below when there are no lyrics.
- Version now comes from `AppInfo.java` only (build.gradle reads it).
- Fixes: cover pop at the end of the open animation, notification dying after a minute paused,
  blurred cover with no cover behind it, dialog button colours, search bar jump, Queue not recolouring.

## 1.18.x and older
- See PROJECT_INFO.md (developer notes). Updater, translations, frosted speed panel, lyrics sources.
