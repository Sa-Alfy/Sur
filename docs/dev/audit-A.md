# Audit A: Find Info and Playlists + Room

## Verification of Earlier Audit Findings (Commit 60a9ae2)

| ID | Description | Status | Evidence |
|---|---|---|---|
| **F1** | `FindInfoViewModel.applySelected`: old cover file is deleted when new cover is null | **FIXED** | `app/src/main/java/io/github/saalfy/sur/ui/info/FindInfoViewModel.kt:304-314, 339-345`<br>Lines 304-314 preserve `existing?.coverFile` when `coverBytes == null` and abort on save failure. Lines 339-345 only delete `oldCoverFile` if `coverBytes != null && oldCoverFile != newCoverFile`. |
| **F2** | "Cover only" passes null title/artist/album, overwriting earlier title/artist override | **FIXED** | `app/src/main/java/io/github/saalfy/sur/ui/info/FindInfoViewModel.kt:319-322`<br>`title = if (coverOnly) existing?.title else candidate.title`, `artist = if (coverOnly) existing?.artist else candidate.artist`, `album = if (coverOnly) existing?.album else candidate.releaseTitle`. Prior metadata is preserved. |
| **F3** | Failed `CoverStore.save` is swallowed: title applied, cover not, no user message | **FIXED** | `app/src/main/java/io/github/saalfy/sur/ui/info/FindInfoViewModel.kt:308-311` & `FindInfoSheet.kt:167`<br>Save failure catches `Exception`, sets `searchState = SearchState.Error.CoverSaveFailed`, returns before calling `infoRepository.apply`, and displays `R.string.find_info_err_cover_save`. |
| **P1** | Playlist entries keyed on MediaStore song IDs; dead IDs hidden from lists/counts but stay in DB | **STILL TRUE** | `app/src/main/java/io/github/saalfy/sur/data/db/PlaylistEntities.kt:31-36` & `RoomPlaylistRepository.kt:27-30, 52-57`<br>`PlaylistEntryEntity` only stores `songId: Long`. `RoomPlaylistRepository.playlistSongs` filters via `mapNotNull { songs[entry.songId] }`, and summary comment confirms missing songs stay in DB. |
| **P2** | Both Room DBs (`sur.db`, `sur_info.db`) are version 1 with no migration or fallback strategy | **STILL TRUE** | `app/src/main/java/io/github/saalfy/sur/data/db/SurDatabase.kt:17-19` & `SurInfoDatabase.kt:17-23`<br>Both databases use `Room.databaseBuilder(...).build()` with no `.fallbackToDestructiveMigration()` and no migration rules. |

---

## 1. BLOCKER (Can lose or corrupt user data)

### B1: Unhandled exceptions in `PlaylistsViewModel` crash the application
- **Description**: Database write failures in playlist CRUD crash the app process due to naked coroutine launches.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/ui/playlists/PlaylistsViewModel.kt:72-76`
```kotlin
    fun create(name: String) = viewModelScope.launch { repository.create(name) }

    fun rename(id: Long, name: String) = viewModelScope.launch { repository.rename(id, name) }

    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }
```
- **Suggested Fix**: Wrap repository operations inside `viewModelScope.launch` in try-catch/runCatching and expose error events to the UI.

### B2: Unhandled exceptions in `PlaylistDetailViewModel` crash the application
- **Description**: Reordering or removing songs from a playlist crashes the app process if Room encounters an SQLite exception.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/ui/playlist/PlaylistDetailViewModel.kt:54-57`
```kotlin
    fun remove(entryId: Long) = viewModelScope.launch { repository.removeEntry(entryId) }

    fun move(fromEntryId: Long, toEntryId: Long) =
        viewModelScope.launch { repository.moveEntry(playlistId, fromEntryId, toEntryId) }
```
- **Suggested Fix**: Catch SQLite exceptions in `remove` and `move` coroutines and notify the user when an update fails.

### B3: Room databases crash on schema upgrade without migrations or fallback (P2)
- **Description**: Incrementing the version of `sur.db` or `sur_info.db` crashes the app on startup with `IllegalStateException` due to missing migration strategies.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/data/db/SurDatabase.kt:17-19`
```kotlin
    companion object {
        fun build(context: Context): SurDatabase =
            Room.databaseBuilder(context.applicationContext, SurDatabase::class.java, "sur.db").build()
    }
```
- **Suggested Fix**: Configure `.fallbackToDestructiveMigration()` or define explicit `Migration` paths on both database builders.

### B4: Re-downloading or re-indexing a track drops it from all user playlists (P1)
- **Description**: Re-downloading a deleted song or moving files causes MediaStore to assign a new `songId`, leaving dead entries in `playlist_entries` and removing the song from all user playlists.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/data/db/PlaylistEntities.kt:31-36` & `app/src/main/java/io/github/saalfy/sur/data/playlist/RoomPlaylistRepository.kt:28-30`
```kotlin
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)
```
- **Suggested Fix**: Reconcile playlist entries by durable file path or clean up orphaned entries when songs are removed.

---

## 2. SHOULD FIX

