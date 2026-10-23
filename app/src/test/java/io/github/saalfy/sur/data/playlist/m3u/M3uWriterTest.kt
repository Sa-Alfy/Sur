package io.github.saalfy.sur.data.playlist.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uWriterTest {

    @Test
    fun roundTrip() {
        val entries = listOf(
            M3uEntry("Song A", "Artist X", 210, "Music/Sur/Artist X - Song A.m4a"),
            M3uEntry("Song B", null, 180, "Music/Sur/Song B.mp3"),
        )
        val result = M3uWriter.write("My Playlist", entries)
        val parsed = M3uParser.parse(result.text, "fallback")

        assertEquals("My Playlist", parsed.playlistName)
        assertEquals(2, parsed.entries.size)
        assertEquals("Song A", parsed.entries[0].title)
        assertEquals("Artist X", parsed.entries[0].artist)
        assertEquals(210, parsed.entries[0].durationSec)
        assertEquals("Music/Sur/Artist X - Song A.m4a", parsed.entries[0].path)
        assertEquals("Song B", parsed.entries[1].title)
        assertEquals(null, parsed.entries[1].artist)
        assertEquals("Music/Sur/Song B.mp3", parsed.entries[1].path)
    }

    @Test
    fun emptyPlaylist() {
        val result = M3uWriter.write("Empty", emptyList())
        assertTrue(result.text.contains("#EXTM3U"))
        assertTrue(result.text.contains("#PLAYLIST:Empty"))
        assertEquals(0, result.weakCount)
        val parsed = M3uParser.parse(result.text, "fallback")
        assertEquals("Empty", parsed.playlistName)
        assertTrue(parsed.entries.isEmpty())
    }

    @Test
    fun weakCountForEntryWithoutSlash() {
        val entries = listOf(
            M3uEntry("Track", "A", 60, "track.mp3"),        // weak – no slash
            M3uEntry("Full", "B", 60, "Music/full.mp3"),    // not weak
        )
        val result = M3uWriter.write("P", entries)
        assertEquals(1, result.weakCount)
    }

    @Test
    fun pathWithSpaces() {
        val entries = listOf(M3uEntry("Cool Song", "DJ Artist", 300, "Music/DJ Artist - Cool Song.flac"))
        val result = M3uWriter.write("P", entries)
        val parsed = M3uParser.parse(result.text, "fallback")
        assertEquals("Music/DJ Artist - Cool Song.flac", parsed.entries[0].path)
    }

    @Test
    fun unicodeAndBengaliNames() {
        val entries = listOf(M3uEntry("আমার সোনার বাংলা", "রবীন্দ্রনাথ", 240, "Music/Rabindra/আমার.m4a"))
        val result = M3uWriter.write("বাংলা", entries)
        val parsed = M3uParser.parse(result.text, "fallback")
        assertEquals("বাংলা", parsed.playlistName)
        assertEquals("আমার সোনার বাংলা", parsed.entries[0].title)
        assertEquals("রবীন্দ্রনাথ", parsed.entries[0].artist)
    }

    @Test
    fun commasAndDashesInTitles() {
        // Title has dashes; artist - title split should use the FIRST " - "
        val entries = listOf(M3uEntry("You - Me - Us", "The Band", 200, "Music/x.mp3"))
        val result = M3uWriter.write("P", entries)
        val parsed = M3uParser.parse(result.text, "fallback")
        assertEquals("The Band", parsed.entries[0].artist)
        assertEquals("You - Me - Us", parsed.entries[0].title)
    }
}
