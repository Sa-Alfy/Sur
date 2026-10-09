# Phase 5 manual test: downloader UI & playback (Oppo A1k, Android 9)

Install `app/build/outputs/apk/debug/app-debug.apk`. Test downloading songs using the in-app downloader UI. Mark each ✅/❌.

1. **Entry point**: navigate to the downloader screen in Sur. The screen shows a link input field, a download action button, and clear helper text without clutter. ✅
2. **Valid link input & resolving**: paste a valid YouTube song link (or share a link into Sur). Tap download. The UI transitions immediately to show "Resolving…" and provides a cancel option. ✅
3. **Progress indicator**: once resolved, the UI displays the song title and channel/artist name. The progress indicator advances smoothly from 0% to 100%, and the system notification stays in sync. ✅
4. **Completion & library update**: once the download finishes, the UI indicates completion. Switch to the Songs tab: the newly downloaded song appears in the library immediately without restarting the app. ✅
5. **Playback verification**: tap the downloaded song in Sur. Audio plays cleanly through ExoPlayer with correct duration, seek bar response, and mini player display. ✅
6. **File storage & tags**: open a file manager and inspect `Internal Storage/Music/Sur/`. The file is saved as `Artist - Title.m4a` (or `Title.m4a`). Audio metadata tags (title, artist, album) are populated. ✅
7. **Cancel download**: start downloading a song, then tap Cancel (either in the UI or from the notification). The download stops immediately and no orphan or corrupt file remains in `Music/Sur/` or cache. ✅
8. **Busy prevention**: while a download is actively running, attempt to start a second download. The app informs that a download is already active, and the ongoing download continues uninterrupted. ✅
9. **Invalid link handling**: enter an unsupported link (such as a playlist, channel, non-YouTube URL, or plain text) and tap download. The UI displays the corresponding friendly error message (e.g. "Playlists aren't supported yet." or "That doesn't look like a YouTube video link.") without crashing. ✅
10. **Network error**: enable Airplane Mode and attempt to download a song. The UI displays "No internet connection." and gracefully allows retrying once connection is restored. ✅
