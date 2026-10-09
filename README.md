# Sur

A lightweight, open-source Android music player for your local library.

> **Work in progress.** Usable day to day. Latest version: **v0.1.0**. Get it from [Releases](https://github.com/Sa-Alfy/Sur/releases).

## Install

1. Download `Sur-v0.1.0.apk` from the [latest release](https://github.com/Sa-Alfy/Sur/releases/latest) on your phone.
2. Open it and allow installing from this source when Android asks.
3. Launch Sur and allow access to your audio files.

Release APKs are signed with Sur's release key. SHA-256 certificate fingerprint:
`DA:D2:6A:E7:86:B2:52:6A:36:B6:81:F3:3F:F3:BE:1C:6C:76:1A:80:6C:EF:6C:0D:A3:6E:68:65:D7:75:6F:42`

## Features

- **Plays your local music**: scans the device library via MediaStore (skips ringtones, notification sounds and clips under 30 s).
- **Playlists first**: the home screen is your playlists. Create, rename, delete, drag to reorder, and add songs one at a time or by multi-select.
- **Background playback**: Media3 service with notification and lock screen controls, audio focus, and pause when headphones are unplugged.
- **Now playing**: album art, seek bar, shuffle, repeat (off / all / one) and a queue view with tap-to-jump, plus a mini player across the app.
- **Search**: songs by title, artist or album; playlists by name.
- **Material 3**: dynamic color on Android 12+, a calm teal palette on Android 8–11, and it follows system light/dark.
- **Downloader**: save audio from a single YouTube link directly into your library (`Music/Sur`) via the Download tab or shared links, with live progress, foreground notification controls, and audio tagging.
- **Light on resources**: built and tested on a 2 GB RAM Android 9 phone.
- **Private**: no accounts, cloud, ads or analytics. Connects to the network only when downloading audio you request.

## Roadmap

- **Bitrate & format preferences**: options for audio quality and format in the downloader.
- **Batch / playlist queueing**: queue multiple downloads sequentially.

## Requirements

Android 8.0 (API 26) or newer.

Sur asks for:
- **Audio files**: to read your music library (`READ_MEDIA_AUDIO` on 13+, `READ_EXTERNAL_STORAGE` on 12 and below).
- **Notifications** (Android 13+, optional): to show playback controls and download progress.
- **Network access**: used exclusively to download audio streams you request.

Some phones (Oppo, Xiaomi, Vivo, Huawei…) stop background apps aggressively. If music cuts out, allow Sur to run without battery restrictions. Sur offers a shortcut on first launch.

## Tech

Kotlin, Jetpack Compose, Material 3, Media3 (ExoPlayer + MediaSessionService), Room, and NewPipe Extractor + OkHttp for the downloader.
`minSdk` 26, `targetSdk` 37. Package: `io.github.saalfy.sur`.

## Build

Requires JDK 17 and the Android SDK (platform 37).

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Release builds (`./gradlew assembleRelease`) are minified, and are signed only when `SUR_RELEASE_STORE_FILE`, `SUR_RELEASE_STORE_PASSWORD`, `SUR_RELEASE_KEY_ALIAS` and `SUR_RELEASE_KEY_PASSWORD` are set as Gradle properties (e.g. in `~/.gradle/gradle.properties`). Otherwise they're unsigned. Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

Manual device test checklists live in [`docs/`](docs/).

## License

Sur is free software, licensed under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [LICENSE](LICENSE) file for details.

Third-party libraries: AndroidX / Jetpack (Apache-2.0), [Reorderable](https://github.com/Calvin-LL/Reorderable) (Apache-2.0), [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0), and [OkHttp](https://github.com/square/okhttp) (Apache-2.0).
