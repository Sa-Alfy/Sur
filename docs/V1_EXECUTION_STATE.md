# Sur V1 execution state

Updated: 2026-11-19. Current phase: **3 complete → next: Phase 4 (Playlist identity)**.

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

## Known gaps found at baseline (not fixed in Phase 0)
- androidTest setup incomplete; `PlaylistDaoIntegrationTest` also hosts the migration test;
  schemas not wired as androidTest assets. → Phase 5.

## Phase log
| Phase | Status | Commit | Notes |
|---|---|---|---|
| 0 | PASS | a817177 | Checkpoint of Batch 2 + V1 docs |
| 1 | PASS | f33d325 | Metadata integrity, details below |
| 2 | PASS | 3d2e9cc | Download lifecycle, details below |
| 3 | PASS | (see git log) | Deletion and artwork recovery, details below |

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
- R2-08 (real bug, hits Android 8-9 incl. the A1k): `PermissionRequired` set
  `permissionSongId` but never launched the permission request, so delete silently did
  nothing. `SongsScreen` now requests WRITE_EXTERNAL_STORAGE and retries on grant;
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

## Unverified on device
Everything device-specific: playback lifecycle, deletion permission flow, migration on a real
v1 database, downloads on the A1k.
