# Sur V1 execution state

Updated: 2026-11-19. Current phase: **1 complete → next: Phase 2 (Download lifecycle)**.

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
| 1 | PASS | (see git log) | Metadata integrity, details below |

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


## Unverified on device
Everything device-specific: playback lifecycle, deletion permission flow, migration on a real
v1 database, downloads on the A1k.
