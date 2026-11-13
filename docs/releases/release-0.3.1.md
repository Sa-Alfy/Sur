# Release v0.3.1

Smoother on slow phones, and back on Sur's release signing key.

## What's new
- **Smoother UI on low-end devices**: the APK now ships a much larger Baseline Profile (about 21,000 methods covering Sur, Compose, Media3 and coroutines), so Android compiles that code ahead of time instead of interpreting it. Scrolling and screen changes should stutter less, especially on Android 8–10 phones with 2–3 GB RAM. No features changed.

## ⚠️ Updating from v0.3.0
v0.3.0 was accidentally signed with a development key. v0.3.1 goes back to Sur's release key (the same one as v0.1.0 and v0.2.0, fingerprint below).
- **Coming from v0.3.0**: Android will refuse to update. Uninstall v0.3.0 first, then install v0.3.1. Uninstalling **deletes your playlists**; your music files are not touched.
- **Coming from v0.2.0 or v0.1.0**: install over it as usual. Nothing is lost.

Release builds now refuse to build without the release key, so this can't happen again.

Signing certificate SHA-256: `DA:D2:6A:E7:86:B2:52:6A:36:B6:81:F3:3F:F3:BE:1C:6C:76:1A:80:6C:EF:6C:0D:A3:6E:68:65:D7:75:6F:42`

## Tip
For a sideloaded APK, Android applies the speed-up in the background while the phone is idle and charging, so it may take a day to kick in. With USB debugging you can apply it right away:
`adb shell cmd package compile -m speed-profile -f io.github.saalfy.sur`
