package io.github.saalfy.sur.data.info

import org.junit.Assert.assertEquals
import org.junit.Test

class SongInfoKeyTest {

    @Test
    fun buildKey_whenRelativePathHasFilename_returnsExactRelativePath() {
        val key = SongInfoKey.buildKey("Music/Sur/Artist - Title.m4a", "Artist - Title.m4a")
        assertEquals("Music/Sur/Artist - Title.m4a", key)
    }

    @Test
    fun buildKey_whenRelativePathIsDirectory_appendsDisplayName() {
        val key = SongInfoKey.buildKey("Music/Sur", "Song.mp3")
        assertEquals("Music/Sur/Song.mp3", key)
    }

    @Test
    fun buildKey_whenRelativePathHasTrailingSlash_appendsDisplayNameCleanly() {
        val key = SongInfoKey.buildKey("Music/Sur/", "Song.mp3")
        assertEquals("Music/Sur/Song.mp3", key)
    }

    @Test
    fun buildKey_normalizesBackslashes() {
        val key = SongInfoKey.buildKey("Music\\Sur\\Folder\\Song.mp3", "Song.mp3")
        assertEquals("Music/Sur/Folder/Song.mp3", key)
    }

    @Test
    fun buildKey_whenRelativePathNull_fallsBackToDisplayName() {
        val key = SongInfoKey.buildKey(null, "Song.mp3")
        assertEquals("Song.mp3", key)
    }

    @Test
    fun buildKey_whenRelativePathEmpty_fallsBackToDisplayName() {
        val key = SongInfoKey.buildKey("  ", "Song.mp3")
        assertEquals("Song.mp3", key)
    }

    @Test
    fun buildKey_whenBothNull_returnsEmpty() {
        val key = SongInfoKey.buildKey(null, null)
        assertEquals("", key)
    }
}
