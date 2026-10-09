# Phase 5 manual test: downloader engine & service (Oppo A1k, Android 9)

Install the debug APK (`adb install -r app/build/outputs/apk/debug/app-debug.apk`). Since the UI for downloading is in the next phase, trigger the service via ADB commands. Mark each ✅/❌.

### ADB commands for testing
- **Start download:**
  `adb shell am start-foreground-service -n io.github.saalfy.sur/.download.DownloadService --es link "<YOUTUBE_URL>"`
- **Cancel download:**
  `adb shell am start-service -n io.github.saalfy.sur/.download.DownloadService -a io.github.saalfy.sur.download.action.CANCEL`

---

1. **Start & Resolving state**: with Sur open (or in background), run the start ADB command with a valid song link. A foreground notification appears immediately with "Resolving…", a music icon, and a Cancel button.
2. **Video title & Determinate progress**: once resolved, the notification title updates to the video title, subtitle shows channel/artist, and progress bar switches to determinate progress, advancing smoothly from 0% to 100%.
3. **Automatic save & notification dismissal**: when download finishes (remux & tag complete), the foreground notification dismisses cleanly without lingering.
4. **Appears in Songs tab**: open Sur's Songs tab. The newly downloaded track appears in the library with correct title and artist, and duration ≥ 30s. Tap to play; audio plays smoothly through ExoPlayer.
5. **Storage location & File tags**: check `Internal Storage/Music/Sur/` in a file manager. The file is saved as `Artist - Title.m4a` (or `Title.m4a`). Title, artist, and album ("Sur downloads") are tagged.
6. **Cancel action**: start a download of a longer video (e.g. 5–10 min song). Tap "Cancel" on the notification. The download stops immediately, the notification disappears, and no partial or corrupt file remains in `Music/Sur/` or cache.
7. **Single download (Busy rejection)**: while a download is running, run a second start command with a different link. The second download does not start, the active download continues unaffected, and state transitions to `Busy`.
8. **Network failure**: turn on Airplane Mode, then send a download command. The service reports error (`NoInternet`), cleans up temp files, and shuts down without crashing.
9. **Invalid link**: send an invalid URL (e.g. non-YouTube link or playlist). The service rejects it gracefully (`UnsupportedLink`) without crashing.
10. **Background persistence**: start a download, lock the screen or switch to another app. The download continues to completion in the background without being killed by Android 9 battery management.
