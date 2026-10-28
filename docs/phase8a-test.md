# Phase 8a manual test: layout wrapping and export/import error handling (Oppo A1k, Android 9)

Install `app/build/outputs/apk/debug/app-debug.apk`. Verify layout adjustments on large/wide fonts and robust export/import error handling. Mark each ✅/❌.

1. **Empty Playlists screen with wide font**: open the Playlists screen with no playlists created, using the system wide font setting. Verify the "Create playlist" and "Import playlist" buttons are stacked in a column, constrained nicely without clipping or stretching awkwardly. ☐
2. **Empty Playlists screen with font scale 1.3**: set system font size to Large / scale 1.3 on the Oppo A1k. Open the empty Playlists screen. Verify button text and icons fit comfortably within the buttons without text truncation. ☐
3. **Header buttons wrap on large font or narrow screen**: with at least one playlist present, view the header row containing "New playlist" and "Import playlist". Under font scale 1.3 or narrow width, verify the FlowRow wraps the buttons onto two rows cleanly instead of squishing them horizontally. ☐
4. **Export to a normal folder**: open a playlist with songs, tap overflow (⋮) -> "Export as M3U", and save to a standard writable directory (e.g., Documents or Music). Verify the success dialog appears showing the number of exported songs. ☐
5. **Export failure case**: attempt to export to a read-only or invalid URI (or trigger a storage error if possible). Verify the failure dialog appears displaying "Couldn't save the playlist file." and never displays the success message. ☐
6. **Import good file**: tap "Import playlist" and pick a valid `.m3u` file with tracks matching songs on the device. Verify the success dialog appears with the imported name and added song count, and the playlist is populated. ☐
7. **Import garbage file shows "couldn't read"**: pick an unreadable or corrupt file (e.g., a non-text binary or inaccessible file that fails stream reading or causes repository insertion failure). Verify an alert dialog displays "Couldn't read that file." rather than "No songs matched". ☐
