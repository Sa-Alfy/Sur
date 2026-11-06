# Room migrations

How to add the next migration (example: v2 -> v3):

1. Bump `SUR_DB_VERSION` in `SurDatabase.kt` (or the SurInfoDatabase version).
2. Write the `Migration` (e.g. `MIGRATION_2_3`) in `SurMigrations.kt` with the exact SQL.
3. Add it to `SurMigrations.ALL`, keeping the list in version order.
4. Build (`./gradlew assembleDebug`) so Room exports the new schema JSON under `app/schemas/...` and commit it.
5. Extend the contiguity test in `SurMigrationsTest.kt` if the chain changes.
6. Do the phone upgrade test in `docs/phase11b3-test.md` item 1: install the new APK over the old one and confirm the data survives.

Never use `fallbackToDestructiveMigration`: it silently deletes the user's playlists and song info instead of migrating them.
