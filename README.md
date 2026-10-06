# Sur

A lightweight, open-source Android music player for your local library.

> **Work in progress.** Phase 1 (project scaffold) only. There is no player yet.

## Goals

- Plays the audio already on your device, with no accounts, cloud, ads or analytics.
- Playlists first: the home screen is your playlists, with Songs, Albums and Artists as secondary views.
- Material 3 with dynamic color on Android 12+ and a calm static palette on Android 8-11.
- Light on resources, so it runs well on low-end phones.

## Tech

Kotlin, Jetpack Compose, Material 3. `minSdk` 26 (Android 8.0), `targetSdk` 37.
Package: `io.github.saalfy.sur`.

## Build

Requires JDK 17 and the Android SDK (platform 37).

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## License

Sur is free software, licensed under the [GNU General Public License v3.0](LICENSE) (GPL-3.0-only).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the [LICENSE](LICENSE) file for details.
