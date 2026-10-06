# Sur

A lightweight, open-source Android music player for your local library.

> **Work in progress.** Usable day to day; a downloader module is planned later.

## Features

- **Plays your local music**: scans the device library via MediaStore (skips ringtones, notification sounds and clips under 30 s).
- **Playlists first**: the home screen is your playlists. Create, rename, delete, drag to reorder, and add songs one at a time or by multi-select.
- **Background playback**: Media3 service with notification and lock screen controls, audio focus, and pause when headphones are unplugged.
- **Now playing**: album art, seek bar, shuffle, repeat (off / all / one) and a queue view with tap-to-jump, plus a mini player across the app.
- **Search**: songs by title, artist or album; playlists by name.
- **Material 3**: dynamic color on Android 12+, a calm teal palette on Android 8–11, and it follows system light/dark.
- **Light on resources**: built and tested on a 2 GB RAM Android 9 phone.
- **Private**: no accounts, cloud, ads or analytics. Nothing leaves your device.

## Requirements

Android 8.0 (API 26) or newer.

Sur asks for:
- **Audio files**: to read your music library (`READ_MEDIA_AUDIO` on 13+, `READ_EXTERNAL_STORAGE` on 12 and below).
- **Notifications** (Android 13+, optional): to show playback controls. Music plays even if you decline.

Some phones (Oppo, Xiaomi, Vivo, Huawei…) stop background apps aggressively. If music cuts out, allow Sur to run without battery restrictions. Sur offers a shortcut on first launch.

## Tech

Kotlin, Jetpack Compose, Material 3, Media3 (ExoPlayer + MediaSessionService) and Room.
`minSdk` 26, `targetSdk` 37. Package: `io.github.saalfy.sur`.

## Build

Requires JDK 17 and the Android SDK (platform 37).

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

Manual device test checklists live in [`docs/`](docs/).

## License

Sur is free software, licensed under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [LICENSE](LICENSE) file for details.

Third-party libraries: AndroidX / Jetpack (Apache-2.0) and [Reorderable](https://github.com/Calvin-LL/Reorderable) (Apache-2.0).
