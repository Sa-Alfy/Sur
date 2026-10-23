package io.github.saalfy.sur.data.playlist.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uMatcherTest {

    private fun song(
        id: Long,
        title: String,
        artist: String? = null,
        durationSec: Int = 180,
        relativePath: String? = null,
        displayName: String? = null,
    ) = MatchableSong(id, title, artist, durationSec, relativePath, displayName)

    @Test
    fun exactPathMatch() {
        val library = listOf(song(1, "T", relativePath = "Music/T.mp3", displayName = "T.mp3"))
        val entries = listOf(M3uEntry("T", null, 180, "Music/T.mp3"))
        val result = M3uMatcher.match(entries, library)
        assertEquals(listOf(1L), result.matched)
        assertEquals(0, result.notMatchedCount)
    }

    @Test
    fun fileNameOnlyMatch() {
        val library = listOf(song(2, "B", relativePath = "Music/Sub/B.mp3", displayName = "B.mp3"))
        val entries = listOf(M3uEntry("B", null, 180, "SomeOtherFolder/B.mp3"))
        val result = M3uMatcher.match(entries, library)
        assertEquals(listOf(2L), result.matched)
    }

    @Test
    fun normalizedTitleArtistDurationMatch() {
        val library = listOf(song(3, "Cool Song", "The Artist", 200))
        val entries = listOf(M3uEntry("cool song!", "The   Artist", 201, "x/x.mp3"))
        val result = M3uMatcher.match(entries, library)
        assertEquals(listOf(3L), result.matched)
    }

    @Test
    fun ambiguousPathNotMatched() {
        // Two songs with the same relative path – should NOT match either
        val library = listOf(
            song(1, "A", relativePath = "Music/dup.mp3", displayName = "dup.mp3"),
            song(2, "B", relativePath = "Music/dup.mp3", displayName = "dup.mp3"),
        )
        val entries = listOf(M3uEntry("A", null, 180, "Music/dup.mp3"))
        val result = M3uMatcher.match(entries, library)
        assertNull(result.matched[0])
        assertEquals(1, result.notMatchedCount)
    }

    @Test
    fun ambiguousFileNameNotMatched() {
        val library = listOf(
            song(1, "A", displayName = "same.mp3"),
            song(2, "B", displayName = "same.mp3"),
        )
        val entries = listOf(M3uEntry("A", null, 180, "folder/same.mp3"))
        val result = M3uMatcher.match(entries, library)
        assertNull(result.matched[0])
        assertEquals(1, result.notMatchedCount)
    }

    @Test
    fun ambiguousTitleArtistNotMatched() {
        val library = listOf(
            song(1, "Song", "Artist", 200),
            song(2, "Song", "Artist", 201), // within 2 s tolerance → both match step 3
        )
        val entries = listOf(M3uEntry("Song", "Artist", 200, "x/x.mp3"))
        val result = M3uMatcher.match(entries, library)
        assertNull(result.matched[0])
    }

    @Test
    fun durationOutsideToleranceNotMatched() {
        val library = listOf(song(1, "Song", "Artist", 200))
        val entries = listOf(M3uEntry("Song", "Artist", 203, "x/x.mp3"))
        val result = M3uMatcher.match(entries, library)
        assertNull(result.matched[0])
    }

    @Test
    fun nothingMatchedIsAllNull() {
        val library = listOf(song(1, "A", "B", 60))
        val entries = listOf(M3uEntry("X", "Y", 60, "z/z.mp3"))
        val result = M3uMatcher.match(entries, library)
        assertEquals(listOf(null), result.matched)
        assertEquals(1, result.notMatchedCount)
    }

    @Test
    fun emptyLibraryNoMatch() {
        val entries = listOf(M3uEntry("X", null, 60, "x/x.mp3"))
        val result = M3uMatcher.match(entries, emptyList())
        assertEquals(listOf(null), result.matched)
    }

    @Test
    fun emptyEntriesEmptyResult() {
        val library = listOf(song(1, "A", durationSec = 60))
        val result = M3uMatcher.match(emptyList(), library)
        assertEquals(emptyList<Long?>(), result.matched)
        assertEquals(0, result.notMatchedCount)
    }
}
