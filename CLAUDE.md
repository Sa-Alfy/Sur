# Sur: open-source Android music player
- Kotlin, Jetpack Compose, Material 3. minSdk 26, targetSdk latest. Package io.github.saalfy.sur. GPL-3.0.
- Media3 ExoPlayer in MediaSessionService. Local library via MediaStore (READ_MEDIA_AUDIO on 13+, READ_EXTERNAL_STORAGE on ≤12).
- Playlists in Room. Home = playlists; Songs/Albums/Artists secondary.
- Dynamic color on 12+, static fallback on 8-11. Follow system dark/light.
- Test device: Oppo A1k (Android 9, 2GB RAM, aggressive battery killing). Keep it light.
- No accounts, cloud, ads, analytics. Downloader lives in the :downloader module; network only for user-started downloads and opt-in MusicBrainz Find info lookups.

## Working rules (token discipline)
- Build (Linux/macOS): `./gradlew assembleDebug -q --console=plain 2>&1 | tail -40`
- Build (Windows PS): `./gradlew assembleDebug -q --console=plain 2>&1 | Select-Object -Last 40`
- Tests: same but `testDebugUnitTest` instead of `assembleDebug`.
- Never claim something works unless you ran it. Unverified = say "unverified".
- Don't re-read files you just wrote. Don't paste whole files back to me.
- Reply in 3-5 lines: what changed, build result, what's unverified. No summaries of the code.
- Stop at the end of each phase and wait.

## Recent phases
- v1.0.0 prepared on main (V1 phases 0-10, see docs/V1_EXECUTION_STATE.md): metadata precedence, download ownership/retry/resume, safe deletion + orphan cover sweep, playlist identity (DB v3: volume, duration, original id), migration tests in CI on an API 28 emulator.
- Windows Gradle needs JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=D:\tmpsock -Djava.net.preferIPv4Stack=true".
- Release key lives at D:/tools/keys/sur-release.jks (user ~/.gradle/gradle.properties); `./gradlew assembleRelease` signs locally. GitHub releases: .github/workflows/release.yml (repo secrets SUR_RELEASE_*).
- Next: phone tests on the A1k (docs/V1_EXECUTION_STATE.md phone checklist).
