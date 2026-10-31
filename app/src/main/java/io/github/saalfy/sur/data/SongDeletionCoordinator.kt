package io.github.saalfy.sur.data

import android.content.IntentSender
import io.github.saalfy.sur.data.info.CoverStore
import io.github.saalfy.sur.data.info.SongInfoRepository
import io.github.saalfy.sur.data.info.SongKeyResolver
import io.github.saalfy.sur.data.playlist.PlaylistRepository

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
) {
    suspend fun begin(songId: Long): SongDeleteStart {
        val key = keyResolver.resolveKey(songId)
        return when (val attempt = deleter.begin(songId)) {
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
        if (!confirmed || !deleter.finish(confirmation.songId, confirmation.mode)) return false
        cleanup(confirmation.songId, confirmation.key)
        return true
    }

    private suspend fun cleanup(songId: Long, key: String?) {
        queueSongRemover.remove(songId)
        playlistRepository.deleteEntriesForSong(songId)
        if (key != null) {
            val coverFile = infoRepository.get(key)?.coverFile
            infoRepository.reset(key)
            if (coverFile != null) coverStore.delete(coverFile)
        }
        artworkEvictor.evict(songId)
    }
}
