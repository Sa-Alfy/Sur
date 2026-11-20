# Sur V1 master plan

Stable scope and phase definitions. Current state lives in `V1_EXECUTION_STATE.md`.

## V1 scope (do not expand)
Local scan, search, background playback, notification/lock-screen controls, shuffle/repeat/seek,
queue, playlists (+persistence, M3U), MusicBrainz lookup, local metadata overrides, artwork,
YouTube audio download with progress/cancel, safe file handling, correct permissions, safe DB
upgrades, clear empty/loading/error states, reproducible release APK.
In-app updates from GitHub Releases (Phase 11, added 2026-11-20).
Out of scope: equalizer, cloud sync, lyrics, social, accounts, analytics.

## Invariants
- I1 Metadata: explicit user-owned metadata (text and cover) is never silently overwritten by
  provider data. Missing value != explicit clear.
- I2 Downloads: only the active download instance may update its progress, notification,
  terminal state or owned files. Cancellation is never reported as Done.
- I3 Deletion: UNKNOWN is not ABSENT. Cleanup only after confirmed deletion.
- I4 Playlists: an uncertain match stays unresolved; reconciliation is deterministic and idempotent.
- I5 Database: no destructive migration fallback; every schema version has a tested upgrade path.
- I6 Updates: nothing from the network is trusted until the manifest signature verifies; an APK
  is only offered for install after its size and SHA-256 match the signed manifest; never a
  downgrade, never a silent install; the manifest private key never enters git, CI or the APK.
- Tests must exercise production code, not test-local copies of the logic.

## Phases
| # | Name | Audit IDs | Acceptance |
|---|------|-----------|------------|
| 0 | Baseline and recovery | – | Existing work preserved, baseline documented |
| 1 | Metadata integrity | R2-01, cover precedence, R2-14 | I1 holds across re-download; production tests |
| 2 | Download lifecycle | R2-03, R2-05, R2-11, R2-13 | I2 holds; race/cancel tests |
| 3 | Deletion and artwork recovery | R2-06, R2-08, R2-09, R2-04, R2-07, R2-12 | I3 holds; partial failures retryable |
| 4 | Playlist identity | R1-02, R1-03, R1-04, R1-06, R2-18 | I4 holds; real DAO writes tested |
| 5 | Database migration safety | R1-01 | I5 holds; MigrationTestHelper run |
| 6 | Core playback and library | confirmed blockers only | Normal journeys work |
| 7 | Downloads and metadata UX | R2-16 + confirmed blockers | Downloads playable, no corruption |
| 8 | UI/UX completion | confirmed blockers only | No broken controls/layouts |
| 9 | Security and release config | – | No critical defect, reproducible release |
| 10 | Final validation | – | Full task run, release notes, single `main` |
| 11 | Online update system | added 2026-11-20 | Signed manifest (Ed25519) + SHA-256 verified APK, Android installer only, documented offline release signing; must ship in the next stable release before final V1 sign-off |

## Git policy
Sequential commits on `main`, pushed and verified after each phase. No force-push, no
`reset --hard`, no history rewrite. Delete a branch only after its contents are confirmed in `main`.

## Build notes (Windows)
Gradle fails with `Unable to establish loopback connection` unless the JDK's unix-socket temp
dir is short. Workaround: `$env:JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=D:\tmpsock"`.
