# Release v1.0.0

The first stable release. No new screens beyond the licenses list; this release is about keeping your data safe and making the existing features dependable.

## What's new
- **Playlists survive library changes**: when Android re-indexes your music (new SD card mount, media rescan), playlist entries now find their song again. A song is only re-linked when its path, storage (internal or SD card) and length all match; anything uncertain stays as it was instead of pointing at the wrong song.
- **Your edits stay yours**: re-downloading a song no longer overwrites a title, artist, album or cover you chose in Find cover & details.
- **More reliable downloads**: a network drop or timeout retries and resumes where it stopped instead of failing; a server that ignores partial requests no longer produces a corrupt file; a download is only saved when Android can actually read its audio; leftover temporary files from interrupted downloads are cleaned up.
- **Cancelling is clean**: a cancelled download is never reported as done, and a late update from a cancelled download can't bring back a stuck "Downloading" card.
- **Safer deleting**: covers and song details are cleaned up in a safe order, one failed step no longer skips the rest, and leftover cover files are removed later.
- **Remove found info asks first** before removing a song's saved title, artist, album and cover.
- **No more crash** when Android's media database fails during a scan or under low memory; the song list just keeps the last good result.
- **Open-source licenses** are listed in About.

## Updating
- **From v0.3.x, v0.2.0 or v0.1.0**: install over it as usual. Your playlists are upgraded automatically (database version 1 → 3); nothing is lost.
- **From v0.3.0**: v0.3.0 was signed with a development key, so Android won't update it. Uninstall it first, then install v1.0.0. Uninstalling deletes your playlists; your music files are not touched.

Built with the same release key as v0.3.4; the signing certificate SHA-256 should be
`DA:D2:6A:E7:86:B2:52:6A:36:B6:81:F3:3F:F3:BE:1C:6C:76:1A:80:6C:EF:6C:0D:A3:6E:68:65:D7:75:6F:42`.

## Known limitations
- **YouTube changes break downloads from time to time.** Downloads use NewPipeExtractor v0.26.5, verified working on 2026-11-19. When downloads start failing with "YouTube changed something", an update to Sur is needed.
- **The queue is not saved.** If Android stops Sur while playback is paused (common on phones with aggressive battery saving), the queue is empty on the next start.
- **Android 13+ "resume playback" card** in the system media controls is not supported.
- **Re-linking limits**: a different song with the same file name, folder, storage and length (within 1 second) could still be re-linked. Entries whose song was already missing before this update stay missing.
- Some playback and lifecycle behaviour (screen-off playback, lock-screen controls, headphone unplug, battery-saver kills) was reviewed in code but not yet tested on a phone for this release.

## Install
Download `Sur-v1.0.0.apk` from the GitHub release and open it on your phone (allow installs from your browser or file manager when asked). Android 8.0 or newer.

## Build
Requires JDK 17 and the Android SDK (compileSdk 37).
```
./gradlew assembleDebug                 # debug APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest lintDebug   # unit tests and lint
./gradlew connectedDebugAndroidTest     # Room migration + playlist DB tests (device/emulator)
./gradlew assembleRelease               # needs SUR_RELEASE_STORE_FILE, _STORE_PASSWORD,
                                        # _KEY_ALIAS, _KEY_PASSWORD Gradle properties
```
Release builds refuse to package without Sur's release key. GitHub releases are built by `.github/workflows/release.yml` (see `docs/ci.md`). On Windows, if Gradle fails with "Unable to establish loopback connection", set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<short dir> -Djava.net.preferIPv4Stack=true`.
