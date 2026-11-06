package io.github.saalfy.sur.data.playlist

import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    /** All playlists, newest first, with counts of songs still present on the device. */
    val playlists: Flow<List<PlaylistSummary>>

    /** Emits null once the playlist no longer exists. */
    fun playlist(id: Long): Flow<Playlist?>

    /** Songs in playlist order. Entries whose song is gone from the device are skipped (but kept in the DB). */
    fun playlistSongs(playlistId: Long): Flow<List<PlaylistSong>>

    suspend fun create(name: String): Long

    suspend fun rename(id: Long, name: String)

    suspend fun delete(id: Long)

    /** Appends songs in order. Songs already in the playlist are ignored. */
    suspend fun addSongs(playlistId: Long, songIds: List<Long>)

    /**
     * Backfills stored song keys and re-links entries whose song moved to a
     * new MediaStore id. Safe to call repeatedly; it never deletes an entry
     * except a duplicate whose re-link target is already in the same playlist.
     */
    suspend fun reconcile()

    suspend fun deleteEntriesForSong(songId: Long)

    suspend fun removeEntry(entryId: Long)

    /** Moves [fromEntryId] to the position currently held by [toEntryId]. */
    suspend fun moveEntry(playlistId: Long, fromEntryId: Long, toEntryId: Long)
}
