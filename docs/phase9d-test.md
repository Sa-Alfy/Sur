# Phase 9d manual test: Find cover & details redesign (Oppo A1k, Android 9)

Install `app/build/outputs/apk/debug/app-debug.apk`. Mark each ✅/❌.

1. **Open from the Songs menu**: tap ⋮ on a song row and choose "Find cover & details". On a fresh install the consent dialog appears first (Cancel does nothing; Allow opens the sheet and is not asked again). ☐
2. **Open from Now Playing**: play a song that has no cover. Verify a "Find cover & details" button shows on the placeholder art, and tapping it opens the same sheet. On a song that has art, verify no extra button shows. ☐
3. **Auto search, no typing**: verify the sheet shows "Looking for matches…" and then a match card (cover, title, artist, album · year, duration note) without typing anything. Tap "Use this match": verify the title, artist and cover update in the Songs list. ☐
4. **Cover only keeps my title and artist**: on another song, tap "Cover only". Verify only the cover changes and the title and artist stay as they were. ☐
5. **Not this one? and Search yourself**: tap "Not this one?". Verify other matches are listed; tapping one swaps the match card and loads its cover. Type "artist - title" (e.g. "billie eilish - bury a friend") into "Search yourself", tap Search and verify matching results. ☐
6. **No results and airplane mode**: search yourself for nonsense text and verify "Couldn't find this song" with the search field. Turn on airplane mode, reopen the sheet and verify "Couldn't reach the service". ☐
7. **Wide font and font scale 1.3**: set a wide system font and font size 1.3. Verify nothing in the sheet or Now Playing is squished, all buttons are full width and stacked, and the sheet scrolls to Close. ☐
