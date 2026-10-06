package io.github.saalfy.sur.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderTest {

    private val ids = listOf(10L, 20L, 30L, 40L, 50L)

    private fun order(result: List<Pair<Long, Int>>) = result.map { it.first }
    private fun positions(result: List<Pair<Long, Int>>) = result.map { it.second }

    @Test
    fun moveUp() {
        val result = reorderPositions(ids, from = 3, to = 1)
        assertEquals(listOf(10L, 40L, 20L, 30L, 50L), order(result))
        assertEquals(listOf(0, 1, 2, 3, 4), positions(result))
    }

    @Test
    fun moveDown() {
        val result = reorderPositions(ids, from = 1, to = 3)
        assertEquals(listOf(10L, 30L, 40L, 20L, 50L), order(result))
        assertEquals(listOf(0, 1, 2, 3, 4), positions(result))
    }

    @Test
    fun moveToStart() {
        val result = reorderPositions(ids, from = 4, to = 0)
        assertEquals(listOf(50L, 10L, 20L, 30L, 40L), order(result))
        assertEquals(listOf(0, 1, 2, 3, 4), positions(result))
    }

    @Test
    fun moveToEnd() {
        val result = reorderPositions(ids, from = 0, to = 4)
        assertEquals(listOf(20L, 30L, 40L, 50L, 10L), order(result))
        assertEquals(listOf(0, 1, 2, 3, 4), positions(result))
    }

    @Test
    fun sameIndexKeepsOrder() {
        val result = reorderPositions(ids, from = 2, to = 2)
        assertEquals(ids, order(result))
        assertEquals(listOf(0, 1, 2, 3, 4), positions(result))
    }

    @Test
    fun singleItem() {
        val result = reorderPositions(listOf(99L), from = 0, to = 0)
        assertEquals(listOf(99L to 0), result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfBoundsThrows() {
        reorderPositions(ids, from = 0, to = 5)
    }

    @Test
    fun moveItemDoesNotMutateInput() {
        val input = listOf("a", "b", "c")
        moveItem(input, 0, 2)
        assertEquals(listOf("a", "b", "c"), input)
    }
}
