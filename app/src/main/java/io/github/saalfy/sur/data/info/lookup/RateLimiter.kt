package io.github.saalfy.sur.data.info.lookup

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LookupBusyException(message: String = "Service unavailable: MusicBrainz rate limit busy (503)") : IOException(message)

fun interface Clock {
    fun currentTimeMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

class RateLimiter(
    private val clock: Clock = Clock.SYSTEM,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var lastRequestTime = 0L

    /**
     * Executes the callSupplier under the rate limiter.
     * Enforces at least 1100 ms between requests.
     * On HTTP 503: waits 3000 ms, retries once, then throws LookupBusyException if still 503.
     */
    suspend fun execute(callSupplier: () -> Call): Response = mutex.withLock {
        throttle()
        val firstCall = callSupplier()
        val firstResponse = firstCall.await()
        if (firstResponse.code == 503) {
            firstResponse.close()
            sleeper(3000L)
            lastRequestTime = clock.currentTimeMillis()
            val retryCall = callSupplier()
            val retryResponse = retryCall.await()
            if (retryResponse.code == 503) {
                retryResponse.close()
                throw LookupBusyException()
            }
            return retryResponse
        }
        return firstResponse
    }

    private suspend fun throttle() {
        val now = clock.currentTimeMillis()
        val elapsed = now - lastRequestTime
        if (lastRequestTime > 0L && elapsed < 1100L) {
            sleeper(1100L - elapsed)
        }
        lastRequestTime = clock.currentTimeMillis()
    }
}

/**
 * Awaits an OkHttp call with structured concurrency.
 * Cancelling the coroutine cancels the active HTTP call.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        cancel()
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
}