### S1: Non-atomic cover save in `DownloadService` leaks cover files on failure
- **Description**: `DownloadService` saves cover bytes to disk, but does not clean up the file if `songInfoRepository.apply` fails or if replacing an existing cover.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/download/DownloadService.kt:216-226`
```kotlin
val coverFileName = if (coverJpeg != null) {
    runCatching { container.coverStore.save(coverJpeg) }.getOrNull()
} else null
container.songInfoRepository.apply(
    key = key,
    title = info.title,
    artist = info.artist,
    album = null,
    coverFile = coverFileName,
    mbRecordingId = null,
)
```
- **Suggested Fix**: Delete newly written cover files if repository apply fails, and remove any previously active cover file on overwrite.

### S2: `DownloadService` unconditionally overwrites manual metadata overrides
- **Description**: Re-downloading a track overwrites user-curated title, artist, and cover overrides without warning or retention.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/download/DownloadService.kt:219-226`
```kotlin
container.songInfoRepository.apply(
    key = key,
    title = info.title,
    artist = info.artist,
    album = null,
    coverFile = coverFileName,
    mbRecordingId = null,
)
```
- **Suggested Fix**: Check `songInfoRepository.get(key)` first and avoid overwriting fields if the user has already applied an override.

### S3: Non-atomic cover save in `FindInfoViewModel` can orphan files on process termination
- **Description**: If the Android process is killed by the OS immediately after `coverStore.save` but before `infoRepository.apply`, the image file remains unreferenced on disk forever.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/ui/info/FindInfoViewModel.kt:306-318`
```kotlin
val newCoverFile = if (coverBytes != null) {
    try {
        coverStore.save(coverBytes)
    } catch (_: Exception) {
        _uiState.update { it.copy(searchState = SearchState.Error.CoverSaveFailed) }
        return@launch
    }
...
infoRepository.apply(...)
```
- **Suggested Fix**: Implement a background prune routine in `CoverStore` that deletes cover files having no matching record in `song_info`.

### S4: MediaStore variable bind limit fails M3U playlist export on playlists with >999 songs
- **Description**: Constructing an `IN (?, ?, ...)` clause with all song IDs in `MediaStoreSongFiles.query` triggers SQLite exceptions on Android when exceeding 999 parameters.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/data/playlist/m3u/MediaStoreSongFiles.kt:36-38`
```kotlin
        val placeholders = songIds.joinToString(",") { "?" }
        val selection = "${MediaStore.Audio.Media._ID} IN ($placeholders)"
        val selectionArgs = songIds.map { it.toString() }.toTypedArray()
```
- **Suggested Fix**: Batch queries in chunks of 500 IDs when fetching file paths from MediaStore.

### S5: Missing unit tests for playlist persistence, viewmodels, and Room schema migrations
- **Description**: `RoomPlaylistRepository`, `PlaylistsViewModel`, and `PlaylistDetailViewModel` lack unit test coverage for error conditions and entry reordering.
- **Evidence**: `app/src/test/java/io/github/saalfy/sur/data/playlist/`
```
PlaylistOrderTest.kt
PlaylistSummaryTest.kt
m3u/M3uMatcherTest.kt
m3u/M3uParserTest.kt
m3u/M3uWriterTest.kt
```
- **Suggested Fix**: Add test suites covering `RoomPlaylistRepository` transactions, ViewModel coroutine error handling, and Room database migrations.

---

## 3. LOW

### L1: Unhandled exceptions in `SongsViewModel.addToPlaylist`
- **Description**: Adding songs to an existing or newly created playlist does not catch database exceptions.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/ui/songs/SongsViewModel.kt:51-57`
```kotlin
    fun addToPlaylist(playlistId: Long, songIds: List<Long>) =
        viewModelScope.launch { playlistRepository.addSongs(playlistId, songIds) }

    fun addToNewPlaylist(name: String, songIds: List<Long>) = viewModelScope.launch {
        val id = playlistRepository.create(name)
        playlistRepository.addSongs(id, songIds)
    }
```
- **Suggested Fix**: Add try-catch blocks to prevent unhandled database exceptions when adding songs to playlists.

### L2: `FindInfoViewModel` pathless fallback key silently mismatches repository lookups
- **Description**: When `songKeyResolver.resolveKey` fails, fallback key drops the directory path, preventing `OverlaySongRepository` from matching the saved override.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/ui/info/FindInfoViewModel.kt:127-131`
```kotlin
            val key = try {
                songKeyResolver.resolveKey(song.id)
            } catch (_: Exception) {
                null
            } ?: SongInfoKey.buildKey(null, displayName ?: song.title)
```
- **Suggested Fix**: Abort or report key resolution failure instead of saving overrides under a pathless fallback key.

### L3: Inverted cleanup sequence in `SongDeletionCoordinator.cleanup`
- **Description**: Resetting the database row before deleting the cover file risks leaving the file on disk if execution is interrupted between calls.
- **Evidence**: `app/src/main/java/io/github/saalfy/sur/data/SongDeletionCoordinator.kt:66-68`
```kotlin
            val coverFile = infoRepository.get(key)?.coverFile
            infoRepository.reset(key)
            if (coverFile != null) coverStore.delete(coverFile)
```
- **Suggested Fix**: Delete the cover file from storage before resetting the database row.

---

## 4. UNVERIFIED

### U1: MediaStore ID collision after external storage reformatting
- **Description**: If external media is removed or reformatted, MediaStore may re-issue an existing ID to a different track, causing playlists to point to wrong songs.
- **Evidence**: Unverified without testing behavior on real devices with removable SD cards.
- **Suggested Fix**: Verify song duration and metadata against stored references before displaying or playing playlist entries.
