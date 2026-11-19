# Sur V1 execution state

Updated: 2026-11-19. Current phase: **6 complete → next: Phase 7 (Downloads and metadata experience)**.

## Repository state at Phase 0
- `origin/main` = 98b0bd1. `phase-11b3` (PR #2, open, mergeable) = 53c6dc2 + Phase 0 checkpoint.
  `main` is an ancestor of `phase-11b3`, so `main` can fast-forward.
- Batch 1 (Room v2 migration, `songKey`, `planReconcile`, DAO/VM wiring): committed on
  `phase-11b3` (dcabc40..53c6dc2). Not in `main` before Phase 0.
- Batch 2 remediation: was uncommitted on `phase-11b3`; committed as the Phase 0 checkpoint.
  Touches SongDeleter, SongDeletionCoordinator, OverlaySongRepository, DownloadService,
  FindInfoViewModel/Sheet. Code comments reference R2-01/02/03/06/07/13/14/18, R1-01/06.
- No R1/R2 audit report exists in the repo; only `docs/dev/audit-A.md` (F1-F3, P1...). The R-IDs
  come from earlier chat audits and are treated as leads, not facts.
- Other remote branches: `licenses-screen` (PR #12, draft, 14A licenses, not in main),
  3 open dependabot PRs (#9 compose plugin, #10 gradle wrapper, #11 okhttp).

## Baseline (Phase 0)
| Check | Result |
|---|---|
| `testDebugUnitTest` (app + downloader, incl. Batch 2 tests) | PASS: 294 tests, 0 failures, 2 skipped |
| `assembleDebug` | PASS |
| `:app:compileDebugAndroidTestKotlin` | FAIL: androidx.test deps and `testInstrumentationRunner` missing |
| `connectedDebugAndroidTest` | BLOCKED: no adb/device/emulator on this machine |
| lint, release build | NOT RUN (Phase 9/10) |


## Phase log
| Phase | Status | Commit | Notes |
|---|---|---|---|
| 0 | PASS | a817177 | Checkpoint of Batch 2 + V1 docs |
| 1 | PASS | f33d325 | Metadata integrity, details below |
| 2 | PASS | 3d2e9cc | Download lifecycle, details below |
| 3 | PASS | d04c497 | Deletion and artwork recovery, details below |
| 4 | PASS | f759ee0 | Playlist identity, details below |
| 5 | PASS | bbcada7, f735ff1, b9db57d | Migration safety, details below |
| 6 | PASS (code) | (see git log) | Core playback and library, details below |

## Phase 1: Metadata integrity
- R2-01 + cover precedence: download overlay merge moved from `DownloadService` into
  `data/info/DownloadOverlay.kt` (`applyDownloadOverlay`). APPLIED row keeps title, artist,
  album, MBID and cover; provider only fills blank text or a missing cover. SKIPPED row is left
  alone. Unreadable row → nothing written (was: treated as absent and overwritten). Thumbnail
  saved only when used; deleted if the DB write fails.
- R2-14: already fixed in Batch 2 and covered by FindInfoViewModel tests. Added: apply reads
  the row fresh instead of the copy cached when the sheet opened.
- Clear vs missing: the data model has no per-field clear. Null/blank = "no override, show the
  file tag"; a full clear is `reset` (row deleted). Documented in `DownloadOverlay.kt`.
- Tests: removed `DownloadMetadataPreservationTest` (tested a copy). Added `DownloadOverlayTest`
  (11, production function + `RoomSongInfoRepository`) and one stale-row VM test.
  Full `testDebugUnitTest`: 296 pass / 0 fail / 2 skipped. `assembleDebug`, `lintDebug`: PASS.
- Unverified: re-download on a device; the new stale-row test was not run against the old code.

## Phase 2: Download lifecycle
- R2-11/R2-05 (real bug): the progress callback could fire once more after cancel, setting
  state back to Downloading with no job (stuck UI) and re-posting the notification. Fix:
  `DownloadManager` now has per-run `DownloadToken` ownership; every service state change goes
  through `update(token, …)`; the progress notification is posted inside the same lock.
  Terminal states and `cancelActive()` release ownership. `begin()` takes over a leaked owner
  so a start can never be stuck as Busy. `reset()` (UI dismiss) is ignored while a run is active.
- R2-05: `startForeground` failure (e.g. ForegroundServiceStartNotAllowedException) left state
  Resolving forever; now reports Error and stops. `stopSelf(lastStartId)` instead of
  `stopSelf()` so a stop never kills a newer start.
- R2-03: Done goes through the token, so it is never shown after a cancel.
- R2-13: verified already fixed (thumbnail/tag/overlay rethrow CE; `mapError` keeps CE;
  `withContext` rethrows on return). No change.
- Tests: `DownloadOwnershipTest` (8, incl. 50-iteration cancel/restart race against a live
  callback loop). Full `testDebugUnitTest`: 304 pass / 0 fail / 2 skipped. `assembleDebug`,
  `lintDebug`: PASS.
- Known, not fixed: cancel arriving during `AudioFileSaver.save` can leave the saved song in
  the library while the UI shows Idle (file is valid, not corrupt). A start intent with a blank
  link that stops without `startForeground` may hit the O+ foreground-start timeout (edge case,
  only via external intents). Neither verified on a device.
## Phase 3: Deletion and artwork recovery
- R2-08 (latent bug; corrected in Phase 6): `PermissionRequired` set `permissionSongId` but
  never launched the permission request, so delete would silently do nothing. In practice the
  startup `AudioPermissionGate` already requests WRITE_EXTERNAL_STORAGE on Android 8-9, so this
  path is normally unreachable on the A1k; the fix is defensive. `SongsScreen` now requests WRITE_EXTERNAL_STORAGE and retries on grant;
  `permissionSongId` is `rememberSaveable` (survives rotation). A confirmation with no system
  IntentSender now shows "delete failed" instead of waiting forever.
- R2-08: `SongDeletionCoordinator.begin/finish` catch deleter/platform errors (e.g.
  `createDeleteRequest` throwing) and report Failed instead of crashing the UI scope.
- R2-06 / R2-09: verified already fixed (null cursor = UNKNOWN; only CONFIRMED_ABSENT cleans).
- R2-04 / R2-12: cleanup steps are independent; one failing step no longer skips the rest, and
  the artwork cache is always evicted.
- R2-07: order is now row reset → cover delete (a row never points at a missing file, so stale
  overrides can't re-attach to a new file at the same path). Cover is deleted only if no other
  row references it. Leftover covers (partial failure, process death) are removed by
  `sweepOrphanCovers()`, which runs after every deletion and skips files < 1 h old (a download
  or Find info apply may not have written its row yet). `FileCoverStore` rejects path-like names.
- Tests: `SongDeletionCoordinatorTest` +8 (failing steps, reset failure, shared cover, deleter
  throws, user cancel, orphan sweep age/reference rules). Full `testDebugUnitTest`: 311 pass /
  0 fail / 2 skipped. `assembleDebug`, `lintDebug`: PASS.
- Not fixed: if the activity is recreated while the Android 11+ delete dialog is open,
  `pendingDelete` (holds an IntentSender) is lost; the file is deleted by the system but the
  playlist/overlay cleanup is skipped. The orphan sweep covers the cover file; playlist entries
  fall to Phase 4 reconciliation. Unverified on device.
## Phase 4: Playlist identity
- Schema v3 (`MIGRATION_2_3`, additive `ALTER TABLE ADD COLUMN` only): `playlist_entries`
  gains `songVolume`, `songDurationMs`, `relinkedFromSongId`. `3.json` exported.
  Released v0.3.5 is schema v1; upgrade path is 1→2→3.
- R1-02: `SongKeyResolver.resolveIdentities()` returns key + volume + duration. Android 10+:
  `VOLUME_NAME`; Android 8-9: derived from `DATA` (`volumeFromAbsolute`, same names as 10+).
  `SongInfoKey` (overlay DB key) is unchanged, so no song-info migration was needed.
- R1-03: a dead entry is re-linked only if exactly one song has the same key, a compatible
  volume (equal, or unknown on either side) and a duration within 1 s. A missing stored or
  candidate duration, or 0 / 2+ candidates, leaves the entry unresolved.
- R1-04: `relinkedFromSongId` keeps the first pre-relink song id (`COALESCE` in the DAO).
- R2-18: verified already fixed in Batch 2 (keys resolved on every combine, no cache).
- R1-06: real DAO writes are only testable with a device/emulator (androidTest). Planner tests
  cover determinism and idempotence. `PlaylistDaoIntegrationTest` still uses the old
  `reconcile(Map<Long,String>)` API and does not compile; it is fixed in Phase 5. NOT RUN.
- Tests: `PlaylistReconcileTest` rewritten (17), `SongKeyResolverTest` +2. Full
  `testDebugUnitTest`: 320 pass / 0 fail / 2 skipped. `assembleDebug`, `lintDebug`: PASS.
- Remaining identity limits: a different song with the same file name, volume and length
  (within 1 s) could still be re-linked. Entries that were dead before the upgrade have no
  stored duration and stay unresolved (shown as missing, not deleted). Android 9 → 10 OS
  upgrade relies on derived volume names matching `VOLUME_NAME`; unverified.
## Phase 5: Database migration safety
- Schema history: sur.db v1 (shipped in v0.3.5) → v2 → v3; sur_info.db v1. Shipped identity
  hashes of both v1 schemas equal the committed `1.json` files (no silent schema drift).
- Registration: `SurDatabase.build` adds `SurMigrations.ALL` (1→2, 2→3); `SurInfoDatabase`
  adds the empty `SurInfoMigrations.ALL`. No `fallbackToDestructiveMigration` anywhere.
- androidTest fixed: `testInstrumentationRunner`, androidx.test ext-junit 1.3.0 + runner 1.7.0.
  Room Gradle plugin bundles all schema JSONs into the test APK (checked in the APK).
- New `SurMigrationTest` (MigrationTestHelper): 1→2, 2→3, and 1→current opened through Room +
  DAO with order preserved and first reconcile filling identities.
  `PlaylistDaoIntegrationTest` rewritten for the v3 identity API (9 tests: backfill, relink +
  COALESCE original id, reused path, other volume, duplicate drop, idempotence, delete
  compaction, move, add de-dup).
- JVM guards in `SurMigrationsTest` (+4): every schema version exported, v3 adds exactly the
  migrated columns, no destructive fallback in `src/main`, 2→3 versions.
- Results: `assembleDebugAndroidTest` PASS (compiles). `testDebugUnitTest` 324 pass / 0 fail /
  2 skipped. `assembleDebug`, `lintDebug` PASS.
  `connectedDebugAndroidTest`: no local device (no cable for the A1k; Android 9 has no
  wireless debugging). Runs in CI instead: new `instrumented` job in `.github/workflows/ci.yml`
  boots an API 28 x86_64 emulator (same OS as the A1k) on every push/PR.
  - First CI run (f735ff1): PlaylistDaoIntegrationTest 9/9 PASS; SurMigrationTest 3/3 FAIL with
    `AbstractMethodError` in kotlinx.serialization (room-migration needs json 1.8.1, app had
    core 1.7.3 from lifecycle; test APK must use app versions).
  - Fix b9db57d: constraint `kotlinx-serialization-core` 1.8.1 in the app. Local unit tests,
    debug build, test APK, lint and `minifyReleaseWithR8` pass.
  - CI run 44511835545 (b9db57d): build PASS, instrumented PASS:
    PlaylistDaoIntegrationTest 9/9, SurMigrationTest 3/3 (1→2, 2→3, 1→3 via Room + DAO).
  - Still unverified: the upgrade on the real A1k with a real v0.3.5 database.
## Phase 6: Core playback and library
- Audited (code inspection, no defect found): `PlaybackService` (ExoPlayer with audio focus,
  becoming-noisy pause, local wake lock, Media3 notification + lock-screen session, mediaPlayback
  FGS type), skip-unplayable-items listener, `PlayerConnection` lifecycle (controller released
  in onStop so the service can stop), shuffle / repeat / seek / queue in `PlayerUiState`,
  search (`filterSongs`, title/artist/album, case-insensitive), A-Z sort, `AudioPermissionGate`
  (asks READ_MEDIA_AUDIO 13+, READ_EXTERNAL_STORAGE 10-12, WRITE_EXTERNAL_STORAGE 8-9; re-checks
  on resume; permanent-denial path opens Settings), delete → queue removal.
- Fixed (confirmed crash path): `MediaStoreSongRepository.songs` rethrew any non-Security
  query exception (e.g. CursorWindowAllocationException under memory pressure, SQLiteException
  during a media scan) inside the app-wide scope → app crash; a SecurityException completed the
  shared flow so refresh stopped working. New `libraryQueries()` keeps the last good list on
  error, emits empty on SecurityException, and keeps listening. `LibraryQueriesTest` (3).
- Results: `testDebugUnitTest` 327 pass / 0 fail / 2 skipped. `assembleDebug`, `lintDebug` PASS.
- Known limitations (not V1 blockers, not changed): the queue is not persisted, so if Android
  kills the process while paused (likely on the A1k) the queue is gone on next launch;
  `onPlaybackResumption` (Android 13+ system resume card) is not implemented.
- Unverified (needs the phone): background playback with screen off, notification and
  lock-screen controls, headphone unplug, audio focus with calls/other apps, ColorOS battery
  killing the service, library refresh after adding files.

## Unverified on device
Everything device-specific: playback lifecycle, deletion permission flow, migration on a real
v1 database, downloads on the A1k.
