package io.github.saalfy.sur.data.playlist

import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.db.EntryRef
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSummaryTest {

    private fun song(id: Long) = Song(id = id, title = "S$id", artist = null, album = null, albumId = 0, durationMs = 60_000)

    private val songs = (1L..10L).associateWith(::song)
    private val a = Playlist(1, "A", 0)
    private val b = Playlist(2, "B", 0)

    @Test
    fun coversAreFirstPresentSongsInOrderCappedAtFour() {
        val refs = listOf(5L, 3L, 99L, 7L, 1L, 2L).map { EntryRef(1, it) } // 99 is gone from the device
        val summary = summarize(listOf(a), refs, songs).single()
        assertEquals(5, summary.songCount)
        assertEquals(listOf(5L, 3L, 7L, 1L), summary.coverSongs.map { it.id })
    }

    @Test
    fun emptyAndAllMissingPlaylistsHaveNoCovers() {
        val refs = listOf(EntryRef(2, 99L))
        val result = summarize(listOf(a, b), refs, songs)
        assertEquals(listOf(0, 0), result.map { it.songCount })
        assertEquals(listOf(emptyList<Song>(), emptyList()), result.map { it.coverSongs })
    }

    @Test
    fun keepsPlaylistOrderAndSeparatesPlaylists() {
        val refs = listOf(EntryRef(1, 1L), EntryRef(2, 2L), EntryRef(2, 3L))
        val result = summarize(listOf(b, a), refs, songs)
        assertEquals(listOf(2L, 1L), result.map { it.playlist.id })
        assertEquals(listOf(listOf(2L, 3L), listOf(1L)), result.map { s -> s.coverSongs.map { it.id } })
    }
}
