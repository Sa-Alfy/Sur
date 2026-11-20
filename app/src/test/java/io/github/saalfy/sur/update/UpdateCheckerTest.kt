package io.github.saalfy.sur.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class UpdateCheckerTest {
    private val signer = TestSigner()

    private fun checker(online: Boolean = true, handler: (okhttp3.Request) -> okhttp3.Response) =
        UpdateChecker(fakeClient(handler), signer.parser(), isOnline = { online })

    @Test
    fun validManifest_isFetchedAndVerified() = runBlocking {
        val envelope = signer.envelope(manifestFields())
        val result = checker { respond(it, 200, bytesBody(envelope)) }.check()
        assertTrue(result is CheckResult.Fetched)
        result as CheckResult.Fetched
        assertEquals(10L, result.manifest.versionCode)
        assertTrue(result.envelope.contentEquals(envelope))
    }

    @Test
    fun requestsTheDocumentedHttpsUrl() = runBlocking {
        var url: String? = null
        checker { url = it.url.toString(); respond(it, 404) }.check()
        assertEquals(UpdateChecker.MANIFEST_URL, url)
        assertTrue(UpdateChecker.MANIFEST_URL.startsWith("https://"))
    }

    @Test
    fun offline_skipsTheNetwork() = runBlocking {
        var called = false
        val result = checker(online = false) { called = true; respond(it, 200) }.check()
        assertEquals(CheckResult.NetworkError(NetworkFailure.Offline), result)
        assertTrue(!called)
    }

    @Test
    fun httpErrors_areReported() = runBlocking {
        assertEquals(CheckResult.NetworkError(NetworkFailure.Http(404)), checker { respond(it, 404) }.check())
        assertEquals(CheckResult.NetworkError(NetworkFailure.Http(503)), checker { respond(it, 503) }.check())
    }

    @Test
    fun networkExceptions_areClassified() = runBlocking {
        assertEquals(CheckResult.NetworkError(NetworkFailure.Dns), checker { throw UnknownHostException("github.com") }.check())
        assertEquals(CheckResult.NetworkError(NetworkFailure.Timeout), checker { throw SocketTimeoutException() }.check())
        assertEquals(CheckResult.NetworkError(NetworkFailure.Tls), checker { throw SSLHandshakeException("bad cert") }.check())
        assertEquals(CheckResult.NetworkError(NetworkFailure.Connection), checker { throw java.io.IOException("reset") }.check())
    }

    @Test
    fun badSignature_isRejectedNotTrusted() = runBlocking {
        val result = checker { respond(it, 200, bytesBody(TestSigner().envelope(manifestFields()))) }.check()
        assertEquals(CheckResult.Rejected(ManifestRejectedException.Reason.BAD_SIGNATURE), result)
    }

    @Test
    fun garbageBody_isRejected() = runBlocking {
        val result = checker { respond(it, 200, bytesBody("<html>rate limited</html>".toByteArray())) }.check()
        assertEquals(CheckResult.Rejected(ManifestRejectedException.Reason.MALFORMED), result)
    }

    @Test
    fun oversizedBody_isRejectedWithoutReadingItAll() = runBlocking {
        val big = ByteArray(UpdateManifestParser.MAX_MANIFEST_BYTES * 4) { 'a'.code.toByte() }
        val result = checker { respond(it, 200, bytesBody(big)) }.check()
        assertEquals(CheckResult.Rejected(ManifestRejectedException.Reason.TOO_LARGE), result)
    }

    @Test
    fun nonHttpsFinalUrl_isRefused() = runBlocking {
        val client = fakeClient { req ->
            val plain = req.newBuilder().url("http://github.com/x").build()
            respond(plain, 200, bytesBody(signer.envelope(manifestFields())))
        }
        val result = UpdateChecker(client, signer.parser()).check()
        assertEquals(CheckResult.NetworkError(NetworkFailure.Insecure), result)
    }

    @Test
    fun productionClient_neverFollowsHttpsToHttpRedirects() {
        val client = UpdateChecker.defaultClient()
        assertTrue(!client.followSslRedirects)
        assertTrue(client.connectTimeoutMillis > 0 && client.readTimeoutMillis > 0)
    }
}
