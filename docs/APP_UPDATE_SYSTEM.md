# In-app update system

Sur is distributed as an APK on GitHub Releases, not through Google Play. The updater lets users
find, download, verify and install new stable releases from **Settings → App updates**.
Code: `app/src/main/java/io/github/saalfy/sur/update/`. Release procedure: [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md).

## Trust model
| What | Proves | Enforced by |
|---|---|---|
| Ed25519 signature on the manifest | The release info (version, URL, SHA-256, size) came from the maintainer's offline key | `Ed25519Verifier` (Bouncy Castle 1.86), key in `UpdateKeys` |
| SHA-256 + size of the APK | The downloaded file is exactly the one the signed manifest names (integrity) | `ApkDownloader` |
| Package name + versionCode inside the APK | The file really is the expected Sur build | `UpdateManager.checkIdentity` via `PackageManager.getPackageArchiveInfo` |
| APK signing certificate | The update comes from the same developer as the installed app | Android's package installer (always). Sur also compares certificates first when Android reports them for the archive, to give a clear error. |

The SHA-256 check is **not** a signature check of the APK; it only ties the file to the signed
manifest. APK authenticity is Android's job and cannot be bypassed: an APK signed with another
certificate can never replace the installed app.

GitHub (hosting) is not trusted for content: a modified manifest fails the signature, a modified
APK fails the SHA-256. A hostile host can only withhold updates or serve an older signed
manifest; older versions are never offered (versionCode must be higher than installed).

## Manifest
URL (fixed in `UpdateChecker.MANIFEST_URL`):
`https://github.com/Sa-Alfy/Sur/releases/latest/download/sur-update.json`
This is GitHub's documented "asset of the latest published release" link. Drafts and
pre-releases are never "latest", so only an intentionally published stable release is offered.

### Envelope (what is downloaded)
```json
{
  "keyId": "sur-update-2026-1",
  "payload": "<base64 of the payload JSON bytes>",
  "signature": "<base64 of the 64-byte Ed25519 signature over those exact bytes>"
}
```
Signing the exact payload bytes avoids any JSON canonicalisation. Nothing in the payload is read
before the signature verifies. Max envelope size 64 KiB.

### Payload, schema 1
| Field | Type | Rules |
|---|---|---|
| `schema` | int | must be `1`; anything else is rejected (old apps never guess at a new format) |
| `packageName` | string | must equal the installed package `io.github.saalfy.sur` |
| `channel` | string | must be `stable` |
| `versionCode` | int | 1..2100000000; compared against the installed versionCode (never versionName) |
| `versionName` | string | `[0-9A-Za-z][0-9A-Za-z.+-]{0,31}`, display only |
| `releaseDate` | string | ISO date `YYYY-MM-DD` |
| `minUpdatableVersionCode` | int, optional (1) | installed builds below this are told to reinstall by hand instead of updating in-app; 1..versionCode |
| `minSdk` | int, optional (1) | devices below this API level are not offered the update |
| `mandatory` | bool, optional (false) | shows a dismissible "required update" dialog once per app session. Never blocks music or the app, never installs anything by itself |
| `releaseNotes` | string, optional | plain text, max 10,000 chars, display only |
| `apk.url` | string | `https://github.com/Sa-Alfy/Sur/releases/download/...apk`; HTTPS only, host `github.com`, port 443, no credentials/query/fragment, no `..` |
| `apk.sha256` | string | 64 hex chars; required |
| `apk.size` | int | 256 KiB .. 200 MiB; download must match exactly |

Unknown extra fields are ignored. Rejections: `ManifestRejectedException.Reason`.

## Client behaviour
- **Startup**: `UpdateManager.restore()` re-verifies the cached manifest (no network) and deletes
  stale update files; then `checkIfDue()` checks at most every 12 h after a success and every 1 h
  after a failure. Runs in the app scope; never blocks the UI or playback. Auto-check failures
  are silent. Settings → "Check automatically" (default on) turns the start-up check off; this is
  the only network request Sur makes without a tap. It sends a plain GET to github.com with
  OkHttp's default User-Agent and no identifiers.
- **Manual**: Settings → Check for updates. Failures show a message (offline, DNS, timeout,
  TLS, HTTP code, 404 = nothing published yet, invalid manifest, bad signature) and keep any
  update already known.
- **Notice**: a snackbar (once per new versionCode), or for a mandatory update a dismissible
  dialog once per session. Neither pauses playback.
