# Release checklist (stable)

Only an intentional, published stable release reaches users. Pushing to `main` never does; a
`v*` tag only creates a **draft** release. Keys: see [APP_UPDATE_SYSTEM.md](APP_UPDATE_SYSTEM.md#keys).

Needs: Android build-tools (`ANDROID_HOME`), OpenSSL 3, Python 3.8+, `gh` logged in.

## 1. Prepare
- [ ] `app/build.gradle.kts`: raise `versionCode` (must be higher than every published release)
      and set `versionName` (e.g. `1.1.0`).
- [ ] Write `docs/releases/release-<versionName>.md` (used as the GitHub release text and,
      trimmed to 10,000 chars, as the in-app release notes).
- [ ] `./gradlew testDebugUnitTest lintDebug assembleRelease` pass locally; CI green on `main`.

## 2. Build (CI)
- [ ] Commit, then `git tag v<versionName>` and `git push origin v<versionName>`.
- [ ] The Release workflow builds and signs the APK, checks package name, versionName, the
      release certificate (`dad26ae7…6f42`) and non-debuggable, and creates a **draft** release
      with `Sur-v<versionName>.apk` and its `.sha256`.

## 3. Sign the update manifest (offline, maintainer's machine)
```powershell
gh release download v1.1.0 -R Sa-Alfy/Sur -p "Sur-v1.1.0.apk" -D release-tmp
python scripts/update_manifest.py sign --apk release-tmp/Sur-v1.1.0.apk --tag v1.1.0 --notes docs/releases/release-1.1.0.md --key D:/tools/keys/sur-update-ed25519.pem --out release-tmp/sur-update.json
python scripts/update_manifest.py verify release-tmp/sur-update.json --apk release-tmp/Sur-v1.1.0.apk
```
The tool refuses to sign if the APK's package, versionName/tag, or signing certificate is
wrong, if the APK is debuggable, or if the key doesn't match the public key compiled into the app.
Options: `--mandatory`, `--min-updatable <versionCode>`, `--date YYYY-MM-DD`.
Always sign the APK **downloaded from the draft**, never a local build: the SHA-256 must match the
published file byte for byte.

## 4. Publish
- [ ] `gh release upload v1.1.0 release-tmp/sur-update.json -R Sa-Alfy/Sur`
- [ ] Review the draft on GitHub, then publish it (not as pre-release):
      `gh release edit v1.1.0 --draft=false --latest -R Sa-Alfy/Sur`
- [ ] Delete `release-tmp/` (git-ignored).

## 5. Verify what users get
```powershell
curl.exe -sSL -o check.json https://github.com/Sa-Alfy/Sur/releases/latest/download/sur-update.json
curl.exe -sSL -o check.apk https://github.com/Sa-Alfy/Sur/releases/download/v1.1.0/Sur-v1.1.0.apk
python scripts/update_manifest.py verify check.json --apk check.apk
```
- [ ] Signature OK, APK matches, versionCode/versionName as intended.

## 6. Phone test (A1k, Android 9)
- [ ] Install the **previous** release APK, create a playlist, add Find-info details, change a setting.
- [ ] Settings → Check for updates → new version, notes and size shown.
- [ ] Download: progress moves; Cancel works; Download again completes; "verified" shown.
- [ ] Install: first time, Android asks to allow installs from Sur → Open settings → allow → back → Install.
- [ ] Android's installer shows "update"; approve. Sur restarts on the new version and shows
      "Sur was updated to …".
- [ ] Playlists, details and settings are still there; music plays; music downloads still work.
- [ ] Also once: cancel in Android's installer → "Installation cancelled", Install works again.

## Never
- Publish a debug APK, or an APK not signed with the release key.
- Replace the APK of a published release (the manifest pins its SHA-256; the workflow refuses).
- Put the manifest key in git, CI secrets, the app, or a build file.
- Change `UpdateKeys` without following the rotation steps.
