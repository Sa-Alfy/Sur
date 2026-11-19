package io.github.saalfy.sur.downloader

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class RangedDownloadTest {

    private val data = ByteArray(1000) { (it % 251).toByte() }
    private val chunk = 300L

    private fun partial(start: Long, end: Long, total: Long? = data.size.toLong()): RangeResponse {
        val last = minOf(end, data.size - 1L)
        val body = data.copyOfRange(start.toInt(), (last + 1).toInt())
        return RangeResponse(206, "bytes $start-$last/${total ?: "*"}", ByteArrayInputStream(body))
    }

    private fun full() = RangeResponse(200, null, ByteArrayInputStream(data))

    private fun run(knownLength: Long?, source: RangeSource): ByteArray = runBlocking {
        val f = File.createTempFile("ranged", ".bin")
        try {
            val written = downloadRanges(f, knownLength, source, { _, _ -> }, retryDelaysMs = listOf(0, 0), chunkBytes = chunk)
            assertEquals(written, f.length())
            f.readBytes()
        } finally {
            f.delete()
        }
    }

    @Test
    fun rangedChunks_assembleExactFile() {
        assertArrayEquals(data, run(data.size.toLong()) { s, e -> partial(s, e) })
    }

    @Test
    fun unknownLength_takenFromContentRange() {
        assertArrayEquals(data, run(null) { s, e -> partial(s, e) })
    }

    @Test
    fun unknownLength_endsOn416() {
        assertArrayEquals(data, run(null) { s, e -> if (s >= data.size) RangeResponse(416, null, ByteArrayInputStream(ByteArray(0))) else partial(s, e, total = null) })
    }

    /** Regression: a 200 after some chunks used to append the whole file after them. */
    @Test
    fun serverIgnoresRangeMidway_restartsInsteadOfCorrupting() {
        var calls = 0
        assertArrayEquals(data, run(null) { s, e -> if (++calls == 2) full() else partial(s, e) })
    }

    @Test
    fun wrongContentRangeStart_isRetriedNotAppended() {
        var calls = 0
        assertArrayEquals(data, run(data.size.toLong()) { s, e -> if (++calls == 2) partial(0, chunk - 1) else partial(s, e) })
    }

    @Test
    fun midChunkNetworkDrop_resumesFromPosition() {
        var dropped = false
        val starts = mutableListOf<Long>()
        val bytes = run(data.size.toLong()) { s, e ->
            starts += s
            if (s == chunk && !dropped) {
                dropped = true
                val good = data.copyOfRange(s.toInt(), s.toInt() + 100)
                RangeResponse(206, "bytes $s-${e}/${data.size}", FailingAfter(good))
            } else {
                partial(s, e)
            }
        }
        assertArrayEquals(data, bytes)
        assertTrue("resumed at 400, not restarted", starts.contains(chunk + 100))
    }

    @Test
    fun serverErrorsBeyondRetryBudget_fail() {
        try {
            run(data.size.toLong()) { _, _ -> RangeResponse(503, null, ByteArrayInputStream(ByteArray(0))) }
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun expiredStreamUrl_failsWithoutRetry() {
        var calls = 0
        try {
            run(data.size.toLong()) { _, _ -> calls++; RangeResponse(403, null, ByteArrayInputStream(ByteArray(0))) }
            fail("expected DownloadException")
        } catch (e: DownloadException) {
            assertTrue(e.error is DownloadError.ExtractionFailed)
        }
        assertEquals(1, calls)
    }

    @Test
    fun zeroBytesBeforeEnd_isAnError() {
        try {
            run(data.size.toLong()) { s, _ -> RangeResponse(206, "bytes $s-${s}/${data.size}", ByteArrayInputStream(ByteArray(0))) }
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun cancelDuringDownload_stopsBeforeTheEnd() = runBlocking {
        val f = File.createTempFile("ranged", ".bin").apply { deleteOnExit() }
        var last = 0L
        val job = launch {
            downloadRanges(f, data.size.toLong(), { s, e -> partial(s, e) }, { p, _ ->
                last = p
                if (p >= chunk) coroutineContext[Job]!!.cancel()
            }, chunkBytes = chunk)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue("stopped at $last", last < data.size)
    }

    /** Returns [good] and then throws, like a dropped connection. */
    private class FailingAfter(good: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(good)
        override fun read(): Int = inner.read().also { if (it < 0) throw IOException("connection reset") }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = inner.read(b, off, len)
            if (n < 0) throw IOException("connection reset")
            return n
        }
    }
}
