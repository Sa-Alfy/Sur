# Phase 4 manual test: player UI, search, battery (Oppo A1k, Android 9)

Uninstall first so the battery dialog counts as a first launch, then install `app/build/outputs/apk/debug/app-debug.apk`. Mark each ✅/❌.

1. **Battery dialog**: on first launch (after granting audio access) a "Keep music playing" dialog appears. "Open settings" opens the battery/optimisation screen. Reopen Sur and it does not appear again. On a second fresh install, "Don't show again" also stops it.
2. **Mini player appears/disappears**: play any song and a bar shows above the tabs with art (or a note placeholder), title, artist, play/pause and a thin progress line. It also shows inside a playlist. Kill the queue (swipe the notification away after pausing, then reopen Sur) and the bar is gone.
3. **Seek**: open now-playing by tapping the mini player. Drag the seek bar and the time follows your finger without jumping back. Release and playback continues from there. The elapsed time ticks smoothly.
4. **Controls**: play/pause, previous and next work. The notification stays in sync.
5. **Shuffle**: toggle it on (icon turns teal), open the queue (top-right icon), and the order is shuffled. Toggle it off and the order returns.
6. **Repeat**: tap to cycle off → all (teal) → one (icon shows "1"). With repeat one, the song restarts when it ends. With repeat all, the last song goes back to the first.
7. **Queue jump**: in the queue the current song is highlighted. Tap another and it plays and the highlight moves. Back closes the queue first, then a second back closes now-playing.
8. **Rotate**: while now-playing is open and playing, rotate the phone. Music doesn't stop, now-playing stays open in a side-by-side layout, and rotating back is fine.
9. **Search**: in Songs, tap 🔍 and type part of a title, then an artist, then an album. The list filters, and nonsense text shows "No results for …". ✕ or back clears it. On Playlists, search by playlist name.
10. **Art and theme**: songs with embedded art show it; songs without show the teal note placeholder. Switch to dark (Developer options → Night mode → Always on, then reopen Sur). Lists, the mini player, now-playing, the tab bar and dialogs are all teal/dark with readable text and no purple. Switch back to light and check again.

Bonus: delete the file of a song that's later in the current queue, then skip to it. Sur should skip past it without crashing.
