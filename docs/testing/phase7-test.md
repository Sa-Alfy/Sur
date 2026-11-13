# Phase 7 manual test: playlist M3U backup (export & import) (Oppo A1k, Android 9)

Install `app/build/outputs/apk/debug/app-debug.apk`. Test playlist export and import using SAF. Mark each ✅/❌.

1. **Export playlist via SAF**: open a playlist with songs. Tap the overflow menu (⋮) in the top app bar and select "Export as M3U". The system file picker opens via `CreateDocument`. Choose a save location and confirm. An alert dialog displays the number of songs exported. ☐
2. **Exported file format**: inspect the exported `.m3u` file in a text editor or file manager. Verify it begins with `#EXTM3U`, contains `#PLAYLIST:<name>`, and contains `#EXTINF:<seconds>,<artist> - <title>` preceding each song path entry. ☐
3. **Weak path warning on API < 29**: on Android 9 (Oppo A1k), where `RELATIVE_PATH` is unavailable in MediaStore, verify the export completion dialog alerts that exported songs have no file path and may not import back on a different device structure. ☐
4. **Import playlist (exact match)**: on the Playlists screen, tap "Import playlist" and pick an exported `.m3u` file whose songs exist on the device. Verify a success dialog appears with the song count added, and the new playlist appears in the list with the correct songs. ☐
5. **Import name conflict**: import an M3U file whose playlist name already exists (e.g., "Favorites"). Verify the import creates a new playlist with a deduplicated name (e.g., "Favorites (2)") and does not overwrite or merge into the existing playlist. ☐
6. **Import with missing songs**: import an M3U file where some referenced songs are missing from the device library. Verify the dialog shows the count of added songs alongside the count of songs not found. The playlist is created with only the found songs. ☐
7. **Import with no matching songs**: import an M3U file containing songs entirely absent from the device library. Verify the dialog displays "No songs matched. Is this an M3U file for music on this device?" and no empty playlist is created. ☐
8. **File size limit (2 MB cap)**: attempt to import a file larger than 2 MB. Verify the operation aborts and displays "File is too large (over 2 MB). Not an M3U playlist?". ☐
9. **Import from empty state**: delete all playlists so the empty state is displayed. Verify the "Import playlist" button appears alongside "Create playlist", and tapping it opens the file picker and imports successfully. ☐
