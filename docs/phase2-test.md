# Phase 2 manual test (Oppo A1k / any device)

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (or copy the APK over). Mark each ✅/❌.

1. **Deny permission**: fresh install, open Sur, deny audio access. Expect a message and an "Allow access" button. Deny again with "Don't ask again". Expect "Open settings", which opens Sur's app settings. Grant it there, go back, and the song list should appear.
2. **Library**: the list shows title, artist and duration. No ringtones, notification sounds, or clips under 30 s.
3. **Play**: tap the 3rd song. It plays, and the next song follows automatically when it ends.
4. **Deny notifications (Android 13+ only)**: on the first tap, deny the notification prompt. Music must still play.
5. **Screen off**: while playing, lock the phone for 5+ minutes. Audio continues without gaps.
6. **Notification controls**: pull down the shade. Play/pause, next and previous all work, and the title/artist are correct.
7. **Lock screen controls**: same controls work from the lock screen.
8. **Swipe from recents (playing)**: swipe Sur away while playing. Music continues and the notification stays. Then pause from the notification and swipe the notification away. The service should stop.
9. **Swipe from recents (paused)**: pause, then swipe Sur away. The notification disappears and nothing keeps running.
10. **Headphones / focus**: unplug wired headphones (or disconnect Bluetooth) while playing, and it pauses. Play, then start a video in another app, and Sur pauses or ducks.

Oppo note: if 5 or 8 fail, set Sur's battery setting to allow background activity and retest. Record which one it was.
