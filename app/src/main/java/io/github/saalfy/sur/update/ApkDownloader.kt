package io.github.saalfy.sur.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ApkDownloadResult {
    data class Verified(val file: File) : ApkDownloadResult
    /** Another update download is already running; nothing was touched. */
    data object Busy : ApkDownloadResult
    /** Network trouble; the partial file is kept so a retry resumes. */
    data class Interrupted(val failure: NetworkFailure, val bytesSoFar: Long) : ApkDownloadResult
    /** Download finished but the bytes are wrong. Partial and final files were deleted. */
    data class Invalid(val problem: Problem) : ApkDownloadResult
    data class StorageError(val notEnoughSpace: Boolean) : ApkDownloadResult

    enum class Problem { SIZE_MISMATCH, CHECKSUM_MISMATCH }
}

/**
 * Downloads update APKs into a private directory (`files/updates`), streaming to disk and hashing
 * as it goes. File names come only from the verified versionCode, so no path from the network is
 * ever used. Separate from the music downloader on purpose.
 */
class ApkDownloader(
    private val client: OkHttpClient,
    private val dir: File,
) {
    private val running = AtomicBoolean(false)

    fun finalFile(m: UpdateManifest) = File(dir, "sur-${m.versionCode}.apk")
    fun partFile(m: UpdateManifest) = File(dir, "sur-${m.versionCode}.apk.part")

    /** Returns the completed APK if it exists and still matches the manifest (size and SHA-256). */
    fun verifiedApk(m: UpdateManifest): File? {
        val f = finalFile(m)
        if (!f.isFile || f.length() != m.apkSize) return null
        return if (sha256Hex(f) == m.apkSha256) f else null
    }

    fun deletePartial(m: UpdateManifest) {
        partFile(m).delete()
    }

    /** Deletes every update file except those for [keep]. Only touches names this class creates. */
    fun deleteAllExcept(keep: UpdateManifest?) {
        val keepNames = keep?.let { setOf(finalFile(it).name, partFile(it).name) }.orEmpty()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && OWN_NAME.matches(f.name) && f.name !in keepNames) f.delete()
        }
    }

    suspend fun download(
        m: UpdateManifest,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): ApkDownloadResult {
        if (!running.compareAndSet(false, true)) return ApkDownloadResult.Busy
        try {
            return withContext(Dispatchers.IO) { downloadLocked(m, onProgress) }
        } finally {
            running.set(false)
        }
    }

    private suspend fun downloadLocked(m: UpdateManifest, onProgress: (Long, Long) -> Unit): ApkDownloadResult {
        if (!dir.isDirectory && !dir.mkdirs()) return ApkDownloadResult.StorageError(notEnoughSpace = false)
        verifiedApk(m)?.let { return ApkDownloadResult.Verified(it) }
        finalFile(m).delete()

        val part = partFile(m)
        var offset = if (part.isFile) part.length() else 0L
        if (offset > m.apkSize) {
            part.delete()
            offset = 0
        }
        if (offset == m.apkSize) return finish(m, part)
        if (dir.usableSpace < (m.apkSize - offset) + SPACE_MARGIN) return ApkDownloadResult.StorageError(notEnoughSpace = true)

        // One retry from zero if the server ignores or rejects our resume range.
        var attempt = 0
        while (true) {
            attempt++
            val outcome = try {
                fetch(m, part, offset, onProgress)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                return ApkDownloadResult.Interrupted(classify(e), part.length())
            }
            when (outcome) {
                is FetchOutcome.Done -> return finish(m, part)
                is FetchOutcome.Result -> return outcome.result
                FetchOutcome.RestartFromZero -> {
                    part.delete()
                    if (offset == 0L || attempt > 1) return ApkDownloadResult.Interrupted(NetworkFailure.Http(416), 0)
                    offset = 0
                }
            }
        }
    }

    private sealed interface FetchOutcome {
        data object Done : FetchOutcome
        data object RestartFromZero : FetchOutcome
        data class Result(val result: ApkDownloadResult) : FetchOutcome
    }

    private suspend fun fetch(
        m: UpdateManifest,
        part: File,
        offset: Long,
        onProgress: (Long, Long) -> Unit,
    ): FetchOutcome {
        val request = Request.Builder().url(m.apkUrl)
            .header("Accept-Encoding", "identity")
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .get().build()
        val call = client.newCall(request)
        return call.executeCancellable { response -> write(response, m, part, offset, onProgress, call::isCanceled) }
    }

    private fun write(
        response: Response,
        m: UpdateManifest,
        part: File,
        requestedOffset: Long,
        onProgress: (Long, Long) -> Unit,
        isCanceled: () -> Boolean,
    ): FetchOutcome {
        if (!response.request.url.isHttps) return FetchOutcome.Result(ApkDownloadResult.Interrupted(NetworkFailure.Insecure, part.length()))
        val start: Long = when {
            response.code == 206 && requestedOffset > 0 -> {
                if (response.header("Content-Range") != "bytes $requestedOffset-${m.apkSize - 1}/${m.apkSize}") {
                    return FetchOutcome.RestartFromZero
                }
                requestedOffset
            }
            response.code == 200 -> 0L // full body, even if we asked for a range
            response.code == 416 && requestedOffset > 0 -> return FetchOutcome.RestartFromZero
            else -> return FetchOutcome.Result(
                ApkDownloadResult.Interrupted(NetworkFailure.Http(response.code), part.length()),
            )
        }
        val body = response.body
        val declared = body.contentLength()
        if (declared >= 0 && declared != m.apkSize - start) return invalid(m, ApkDownloadResult.Problem.SIZE_MISMATCH)

        var written = start
        var lastReported = -1L
        FileOutputStream(part, start > 0).use { out ->
            body.byteStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    if (isCanceled()) throw IOException("Canceled")
                    val n = input.read(buf)
                    if (n < 0) break
                    if (written + n > m.apkSize) {
                        out.close()
                        return invalid(m, ApkDownloadResult.Problem.SIZE_MISMATCH)
                    }
                    out.write(buf, 0, n)
                    written += n
                    if (written - lastReported >= PROGRESS_STEP || written == m.apkSize) {
                        lastReported = written
                        onProgress(written, m.apkSize)
                    }
                }
            }
            out.fd.sync()
        }
        if (written < m.apkSize) {
            // Connection ended early without an error: keep the part file for resume.
            return FetchOutcome.Result(ApkDownloadResult.Interrupted(NetworkFailure.Connection, written))
        }
        return FetchOutcome.Done
    }

    /** Size and SHA-256 must both match the signed manifest before the file gets its final name. */
    private fun finish(m: UpdateManifest, part: File): ApkDownloadResult {
        if (part.length() != m.apkSize) return invalidResult(m, ApkDownloadResult.Problem.SIZE_MISMATCH)
        if (sha256Hex(part) != m.apkSha256) return invalidResult(m, ApkDownloadResult.Problem.CHECKSUM_MISMATCH)
        val target = finalFile(m)
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return ApkDownloadResult.StorageError(notEnoughSpace = false)
        }
        return ApkDownloadResult.Verified(target)
    }

    private fun invalid(m: UpdateManifest, problem: ApkDownloadResult.Problem) =
        FetchOutcome.Result(invalidResult(m, problem))

    private fun invalidResult(m: UpdateManifest, problem: ApkDownloadResult.Problem): ApkDownloadResult {
        partFile(m).delete()
        finalFile(m).delete()
        return ApkDownloadResult.Invalid(problem)
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val PROGRESS_STEP = 128L * 1024
        private const val SPACE_MARGIN = 8L * 1024 * 1024
        private val OWN_NAME = Regex("^sur-\\d{1,10}\\.apk(\\.part)?$")

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
