package io.github.saalfy.sur.downloader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** One HTTP response to a byte-range request. */
class RangeResponse(val code: Int, val contentRange: String?, val body: InputStream) : Closeable {
    override fun close() = body.close()
}

/** Opens `Range: bytes=start-endInclusive` for the stream. */
fun interface RangeSource {
    fun open(start: Long, endInclusive: Long): RangeResponse
}

const val DOWNLOAD_CHUNK_BYTES = 10L * 1024 * 1024
private const val COPY_BUFFER_BYTES = 64 * 1024

/** Waits before each retry of a failed chunk; its size is the retry budget per chunk. */
val DEFAULT_RETRY_DELAYS_MS = listOf(1_000L, 3_000L)

/**
 * Downloads the stream into [target] in [chunkBytes] ranges and returns the bytes written.
 *
 * - 206: the Content-Range start must equal the requested position, else the chunk is retried
 *   (appending bytes from the wrong offset would corrupt the file).
 * - 200: the server ignored Range and sends the whole file. If bytes were already written they
 *   are discarded and the file restarts from 0 (before, the whole file was appended after them).
 * - 416 with an unknown length: the previous chunk was the last one.
 * - 403/410: the stream URL expired or was refused → [DownloadError.ExtractionFailed] (no retry).
 * - Other failures (I/O errors, timeouts, 5xx) retry from the current position after each delay
 *   in [retryDelaysMs]; written bytes are kept, so a retry resumes instead of starting over.
 * Cancellation is checked between reads and during retry delays.
 */
suspend fun downloadRanges(
    target: File,
    knownLength: Long?,
    source: RangeSource,
    onProgress: (downloaded: Long, total: Long?) -> Unit,
    retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    chunkBytes: Long = DOWNLOAD_CHUNK_BYTES,
): Long {
    RandomAccessFile(target, "rw").use { file ->
        file.setLength(0)
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var position = 0L
        var total = knownLength
        var failures = 0
        while (total == null || position < total) {
            currentCoroutineContext().ensureActive()
            try {
                source.open(position, position + chunkBytes - 1).use { response ->
                    when (response.code) {
                        206 -> {
                            val start = contentRangeStart(response.contentRange)
                            if (start != null && start != position) {
                                throw IOException("Range mismatch: asked $position, got $start")
                            }
                            if (total == null) total = contentRangeTotal(response.contentRange)
                        }
                        200 -> if (position > 0) {
                            file.setLength(0)
                            file.seek(0)
                            position = 0
                        }
                        416 -> if (total == null) {
                            total = position
                        } else {
                            throw IOException("HTTP 416 at $position of $total")
                        }
                        403, 410 -> throw DownloadException(
                            DownloadError.ExtractionFailed("Stream refused (HTTP ${response.code})"),
                        )
                        else -> throw IOException("HTTP ${response.code}")
                    }
                    if (response.code == 416) return@use
                    var readThisChunk = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = response.body.read(buffer)
                        if (n < 0) break
                        file.write(buffer, 0, n)
                        position += n
                        readThisChunk += n
                        onProgress(position, total)
                    }
                    if (response.code == 200) {
                        total = position
                    } else if (readThisChunk == 0L) {
                        if (total != null && position < total) {
                            throw IOException("Stream returned 0 bytes at $position of $total")
                        }
                        total = position
                    }
                }
                failures = 0
            } catch (e: IOException) {
                if (failures >= retryDelaysMs.size) throw e
                delay(retryDelaysMs[failures++])
            }
        }
        return position
    }
}

internal fun contentRangeTotal(header: String?): Long? =
    header?.substringAfterLast('/')?.trim()?.toLongOrNull()

/** "bytes 100-199/1000" → 100. */
internal fun contentRangeStart(header: String?): Long? =
    header?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.trim()?.toLongOrNull()
