# Phase 9b manual test: regression smoke test & overlay wiring (Oppo A1k, Android 9)

Install `app/build/outputs/apk/debug/app-debug.apk`. Because no UI creates overrides yet, this is a regression smoke test verifying that with an empty or missing info database the app behaves exactly as before. Note: the currently playing MediaItem is not replaced when an override changes; it takes effect the next time the song is queued or played. Mark each ✅/❌.

1. **Songs list loads**: open the app. Verify the Songs list loads with all local songs in case-insensitive alphabetical order by title. ☐
2. **Search finds songs**: tap the search bar, type a song title, artist, or album. Verify matching songs are filtered in real-time, and clearing the query restores the full library. ☐
3. **Playlists open and display counts**: open the Playlists tab. Verify existing playlists open, display correct song counts and mosaic artwork, and navigate into details without error. ☐
4. **Playlist reorder and play**: in a playlist detail screen, reorder songs and tap play. Verify playback plays the playlist in the modified order. ☐
5. **Playback, Mini Player, Now Playing**: tap a song in the library. Verify playback begins, the mini player appears at the bottom, and opening Now Playing displays the progress slider, time labels, and playback controls. ☐
6. **Artwork and lockscreen art**: verify artwork displays in song rows, the mini player, Now Playing, and the lockscreen/notification with identical appearance to previous releases. ☐
7. **Artwork from downloads**: verify tracks downloaded via the Downloader module still show their downloaded cover art. ☐
8. **M3U export and import**: export an existing playlist to M3U via SAF and re-import it. Verify playlist export and import succeed without errors. ☐
9. **Missing or empty info database**: launch the app with a fresh install or empty `sur_info.db`. Verify the app starts immediately, loads all MediaStore songs, and plays music without crashing or blocking the songs flow. ☐
10. **Screen rotation and app lifecycle**: rotate the device between portrait and landscape and move the app between background and foreground during active playback. Verify song list order is retained without duplicated or lost entries. ☐
