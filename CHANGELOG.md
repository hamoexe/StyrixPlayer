# Changelog

Newest first. One entry per release you tag on GitHub (not one per build).
Copy the entry into the release notes: the in-app updater shows that text.

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
