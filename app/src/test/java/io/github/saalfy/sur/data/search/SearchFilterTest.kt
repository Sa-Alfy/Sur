package io.github.saalfy.sur.data.search

import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.playlist.Playlist
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFilterTest {

    private fun song(id: Long, title: String, artist: String? = null, album: String? = null) =
        Song(id = id, title = title, artist = artist, album = album, albumId = 0, durationMs = 180_000)

    private val songs = listOf(
        song(1, "Moner Manush", artist = "Anupam Roy", album = "Moner Manush OST"),
        song(2, "Barso Re", artist = "A. R. Rahman", album = "Guru"),
        song(3, "Hello (Remix)", artist = null, album = null),
        song(4, "C++ & You [Live]", artist = "Band.Name", album = "100% Live"),
    )

    private fun ids(result: List<Song>) = result.map { it.id }

    @Test
    fun caseInsensitive() {
        assertEquals(listOf(2L), ids(filterSongs(songs, "BARSO")))
        assertEquals(listOf(2L), ids(filterSongs(songs, "barso re")))
    }

    @Test
    fun partialMatch() {
        assertEquals(listOf(1L), ids(filterSongs(songs, "anus")))
    }

    @Test
    fun matchesArtist() {
        assertEquals(listOf(2L), ids(filterSongs(songs, "rahman")))
    }

    @Test
    fun matchesAlbum() {
        assertEquals(listOf(2L), ids(filterSongs(songs, "guru")))
    }

    @Test
    fun noMatch() {
        assertEquals(emptyList<Long>(), ids(filterSongs(songs, "zzz")))
    }

    @Test
    fun emptyAndBlankQueryReturnAll() {
        assertEquals(songs, filterSongs(songs, ""))
        assertEquals(songs, filterSongs(songs, "   "))
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(listOf(2L), ids(filterSongs(songs, "  guru ")))
    }

    @Test
    fun specialCharactersAreLiteral() {
        assertEquals(listOf(3L), ids(filterSongs(songs, "(remix)")))
        assertEquals(listOf(4L), ids(filterSongs(songs, "c++")))
        assertEquals(listOf(4L), ids(filterSongs(songs, "[live]")))
        assertEquals(listOf(4L), ids(filterSongs(songs, "100%")))
        assertEquals(listOf(2L), ids(filterSongs(songs, "a. r.")))
        // "." must not act as a regex wildcard.
        assertEquals(emptyList<Long>(), ids(filterSongs(songs, "b.rso")))
    }

    @Test
    fun nullFieldsDoNotMatchOrCrash() {
        assertEquals(emptyList<Long>(), ids(filterSongs(listOf(song(9, "X")), "null")))
    }

    @Test
    fun filtersPlaylistNames() {
        val playlists = listOf(
            PlaylistSummary(Playlist(1, "Morning", 0), 4),
            PlaylistSummary(Playlist(2, "Evening Chill", 0), 2),
        )
        assertEquals(listOf(2L), filterPlaylists(playlists, "CHILL").map { it.playlist.id })
        assertEquals(playlists, filterPlaylists(playlists, ""))
        assertEquals(emptyList<PlaylistSummary>(), filterPlaylists(playlists, "night"))
    }
}
