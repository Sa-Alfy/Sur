# CI

Workflow: `.github/workflows/ci.yml`, jobs `build` (keep that name: a required check matches
on it) and `instrumented`. Runs on pull requests, pushes to `main` and `fix-**`, and manually.

## What it checks
1. `./gradlew assembleDebug test`: debug build and all JVM unit tests.
2. `./gradlew lintDebug`: Android lint; findings in `app/lint-baseline.xml` are ignored, new ones fail.
3. `./gradlew minifyReleaseWithR8`: release shrinking without a signing key (catches missing keep rules).
4. `scripts/check-permissions.sh`: every permission in the merged release manifest must be
   listed in `scripts/permissions-allowlist.txt`.
Debug APK and test reports are uploaded (14 days); the lint report only when lint fails.

## Instrumented job
Boots an API 28 x86_64 emulator (Android 9, same as the A1k test phone) with KVM via
`reactivecircus/android-emulator-runner` and runs `./gradlew connectedDebugAndroidTest`:
`SurMigrationTest` (Room MigrationTestHelper, every upgrade path) and
`PlaylistDaoIntegrationTest` (real DAO writes). Results are uploaded as
`instrumented-test-results`. Adds roughly 5-10 minutes, in parallel with `build`.

## Release workflow
`.github/workflows/release.yml` builds the signed release APK and creates (or updates) the
GitHub release for a tag. It runs on a pushed `v*` tag, or by hand: Actions -> Release ->
Run workflow, with an existing tag such as `v1.0.0` (needed for tags created before the
workflow existed). Release notes come from `docs/releases/release-<version>.md`; the APK and a
`.sha256` file are attached. The keystore is decoded into the runner's temp dir and deleted
at the end.

Set these repository secrets once (Settings -> Secrets and variables -> Actions), from the
machine that has the key:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\tools\keys\sur-release.jks")) | gh secret set SUR_RELEASE_KEYSTORE_BASE64
gh secret set SUR_RELEASE_STORE_PASSWORD
gh secret set SUR_RELEASE_KEY_ALIAS
gh secret set SUR_RELEASE_KEY_PASSWORD
```

(`gh secret set NAME` without a value prompts for it, so passwords never land in shell
history.) If a secret is missing the workflow stops at "Check signing secrets". Always check
that the "Verify signature" step prints certificate SHA-256 `dad26ae7...756f42` (same as the
README fingerprint).

## Update the permission allowlist
Only when a permission is added on purpose. In the same PR: add the name to
`scripts/permissions-allowlist.txt` (one per line), update the README permissions list,
and tick "Permissions or manifest" in the PR template. Check locally with
`./gradlew :app:processReleaseMainManifest` then `bash scripts/check-permissions.sh`.

## Regenerate the lint baseline
Only in a PR titled "lint baseline", never mixed into a feature PR. The baseline should only shrink.
`./gradlew updateLintBaseline`, then commit `app/lint-baseline.xml`.

## Dependabot
`.github/dependabot.yml`: weekly Gradle and GitHub Actions updates, no auto-merge.
