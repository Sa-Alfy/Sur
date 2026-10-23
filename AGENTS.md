# Sur: open-source Android music player
- Kotlin, Compose, Material 3. minSdk 26, targetSdk latest. Package io.github.saalfy.sur. GPL-3.0.
- Modules: :app, :downloader. Media3 playback in MediaSessionService. Playlists in Room. Local library via MediaStore.
- Test device: Oppo A1k (Android 9, 2GB RAM, aggressive battery killing). Keep it light.
- No accounts, cloud, ads, analytics.

## Rules
- Do only what the task lists. Don't touch unrelated files. No new permissions or libraries without asking.
- Build: ./gradlew assembleDebug -q --console=plain 2>&1 | tail -40
- Tests: ./gradlew testDebugUnitTest -q --console=plain 2>&1 | tail -40
- Every control must work, no untrue marketing text.
- Never tick test checkboxes.
- Never say something works unless you ran it. Paste the real command output. If you couldn't verify it, write "unverified".
- If a build or test fails, fix and rerun. If the same failure happens twice, stop and report the error instead of trying a third guess.
- Finish with 3-5 lines: files changed, build result, test result, what's unverified. Don't paste file contents.