package io.github.saalfy.sur.data

import kotlinx.coroutines.flow.Flow

interface SongRepository {
    /** All songs in the library. Re-emits when the underlying library changes. */
    val songs: Flow<List<Song>>

    /** Forces a rescan for active collectors of [songs]. */
    suspend fun refresh()
}
