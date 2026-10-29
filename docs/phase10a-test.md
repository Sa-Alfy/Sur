# Phase 10a manual test: Playlist export & import fix (Oppo A1k, Android 9)

Install `app/build/outputs/apk/debug/app-debug.apk`. Mark each ✅/❌.

1. **Export 90-song playlist on Android 9**: open a playlist containing 90 songs (including songs without artist tags, downloaded songs with titles like "Anuv Jain - ALAG AASMAN", and songs stored on internal storage). Tap overflow (⋮) -> "Export as M3U", choose a save location, and save. Verify the success dialog says "Exported 90 songs" and does NOT say "90 songs have no file path". ☐
2. **Import the exported playlist back**: on the Playlists tab, tap "Import playlist" and select the M3U file saved in step 1. Verify the playlist imports successfully and almost all songs are matched. ☐
3. **Import result dialog displays not-found list**: if any songs could not be matched, verify the import dialog shows a scrollable list with up to 10 song names and a "+N more" label if more than 10 are missing. ☐
4. **Scrollable dialog**: verify that when multiple missing songs are listed, the dialog content scrolls smoothly without clipping buttons or text. ☐
5. **Android 13+ phone check**: perform export and import of the playlist on an Android 13+ device to ensure SDK >= 29 behavior remains intact. ☐
