# Release v0.3.0

Sur v0.3.0 introduces a comprehensive redesign of the player experience, visual hierarchy improvements, artwork fallback, and downloader usability enhancements.

## What's New

### 1. Now Playing Redesign & Art Accents
- **Art-tinted palette**: The Now Playing screen extracts vibrant accent colors from the current album artwork via AndroidX Palette with a smooth color crossfade.
- **Adaptive layout**: Wrapped artwork and playback controls in responsive constraint containers, hiding the Up Next preview in landscape mode to prevent clipping and overflow on compact screens.
- **Queue integration**: Tapping the Up Next preview card directly opens the active playback queue.

### 2. Playlists & Library
- **2×2 cover mosaics**: Playlists with 4+ tracks now display a dynamic 2×2 artwork mosaic.
- **Cleaned song rows**: Redesigned list items with rounded thumbnails, visible animated level bars for active playback, and refined typography.
- **Removed dead UI**: Stripped fake format chips, decorative drag handles, and non-functional buttons.

### 3. Downloader Improvements
- **Live metadata & progress**: Progress card now displays song title and channel name during download.
- **Direct playback**: Added one-tap **Play** action once the audio download completes and syncs to the local library.
- **Clearer error handling**: Meaningful error messages with direct **Try again** and **Dismiss** actions.
- **Embedded artwork fallback**: Artwork loader falls back to ID3/MP4 embedded picture bytes via `MediaMetadataRetriever` if MediaStore thumbnails are unavailable.

### 4. Accessibility & Visual Hierarchy
- **WCAG contrast compliance**: Verified color contrast for text, icons, and surfaces in both light and dark themes.
- **Calm teal fallback**: High-contrast, non-purple theme fallback on Android 8–11 devices without dynamic color support.

## Verification & Testing
- Unit test suite: 62 tests passing (`./gradlew testDebugUnitTest`).
- Release minification: R8 / ProGuard verified (`./gradlew assembleRelease`).
- Manual test checklist: see [`docs/phase6b-test.md`](phase6b-test.md).
