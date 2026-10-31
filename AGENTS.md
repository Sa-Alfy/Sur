# Sur: open-source Android music player
- Kotlin, Compose, Material 3. minSdk 26, targetSdk latest. Package io.github.saalfy.sur. GPL-3.0.
- Modules: :app, :downloader. Media3 playback in MediaSessionService. Playlists in Room. Local library via MediaStore.
- Test device: Oppo A1k (Android 9, 2GB RAM, aggressive battery killing). Keep it light.
- No accounts, cloud, ads, analytics.
- This machine is Windows (PowerShell). Use PowerShell-compatible commands (no tail, grep, unzip).

## Rules
- Do only what the task lists. Don't touch unrelated files. No new permissions or libraries unless the task names them.
- Never push, tag, release or change the version unless the task says so.
- Commit after each step, before starting the next. Message: "<phase>-<n>: <what>".
- Build: ./gradlew assembleDebug --console=plain 2>&1 | Select-Object -Last 15
- Tests: ./gradlew testDebugUnitTest --console=plain 2>&1 | Select-Object -Last 15. "UP-TO-DATE" is not a test run: use cleanTestDebugUnitTest first (or --rerun-tasks), and report tests run/failed from build/test-results.
- Never delete or weaken an existing test. If you must change one, say which and why.
- Every control must work, no untrue marketing text.
- Never tick test checkboxes.
- Never say something works unless you ran it. Paste the real command output. If you couldn't verify it, write "unverified".
- If a build or test fails, fix and rerun. If the same failure happens twice, STOP and report the error. Do not continue to later steps.
- Finish with 3-5 lines: files changed, build result, test result, what's unverified. Don't paste file contents.