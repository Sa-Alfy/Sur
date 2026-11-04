package io.github.saalfy.sur.data.playlist.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreSongFilesChunkingTest {

    @Test
    fun zeroIds_returnsEmptyList() {
        val ids = emptySet<Long>()
        val chunks = chunkSongIds(ids)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun oneId_returnsSingleChunkWithOneId() {
        val ids = setOf(42L)
        val chunks = chunkSongIds(ids)
        assertEquals(1, chunks.size)
        assertEquals(listOf(42L), chunks[0])
    }

    @Test
    fun exactlyFiveHundredIds_returnsSingleChunkOfFiveHundred() {
        val ids = (1L..500L).toSet()
        val chunks = chunkSongIds(ids)
        assertEquals(1, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(ids.toList(), chunks[0])
    }

    @Test
    fun fiveHundredAndOneIds_returnsTwoChunks() {
        val ids = (1L..501L).toSet()
        val chunks = chunkSongIds(ids)
        assertEquals(2, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(1, chunks[1].size)
        assertEquals(501L, chunks[1][0])
        assertEquals(ids.toList(), chunks.flatten())
    }

    @Test
    fun twelveHundredIds_returnsThreeChunks() {
        val ids = (1L..1200L).toSet()
        val chunks = chunkSongIds(ids)
        assertEquals(3, chunks.size)
        assertEquals(500, chunks[0].size)
        assertEquals(500, chunks[1].size)
        assertEquals(200, chunks[2].size)
        assertEquals(ids.toList(), chunks.flatten())
    }
}

