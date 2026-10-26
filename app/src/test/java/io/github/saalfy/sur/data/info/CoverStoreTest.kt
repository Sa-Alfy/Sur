package io.github.saalfy.sur.data.info

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverStoreTest {

    @Test
    fun sampleSize_3000x3000_target600() {
        assertEquals(4, sampleSize(3000, 3000, 600))
    }

    @Test
    fun sampleSize_600x600_target600() {
        assertEquals(1, sampleSize(600, 600, 600))
    }

    @Test
    fun sampleSize_500x500_target600() {
        assertEquals(1, sampleSize(500, 500, 600))
    }

    @Test
    fun sampleSize_1200x300_target600() {
        assertEquals(2, sampleSize(1200, 300, 600))
    }

    @Test
    fun sampleSize_1x1_target600() {
        assertEquals(1, sampleSize(1, 1, 600))
    }

    @Test
    fun scaledSize_3000x3000_max600() {
        assertEquals(Pair(600, 600), scaledSize(3000, 3000, 600))
    }

    @Test
    fun scaledSize_600x600_max600() {
        assertEquals(Pair(600, 600), scaledSize(600, 600, 600))
    }

    @Test
    fun scaledSize_500x500_max600_noUpscaling() {
        assertEquals(Pair(500, 500), scaledSize(500, 500, 600))
    }

    @Test
    fun scaledSize_1200x300_max600() {
        assertEquals(Pair(600, 150), scaledSize(1200, 300, 600))
    }

    @Test
    fun scaledSize_1x1_max600() {
        assertEquals(Pair(1, 1), scaledSize(1, 1, 600))
    }
}
