# Phase 11B-3 phone test checklist

Device: Oppo A1k

- ☐ Upgrade over v1: with the CI debug APK of main BEFORE this branch, create 2 playlists with 5+ songs each; then install this branch's CI debug APK OVER it (same debug signing) and confirm both playlists and their song order are intact.
- ☐ Add and reopen: add a song to a playlist, leave the screen and reopen it, verify the song is there in the right position.
- ☐ Re-link after the file returns: copy one playlist song's file out of Music and back (or re-download it), then open Playlists and check the song is back in the playlist in its old position. If its MediaStore id did not change, this test is inconclusive: say so.
- ☐ Remove, reorder, delete: remove a song from a playlist, reorder the remaining songs, then delete a playlist, verifying each step works.
- ☐ M3U round-trip: export a playlist to an M3U file and import it back, verifying the songs match.
- ☐ Clear-data start: clear the app's data and open it, verifying it starts clean with no crash and no leftover playlists.
