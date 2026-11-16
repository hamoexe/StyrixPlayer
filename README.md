# StyrixPlayer

A music player for Android that follows the colors of what you listen to. It plays your local library and YouTube Music, shows synced word-by-word lyrics, and repaints the whole player from the cover art or the playing video.

Written in Java with Material 3 and media3, and built entirely on a phone.

## Features

**Playback**
- Local library with songs, albums, artists, playlists and favorites
- YouTube Music: search, stream and queue, with a signed-in account
- Speed and pitch control, sleep timer, shuffle and repeat
- Music cache so streamed songs replay instantly, even offline
- Video mode for streamed songs, with the picture running beside the lyrics

**Lyrics**
- Synced lyrics from LyricsPlus, BiniLyrics and LRCLIB, picked automatically or chosen by you
- Word-by-word highlighting that glides with the singer, plus a per-song timing offset

**Ambient look**
- The background is a smooth color mesh taken from the cover, or live from the video as it plays
- Buttons and panels are frosted glass tinted by the colors behind them
- A "most listened" bar above the seek bar shows which parts of a song you replay

**Downloads and tags**
- Download YouTube audio to `Music/StyrixPlayer` with the cover and tags written into the file
- Tag editor with online lookup (iTunes and Deezer) and cover picking

**Everything else**
- Arabic, Kurdish (Sorani) and Turkish, with right-to-left layout
- Built-in update check that downloads and installs new releases
- Crash report you can copy after a crash

## Install

Download the latest APK from the [Releases](../../releases) page and open it. Android will ask you to allow installs from your browser or file manager the first time. Once the app is installed, it tells you when a newer release is out.

Requires Android 8.0 or newer.

## Build from source

```
git clone https://github.com/hamoexe/StyrixPlayer.git
cd StyrixPlayer
./gradlew assembleDebug
```

The app is a single `app` module. Main dependencies: AndroidX media3 for playback, NewPipeExtractor for YouTube, and Material Components.

Release builds are signed with a private key that is not part of this repository, so updates only install over builds signed with the same key.

## Releasing an update

The in-app updater reads the newest release of this repository. To ship a version, bump `versionName` and `versionCode` in `app/build.gradle` and in `AppInfo.java`, build and sign the APK, then publish a GitHub release tagged `vX.Y.Z` with the APK attached. The release notes are shown to users in the update dialog.

## Credits

[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) for YouTube access, [LRCLIB](https://lrclib.net) and BiniLyrics for lyrics, and [AndroidX media3](https://developer.android.com/media/media3) for the player.
