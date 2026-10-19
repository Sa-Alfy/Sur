package io.github.saalfy.sur.data.playlist

import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.SongRepository
import io.github.saalfy.sur.data.db.EntryRef
import io.github.saalfy.sur.data.db.PlaylistDao
import io.github.saalfy.sur.data.db.PlaylistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class RoomPlaylistRepository(
    private val dao: PlaylistDao,
    songRepository: SongRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaylistRepository {

    private val songsById: Flow<Map<Long, Song>> = songRepository.songs.map { songs -> songs.associateBy(Song::id) }

    override val playlists: Flow<List<PlaylistSummary>> =
        combine(dao.observePlaylists(), dao.observeEntryRefs(), songsById) { playlists, refs, songs ->
            summarize(playlists.map { it.toModel() }, refs, songs)
        }

    override fun playlist(id: Long): Flow<Playlist?> = dao.observePlaylist(id).map { it?.toModel() }

    override fun playlistSongs(playlistId: Long): Flow<List<PlaylistSong>> =
        combine(dao.observeEntries(playlistId), songsById) { entries, songs ->
            entries.mapNotNull { entry -> songs[entry.songId]?.let { PlaylistSong(entry.id, it) } }
        }

    override suspend fun create(name: String): Long =
        dao.insertPlaylist(PlaylistEntity(name = name.trim(), createdAt = clock()))

    override suspend fun rename(id: Long, name: String) = dao.renamePlaylist(id, name.trim())

    override suspend fun delete(id: Long) = dao.deletePlaylist(id)

    override suspend fun addSongs(playlistId: Long, songIds: List<Long>) = dao.addSongs(playlistId, songIds)

    override suspend fun removeEntry(entryId: Long) = dao.deleteEntry(entryId)

    override suspend fun moveEntry(playlistId: Long, fromEntryId: Long, toEntryId: Long) =
        dao.moveEntry(playlistId, fromEntryId, toEntryId)

    private fun PlaylistEntity.toModel() = Playlist(id = id, name = name, createdAt = createdAt)
}

/**
 * Counts and cover songs per playlist. [refs] must be ordered by (playlistId, position).
 * Songs no longer on the device are skipped (and stay in the database).
 */
internal fun summarize(playlists: List<Playlist>, refs: List<EntryRef>, songs: Map<Long, Song>): List<PlaylistSummary> {
    val present = refs.mapNotNull { ref -> songs[ref.songId]?.let { ref.playlistId to it } }
        .groupBy({ it.first }, { it.second })
    return playlists.map {
        val playlistSongs = present[it.id].orEmpty()
        PlaylistSummary(it, playlistSongs.size, playlistSongs.take(MAX_COVER_SONGS))
    }
}
