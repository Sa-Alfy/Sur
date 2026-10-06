# Sur: open-source Android music player
- Kotlin, Jetpack Compose, Material 3. minSdk 26, targetSdk latest. Package io.github.saalfy.sur. GPL-3.0.
- Media3 ExoPlayer in MediaSessionService. Local library via MediaStore (READ_MEDIA_AUDIO on 13+, READ_EXTERNAL_STORAGE on ≤12).
- Playlists in Room. Home = playlists; Songs/Albums/Artists secondary.
- Dynamic color on 12+, static fallback on 8-11. Follow system dark/light.
- Test device: Oppo A1k (Android 9, 2GB RAM, aggressive battery killing). Keep it light.
- No accounts, cloud, ads, analytics. Downloader module comes later, not now.

## Working rules (token discipline)
- Build with: ./gradlew assembleDebug -q --console=plain 2>&1 | tail -40
- Never claim something works unless you ran it. Unverified = say "unverified".
- Don't re-read files you just wrote. Don't paste whole files back to me.
- Reply in 3-5 lines: what changed, build result, what's unverified. No summaries of the code.
- Stop at the end of each phase and wait.
