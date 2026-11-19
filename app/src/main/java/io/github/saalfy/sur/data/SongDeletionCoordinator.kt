package io.github.saalfy.sur.data

import android.content.IntentSender
import android.util.Log
import io.github.saalfy.sur.data.info.CoverStore
import io.github.saalfy.sur.data.info.SongInfoRepository
import io.github.saalfy.sur.data.info.SongKeyResolver
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull

interface QueueSongRemover {
    suspend fun remove(songId: Long)
}

interface ArtworkEvictor {
    fun evict(songId: Long)
}

sealed interface SongDeleteStart {
    data object Deleted : SongDeleteStart
    data object PermissionRequired : SongDeleteStart
    data object Failed : SongDeleteStart
    data class Confirmation(
        val songId: Long,
        val key: String?,
        val intentSender: IntentSender?,
        val mode: DeleteConfirmationMode,
    ) : SongDeleteStart
}

class SongDeletionCoordinator(
    private val deleter: SongDeleter,
    private val keyResolver: SongKeyResolver,
    private val queueSongRemover: QueueSongRemover,
    private val playlistRepository: PlaylistRepository,
    private val infoRepository: SongInfoRepository,
    private val coverStore: CoverStore,
    private val artworkEvictor: ArtworkEvictor,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun begin(songId: Long): SongDeleteStart {
        val key = guard { keyResolver.resolveKey(songId) }
        // R2-08: a platform error building the delete request (or a missing request) is a
        // failure the user sees, not a crash or a silent no-op.
        val attempt = guard { deleter.begin(songId) } ?: return SongDeleteStart.Failed
        return when (attempt) {
            DeleteAttempt.Deleted -> {
                cleanup(songId, key)
                SongDeleteStart.Deleted
            }
            DeleteAttempt.PermissionRequired -> SongDeleteStart.PermissionRequired
            DeleteAttempt.Failed -> SongDeleteStart.Failed
            is DeleteAttempt.Confirmation -> SongDeleteStart.Confirmation(
                songId = songId,
                key = key,
                intentSender = attempt.intentSender,
                mode = attempt.mode,
            )
        }
    }

    suspend fun finish(confirmation: SongDeleteStart.Confirmation, confirmed: Boolean): Boolean {
        if (!confirmed) return false
        // R2-09: only a confirmed absence counts; UNKNOWN or an error is "not deleted".
        if (guard { deleter.finish(confirmation.songId, confirmation.mode) } != true) return false
        cleanup(confirmation.songId, confirmation.key)
        return true
    }

    /**
     * Runs only after a confirmed deletion. Each step is independent, so one failing step
     * does not skip the rest (R2-04, R2-12: the artwork cache is always evicted).
     * @return true when every step succeeded.
     */
    internal suspend fun cleanup(songId: Long, key: String?): Boolean {
        var ok = true
        ok = step { queueSongRemover.remove(songId) } && ok
        ok = step { playlistRepository.deleteEntriesForSong(songId) } && ok
        if (key != null) {
            val coverFile = guard { infoRepository.get(key) }?.coverFile
            // R2-07: reset the row first, then delete the cover. A row left pointing at a deleted
            // file could re-attach stale overrides to a new file at the same path; a cover file
            // left behind is harmless and is removed by the orphan sweep below.
            val reset = step { infoRepository.reset(key) }
            ok = reset && ok
            if (reset && !coverFile.isNullOrBlank()) {
                ok = step { deleteCoverIfUnreferenced(coverFile) } && ok
            }
        }
        step { artworkEvictor.evict(songId) }
        // Retries leftovers from earlier partial failures (or a cleanup lost to process death).
        step { sweepOrphanCovers() }
        return ok
    }

    private suspend fun deleteCoverIfUnreferenced(name: String) {
        val referenced = (infoRepository.observeAll().firstOrNull() ?: emptyList()).any { it.coverFile == name }
        if (!referenced) coverStore.delete(name)
    }

    /**
     * Deletes cover files no song-info row points at. Files younger than [ORPHAN_MIN_AGE_MS]
     * are kept: a download or Find info apply may have saved one and not yet written its row.
     */
    internal suspend fun sweepOrphanCovers(): Int {
        val stored = coverStore.list()
        if (stored.isEmpty()) return 0
        val referenced = (infoRepository.observeAll().firstOrNull() ?: emptyList()).mapNotNullTo(HashSet()) { it.coverFile }
        val cutoff = now() - ORPHAN_MIN_AGE_MS
        var removed = 0
        for (cover in stored) {
            if (cover.name !in referenced && cover.lastModified in 1..cutoff && coverStore.delete(cover.name)) {
                removed++
            }
        }
        return removed
    }

    private suspend inline fun step(block: () -> Unit): Boolean = guard { block(); true } ?: false

    private suspend inline fun <T> guard(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { Log.w("SurDelete", "deletion step failed", e) } // Log is a stub in JVM tests
        null
    }

    companion object {
        const val ORPHAN_MIN_AGE_MS = 60 * 60 * 1000L
    }
}
