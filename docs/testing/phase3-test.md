# Phase 3 manual test: playlists (Oppo A1k)

Install the new APK over the old one (`adb install -r app/build/outputs/apk/debug/app-debug.apk`). Mark each ✅/❌.

1. **Empty state**: open Sur. The Playlists tab shows "No playlists yet". Tap +, create "Test", and it appears with "0 songs".
2. **Rename**: ⋮ → Rename → "Morning". The name updates in the list.
3. **Add multiple**: Songs tab, long-press a song, tick 4 more, tap the add-to-playlist icon, pick "Morning". The toast appears and the playlist shows "5 songs".
4. **Duplicate**: on a song already in "Morning", ⋮ → Add to playlist → "Morning". The count stays at 5 with no error.
5. **New from picker**: select 2 songs, add-to-playlist → New playlist → "Evening". It's created with 2 songs.
6. **Reorder**: open "Morning" and drag song 5 to the top by its handle. Go back, reopen, and the order is kept.
7. **Remove**: tap ✕ on one song. It disappears and the count shows 4.
8. **Play from middle**: tap the 3rd song. It plays, and next/previous follow the playlist order (check from the notification).
9. **Missing file**: delete one of the playlist's song files with a file manager, then reopen Sur and the playlist. The song is gone from the list, the count drops, and nothing crashes.
10. **Persistence and delete**: swipe Sur from recents, reopen it, and the playlists and order are intact. Then ⋮ → Delete on "Evening" → confirm, and it's gone. Cancel on the confirm dialog keeps it.
