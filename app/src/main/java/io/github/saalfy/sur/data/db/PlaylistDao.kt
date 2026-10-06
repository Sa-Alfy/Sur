package io.github.saalfy.sur.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.saalfy.sur.data.playlist.reorderPositions
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    abstract fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    abstract fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT playlistId, songId FROM playlist_entries")
    abstract fun observeEntryRefs(): Flow<List<EntryRef>>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    abstract fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    abstract suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    abstract suspend fun deletePlaylist(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEntry(entry: PlaylistEntryEntity): Long

    @Query("DELETE FROM playlist_entries WHERE id = :entryId")
    abstract suspend fun deleteEntry(entryId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    protected abstract suspend fun maxPosition(playlistId: Long): Int

    @Query("SELECT id FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    protected abstract suspend fun entryIdsInOrder(playlistId: Long): List<Long>

    @Query("UPDATE playlist_entries SET position = :position WHERE id = :entryId")
    protected abstract suspend fun setPosition(entryId: Long, position: Int)

    @Transaction
    open suspend fun addSongs(playlistId: Long, songIds: List<Long>) {
        var next = maxPosition(playlistId) + 1
        for (songId in songIds) {
            // IGNORE on the (playlistId, songId) unique index skips duplicates and returns -1.
            val rowId = insertEntry(PlaylistEntryEntity(playlistId = playlistId, songId = songId, position = next))
            if (rowId != -1L) next++
        }
    }

    @Transaction
    open suspend fun moveEntry(playlistId: Long, fromEntryId: Long, toEntryId: Long) {
        val ids = entryIdsInOrder(playlistId)
        val from = ids.indexOf(fromEntryId)
        val to = ids.indexOf(toEntryId)
        if (from < 0 || to < 0 || from == to) return
        for ((entryId, position) in reorderPositions(ids, from, to)) {
            setPosition(entryId, position)
        }
    }
}