- **Download** (`ApkDownloader`): dedicated OkHttp client (no HTTPS→HTTP redirects, 15 s connect,
  30 s read timeouts), separate from the music downloader. Streams to
  `files/updates/sur-<versionCode>.apk.part`, then size + SHA-256 are checked and the file is
  renamed to `sur-<versionCode>.apk`. Names come only from the verified versionCode. One download
  at a time. A network drop keeps the `.part` and Retry resumes with an HTTP Range request
  (`Content-Range` must match exactly, otherwise it restarts from zero). A size or checksum
  mismatch deletes both files. Cancel deletes the partial file. Free space is checked first.
  If Android kills the app mid-download, the partial file stays and the next Download resumes.
- **Install**: right before handing over, the file is re-hashed and its package/versionCode (and
  certificate where available) are checked. If "Install unknown apps" is off for Sur, the user
  sees why it is needed and a button to the per-app Android setting; the state is rechecked on
  return. Install opens Android's installer via `ACTION_INSTALL_PACKAGE` with a
  `FileProvider` content URI (`${applicationId}.updates`, only `files/updates/` exposed,
  read grant per intent). Cancel / failure results are shown; success is only reported after
  the app restarts on the new versionCode (`pendingInstallVersionCode`).
- **Permissions added**: `REQUEST_INSTALL_PACKAGES` only. No silent install, no
  `INSTALL_PACKAGES`, no dynamic code loading, no scripts.

## Data preservation
An update is an in-place replace by Android with the same package and certificate, which keeps
the app's private data: the Room databases (playlists, song info), shared preferences and
covers. Database schema upgrades go through the tested Room migrations (`SurMigrations`, CI
`instrumented` job). The updater only creates and deletes `sur-<n>.apk[.part]` files inside
`files/updates/` (tested in `ApkDownloaderTest.cleanup_onlyTouchesUpdateFiles` and
`UpdateManagerTest.restartOnNewVersion_...`).

## Keys
- **Manifest signing key** (Ed25519): private key `D:/tools/keys/sur-update-ed25519.pem` on the
  maintainer's machine, created 2026-11-20 with `openssl genpkey -algorithm ed25519`. Not in git,
  CI secrets, the APK or resources. Public key (base64, 32 bytes) in `UpdateKeys.TRUSTED` under
  id `sur-update-2026-1`.
- **APK signing key**: unchanged, `D:/tools/keys/sur-release.jks`, certificate SHA-256
  `dad26ae786b2526a36b681f33ff3be1c6c761a806cef6c0da36e6865d7756f42`.

### Backup
Keep at least two offline copies of `sur-update-ed25519.pem` (e.g. encrypted USB + password
manager attachment), stored separately from the laptop. It is about 120 bytes of text; a paper copy also works.

### Rotation (planned)
1. Generate a new key; add its public key to `UpdateKeys.TRUSTED` under a new id, keeping the old.
2. Ship that release, signed with the **old** key.
3. Once most users run it, sign later manifests with the new key (`--key-id`).
4. Remove the old key from `UpdateKeys` in a later release.
Never replace a published key in place: apps that only know the old key would reject every
future update.

### Loss or compromise
- **Lost key**: existing installs can no longer verify new manifests. Publish a release with a
  new key in `UpdateKeys` and ask users (release notes, README) to install it once by hand from
  GitHub; in-app updates work again from then on.
- **Leaked key**: an attacker can sign manifests, but Android still refuses an APK that is not
  signed with Sur's release certificate, so they cannot install their own app over Sur. They can
  advertise old or broken Sur builds; old ones are filtered by versionCode. Rotate as for a lost
  key and say so in the release notes.

## Tests
`app/src/test/java/io/github/saalfy/sur/update/`: parser (signatures, schema, package, channel,
versions, URLs, checksum, size, malformed input, downgrade), checker (HTTP and network errors,
oversized body, non-HTTPS), downloader (verify, checksum/size mismatch, interruption + Range
resume, ignored range, duplicate download, cancel, cleanup scope), manager (cooldown, notices,
install permission, installer cancel/failure, tampered cache, file modified before install,
restart after update). `scriptGeneratedEnvelope_verifies` checks an envelope made by
`scripts/update_manifest.py` with a throwaway key, so tool and app agree on the format.
None of these prove an install on a device; see the checklist's phone test.
