# Phase 6a manual test: UI bug fixes, fake UI removal, artwork fallback (Oppo A1k, Android 9)

Install `app/build/outputs/apk/debug/app-debug.apk`. Test the UI fixes and artwork loading. Mark each ✅/❌.

1. **Now Playing portrait layout**: open Now Playing on a song with and without artwork. Artwork scales cleanly within available space without overlapping playback controls or clipping song title/artist. ☐
2. **Now Playing landscape layout**: rotate device while Now Playing is open. In landscape, artwork scales to fit half the screen, the up-next section is hidden, and controls remain fully visible and usable. ☐
3. **Queue list height constraint**: tap the queue button in Now Playing. The queue list is constrained to available height and does not push playback controls or progress bar off-screen. ☐
4. **Download screen clean UI**: navigate to the Download tab. The card title is "Download Audio", the link input and download button are visible, and fake format selector chips (M4A/MP3/OPUS) and NewPipe badge are removed. ☐
5. **Download legal disclaimer**: verify the helper text under the download card reads: "Paste a YouTube link. Audio is saved to Music/Sur. Only download content you have the right to." ☐
6. **Songs screen clean UI**: navigate to the Songs tab. The fake "All Songs / Queue" tab row is gone. The song list renders directly below the top bar without unnecessary tab headers. ☐
7. **Songs selection bar**: select one or more songs. The top action bar displays the selection count cleanly without the misleading "batch_queue_edit" subtitle. ☐
8. **Embedded artwork fallback**: play or view a downloaded song or local file that has embedded ID3/MP4 cover art but no system MediaStore thumbnail. The embedded album art renders in the song list, mini player, and Now Playing screen. ☐
9. **Missing artwork placeholder**: play or view a song with no embedded art. The teal music-note placeholder renders reliably without crashes or infinite reload loops. ☐
10. **Low memory stability**: scroll through a long list of songs with artwork and toggle between screens on the 2GB RAM test device. Memory remains bounded and UI remains smooth without OutOfMemoryError. ☐
