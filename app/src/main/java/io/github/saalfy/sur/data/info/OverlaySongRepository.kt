package io.github.saalfy.sur.data.info

import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure function: applies an override to a song.
 * APPLIED rows: replace title/artist/album only when the override value is non-null and non-blank;
 * SKIPPED rows and no row: return the song unchanged.
 */
fun applyOverride(song: Song, info: SongInfoEntity?, coverFile: File? = null): Song {
    if (info == null || info.status != SongInfoStatus.APPLIED) return song
    return song.copy(
        title = info.title?.takeIf { it.isNotBlank() } ?: song.title,
        artist = info.artist?.takeIf { it.isNotBlank() } ?: song.artist,
        album = info.album?.takeIf { it.isNotBlank() } ?: song.album,
        coverFile = coverFile ?: song.coverFile,
    )
}

/**
 * Pure function: returns song IDs whose cover file name changed, appeared or disappeared.
 */
fun changedCoverIds(old: Map<Long, String?>, new: Map<Long, String?>): Set<Long> {
    val allIds = old.keys + new.keys
    val changed = mutableSetOf<Long>()
    for (id in allIds) {
        if (old[id] != new[id]) {
            changed.add(id)
        }
    }
    return changed
}

/**
 * Decorates [delegate] with overrides from [info] using keys from [keys].
 * If the info repository or key query fails, falls back to plain songs without crashing.
 */
class OverlaySongRepository(
    private val delegate: SongRepository,
    private val keys: SongKeyResolver,
    private val info: SongInfoRepository,
    private val covers: CoverStore,
) : SongRepository {

    private val coverFilesSnapshot = AtomicReference<Map<Long, File>>(emptyMap())
    private val coverNamesSnapshot = AtomicReference<Map<Long, String?>>(emptyMap())

    var onCoversChanged: ((Set<Long>) -> Unit)? = null

    /** Exposes cover file for [songId] read from an AtomicReference snapshot (no locking on main thread). */
    fun coverFor(songId: Long): File? = coverFilesSnapshot.get()[songId]

    override val songs: Flow<List<Song>> = flow {
        var lastDelegateSongs: List<Song>? = null
        var cachedKeyMap: Map<Long, String> = emptyMap()

        val safeInfoFlow: Flow<List<SongInfoEntity>> = try {
            info.observeAll()
                .catch { e ->
                    logWarning("Failed to observe song info, emitting empty info", e)
                    emit(emptyList())
                }
        } catch (e: Exception) {
            logWarning("Failed to get song info flow, using empty info", e)
            flowOf(emptyList())
        }

        delegate.songs.combine(safeInfoFlow) { delegateSongs, infoList ->
            val keyMap = if (delegateSongs == lastDelegateSongs && cachedKeyMap.isNotEmpty()) {
                cachedKeyMap
            } else {
                try {
                    keys.resolveKeys().also {
                        cachedKeyMap = it
                        lastDelegateSongs = delegateSongs
                    }
                } catch (e: Exception) {
                    logWarning("Failed to resolve song keys, falling back to plain songs", e)
                    emptyMap()
                }
            }

            if (keyMap.isEmpty() || infoList.isEmpty()) {
                updateCoverSnapshot(emptyMap())
                delegateSongs
            } else {
                val infoByKey = infoList.associateBy { it.key }
                val newCovers = mutableMapOf<Long, String?>()
                val overridden = delegateSongs.map { song ->
                    val key = keyMap[song.id]
                    val entity = key?.let { infoByKey[it] }
                    val coverFile = if (entity?.status == SongInfoStatus.APPLIED && !entity.coverFile.isNullOrBlank()) {
                        newCovers[song.id] = entity.coverFile
                        covers.file(entity.coverFile)
                    } else {
                        newCovers[song.id] = null
                        null
                    }
                    applyOverride(song, entity, coverFile)
                }
                updateCoverSnapshot(newCovers)
                overridden
            }
        }.catch { e ->
            logWarning("Error in overlay combine, emitting plain delegate songs", e)
            emitAll(delegate.songs)
        }.collect {
            emit(it)
        }
    }

    override suspend fun refresh() {
        delegate.refresh()
    }

    private fun updateCoverSnapshot(newCoverNames: Map<Long, String?>) {
        val oldCoverNames = coverNamesSnapshot.getAndSet(newCoverNames)
        val fileMap = mutableMapOf<Long, File>()
        for ((id, fileName) in newCoverNames) {
            if (!fileName.isNullOrBlank()) {
                covers.file(fileName)?.let { fileMap[id] = it }
            }
        }
        coverFilesSnapshot.set(fileMap)

        val changed = changedCoverIds(oldCoverNames, newCoverNames)
        if (changed.isNotEmpty()) {
            onCoversChanged?.invoke(changed)
        }
    }

    private fun logWarning(msg: String, t: Throwable?) {
        try {
            android.util.Log.w(TAG, msg, t)
        } catch (_: RuntimeException) {
            // Unmocked android.util.Log during JVM tests
        }
    }

    companion object {
        private const val TAG = "OverlaySongRepository"
    }
}
