package io.github.saalfy.sur.data.playlist.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    @Test
    fun bomAndCrlf() {
        val text = "\uFEFF#EXTM3U\r\n#PLAYLIST:Test\r\n#EXTINF:120,Artist - Title\r\nMusic/file.mp3\r\n"
        val result = M3uParser.parse(text, "fallback")
        assertEquals("Test", result.playlistName)
        assertEquals(1, result.entries.size)
        assertEquals("Artist", result.entries[0].artist)
        assertEquals("Title", result.entries[0].title)
        assertEquals(120, result.entries[0].durationSec)
        assertEquals("Music/file.mp3", result.entries[0].path)
    }

    @Test
    fun missingExtm3uHeader() {
        val text = "#PLAYLIST:NoHeader\n#EXTINF:60,A - B\nMusic/b.mp3\n"
        val result = M3uParser.parse(text, "fallback")
        assertEquals("NoHeader", result.playlistName)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun missingPlaylistFallsBackToFileName() {
        val text = "#EXTM3U\n#EXTINF:60,A - B\nMusic/b.mp3\n"
        val result = M3uParser.parse(text, "my-file")
        assertEquals("my-file", result.playlistName)
    }

    @Test
    fun blankLinesAndUnknownComments() {
        val text = """
            #EXTM3U
            #PLAYLIST:P
            
            # Some comment nobody knows
            #EXTINF:100,A - B
            Music/b.mp3
            
        """.trimIndent()
        val result = M3uParser.parse(text, "fallback")
        assertEquals("P", result.playlistName)
        assertEquals(1, result.entries.size)
        assertEquals(0, result.badLineCount)
    }

    @Test
    fun garbageLines() {
        // Lines that start with #EXTINF: but lack a comma are counted as bad
        val text = "#EXTM3U\n#EXTINF:NOTVALID\nMusic/x.mp3\n"
        val result = M3uParser.parse(text, "fallback")
        // The path line still becomes an entry (with null extinf metadata)
        assertEquals(1, result.entries.size)
        assertEquals(1, result.badLineCount)
        assertEquals(null, result.entries[0].durationSec)
    }

    @Test
    fun emptyInput() {
        val result = M3uParser.parse("", "empty-file")
        assertEquals("empty-file", result.playlistName)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun pathWithSpaces() {
        val text = "#EXTM3U\n#EXTINF:60,A - B\nMusic/My Artist/Cool Song.mp3\n"
        val result = M3uParser.parse(text, "f")
        assertEquals("Music/My Artist/Cool Song.mp3", result.entries[0].path)
    }

    @Test
    fun backslashConvertedToForwardSlash() {
        val text = "#EXTM3U\n#EXTINF:60,A - B\nMusic\\Sur\\song.mp3\n"
        val result = M3uParser.parse(text, "f")
        assertEquals("Music/Sur/song.mp3", result.entries[0].path)
    }

    @Test
    fun titleOnlyExtinf() {
        // No artist dash separator in the display string
        val text = "#EXTM3U\n#EXTINF:60,Just a Title\nMusic/x.mp3\n"
        val result = M3uParser.parse(text, "f")
        assertEquals(null, result.entries[0].artist)
        assertEquals("Just a Title", result.entries[0].title)
    }

    @Test
    fun bengaliNamesRoundTrip() {
        val text = "#EXTM3U\n#PLAYLIST:বাংলা\n#EXTINF:240,রবীন্দ্রনাথ - আমার সোনার বাংলা\nMusic/আমার.m4a\n"
        val result = M3uParser.parse(text, "fallback")
        assertEquals("বাংলা", result.playlistName)
        assertEquals("রবীন্দ্রনাথ", result.entries[0].artist)
        assertEquals("আমার সোনার বাংলা", result.entries[0].title)
        assertEquals("Music/আমার.m4a", result.entries[0].path)
    }
}
