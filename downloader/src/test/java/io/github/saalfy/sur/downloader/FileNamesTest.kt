package io.github.saalfy.sur.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun cleansTopicSuffix() {
        assertEquals("Arijit Singh", cleanChannelName("Arijit Singh - Topic"))
        assertEquals("Band", cleanChannelName("  Band  "))
        assertEquals("Topic Lovers", cleanChannelName("Topic Lovers"))
        assertNull(cleanChannelName(" - Topic"))
        assertNull(cleanChannelName(null))
    }

    @Test
    fun removesReservedCharacters() {
        assertEquals("AC DC - Back in Black", sanitizeFileName("AC/DC - Back in Black"))
        assertEquals("a b c d e f g h i", sanitizeFileName("a\\b:c*d?e\"f<g>h|i"))
        assertEquals("tab and newline", sanitizeFileName("tab\tand\nnewline"))
    }

    @Test
    fun trimsDotsAndFallsBack() {
        assertEquals("name", sanitizeFileName("...name..."))
        assertEquals("audio", sanitizeFileName("///"))
    }

    @Test
    fun keepsUnicodeAndCapsLength() {
        assertEquals("বইশ by Smooches", sanitizeFileName("বইশ by Smooches"))
        assertTrue(sanitizeFileName("x".repeat(500)).length <= 100)
    }

    @Test
    fun buildsArtistTitleFileName() {
        assertEquals("Artist - Song.m4a", audioFileName("Song", "Artist"))
        assertEquals("Song.m4a", audioFileName("Song", null))
        assertEquals("A B - Song.m4a", audioFileName("Song", "A/B"))
    }
}
