package io.github.saalfy.sur.data.info

import kotlinx.coroutines.CancellationException

/**
 * Writes the song-info overlay for a freshly downloaded file.
 *
 * Precedence (R2-01): user-owned data beats provider (download) data.
 * - No row: provider title/artist and thumbnail cover are applied.
 * - SKIPPED row: the user dismissed Find info for this song; the row is left untouched.
 * - APPLIED row: existing non-blank title/artist, album, MusicBrainz ID and cover are kept.
 *   Provider values only fill a blank title/artist or a missing cover. A null field means
 *   "no override" (the file tag shows), never an explicit clear, so filling it is safe.
 *
 * The thumbnail is saved only when it will actually be used, and removed again if the
 * database write fails, so no orphaned cover file is left behind.
 *
 * @return true when a row was written.
 */
suspend fun applyDownloadOverlay(
    repository: SongInfoRepository,
    covers: CoverStore,
    key: String,
    title: String,
    artist: String?,
    thumbnail: ByteArray?,
): Boolean {
    val existing = try {
        repository.get(key)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        // UNKNOWN is not ABSENT: without the current row we cannot honour user overrides.
        return false
    }
    if (existing?.status == SongInfoStatus.SKIPPED) return false
    val applied = existing?.takeIf { it.status == SongInfoStatus.APPLIED }

    val keptCover = applied?.coverFile?.takeIf { it.isNotBlank() }
    val newCover = if (keptCover == null && thumbnail != null) {
        try {
            covers.save(thumbnail)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    } else null

    try {
        repository.apply(
            key = key,
            title = applied?.title?.takeIf { it.isNotBlank() } ?: title,
            artist = applied?.artist?.takeIf { it.isNotBlank() } ?: artist,
            album = applied?.album,
            coverFile = keptCover ?: newCover,
            mbRecordingId = applied?.mbRecordingId,
        )
    } catch (t: Throwable) {
        if (newCover != null) runCatching { covers.delete(newCover) }
        throw t
    }
    return true
}
