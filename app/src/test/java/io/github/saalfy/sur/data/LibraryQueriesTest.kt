package io.github.saalfy.sur.data

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** Library stream survives MediaStore query failures (production [libraryQueries]). */
class LibraryQueriesTest {

    private fun song(id: Long) = Song(id = id, title = "t$id", artist = null, album = null, albumId = 0, durationMs = 180_000)

    /** One trigger per result; yields so each query runs before the next trigger (as with real debounced triggers). */
    private fun run(results: List<() -> List<Song>>): Pair<List<List<Song>>, Int> = runBlocking {
        var call = 0
        var errors = 0
        val triggers = flow { repeat(results.size) { emit(Unit); yield() } }
        val emitted = libraryQueries(triggers, query = { results[call++]() }, onError = { errors++ }).toList()
        emitted to errors
    }

    @Test
    fun failedQuery_keepsLastList_andLaterRefreshStillWorks() {
        val (emitted, errors) = run(
            listOf({ listOf(song(1)) }, { throw IllegalStateException("SQLiteException: scan in progress") }, { listOf(song(1), song(2)) })
        )
        assertEquals(listOf(listOf(song(1)), listOf(song(1), song(2))), emitted)
        assertEquals(1, errors)
    }

    @Test
    fun lowMemoryCursorError_doesNotEndStream() {
        val (emitted, _) = run(
            listOf({ throw IllegalStateException("CursorWindowAllocationException") }, { listOf(song(3)) })
        )
        assertEquals(listOf(listOf(song(3))), emitted)
    }

    @Test
    fun securityException_emitsEmpty_thenRecovers() {
        val (emitted, _) = run(
            listOf({ throw SecurityException("no access") }, { listOf(song(4)) })
        )
        assertEquals(listOf(emptyList<Song>(), listOf(song(4))), emitted)
    }
}
