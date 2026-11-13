# CI

Workflow: `.github/workflows/ci.yml`, one job named `build` (keep that name: a required
check matches on it). Runs on pull requests, pushes to `main` and `fix-**`, and manually.

## What it checks
1. `./gradlew assembleDebug test`: debug build and all JVM unit tests.
2. `./gradlew lintDebug`: Android lint; findings in `app/lint-baseline.xml` are ignored, new ones fail.
3. `./gradlew minifyReleaseWithR8`: release shrinking without a signing key (catches missing keep rules).
4. `scripts/check-permissions.sh`: every permission in the merged release manifest must be
   listed in `scripts/permissions-allowlist.txt`.
Debug APK and test reports are uploaded (14 days); the lint report only when lint fails.

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
