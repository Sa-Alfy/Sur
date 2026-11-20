package io.github.saalfy.sur.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Why a check or download could not reach a usable answer. */
sealed interface NetworkFailure {
    data object Offline : NetworkFailure
    data object Dns : NetworkFailure
    data object Timeout : NetworkFailure
    data object Tls : NetworkFailure
    data object Connection : NetworkFailure
    /** Redirected away from HTTPS, or a non-HTTPS final URL. */
    data object Insecure : NetworkFailure
    data class Http(val code: Int) : NetworkFailure
}

sealed interface CheckResult {
    /** [envelope] is the raw signed manifest, kept so the result survives restarts without trust loss. */
    data class Fetched(val manifest: UpdateManifest, val envelope: ByteArray) : CheckResult
    data class NetworkError(val failure: NetworkFailure) : CheckResult
    data class Rejected(val reason: ManifestRejectedException.Reason) : CheckResult
}

/** Downloads and verifies the update manifest. Never decides anything from an unverified manifest. */
class UpdateChecker(
    private val client: OkHttpClient,
    private val parser: UpdateManifestParser,
    private val manifestUrl: String = MANIFEST_URL,
    private val isOnline: () -> Boolean = { true },
) {
    suspend fun check(): CheckResult {
        if (!isOnline()) return CheckResult.NetworkError(NetworkFailure.Offline)
        val request = Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").get().build()
        return try {
            client.newCall(request).executeCancellable { response -> handle(response) }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            CheckResult.NetworkError(classify(e))
        }
    }

    private fun handle(response: Response): CheckResult {
        if (!response.request.url.isHttps) return CheckResult.NetworkError(NetworkFailure.Insecure)
        if (!response.isSuccessful) return CheckResult.NetworkError(NetworkFailure.Http(response.code))
        val limit = UpdateManifestParser.MAX_MANIFEST_BYTES.toLong()
        val body = response.body
        if (body.contentLength() > limit) return CheckResult.Rejected(ManifestRejectedException.Reason.TOO_LARGE)
        val source = body.source()
        // Buffer at most one byte past the limit so an endless body can't fill memory.
        source.request(limit + 1)
        if (source.buffer.size > limit) return CheckResult.Rejected(ManifestRejectedException.Reason.TOO_LARGE)
        val bytes = source.buffer.readByteArray()
        return try {
            CheckResult.Fetched(parser.parse(bytes), bytes)
        } catch (e: ManifestRejectedException) {
            CheckResult.Rejected(e.reason)
        }
    }

    companion object {
        /** GitHub's documented "latest release asset" link. Every stable release must carry this asset. */
        const val MANIFEST_URL = "https://github.com/Sa-Alfy/Sur/releases/latest/download/sur-update.json"

        /** Dedicated client: separate from the music downloader, no HTTPS->HTTP redirects. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}

internal fun classify(e: IOException): NetworkFailure = when (e) {
    is UnknownHostException -> NetworkFailure.Dns
    is SSLException -> NetworkFailure.Tls
    is InterruptedIOException -> NetworkFailure.Timeout // includes SocketTimeoutException
    else -> NetworkFailure.Connection
}

/**
 * Runs a blocking OkHttp call on the IO dispatcher and cancels the socket when the coroutine is
 * cancelled, so cancel takes effect mid-read instead of after the read timeout.
 */
internal suspend fun <T> Call.executeCancellable(block: (okhttp3.Response) -> T): T = withContext(Dispatchers.IO) {
    coroutineScope {
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                this@executeCancellable.cancel()
            }
        }
        try {
            execute().use(block)
        } finally {
            watcher.cancel()
        }
    }
}
