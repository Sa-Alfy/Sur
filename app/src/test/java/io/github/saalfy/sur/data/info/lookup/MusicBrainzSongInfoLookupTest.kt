package io.github.saalfy.sur.data.info.lookup

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MusicBrainzSongInfoLookupTest {

    private class FakeClock(var current: Long = 1000L) : Clock {
        override fun currentTimeMillis(): Long = current
    }

    @Test
    fun queryBuilder_quotesAndBackslashes_areEscaped() {
        val query = MusicBrainzSongInfoLookup.buildLuceneQuery("Say \"Hello\"", "Artist\\Band")
        assertEquals("recording:\"Say \\\"Hello\\\"\" AND artist:\"Artist\\\\Band\"", query)
    }

    @Test
    fun queryBuilder_colonsAndParentheses_areEscaped() {
        val query = MusicBrainzSongInfoLookup.buildLuceneQuery("Title: Subtitle (Live)", null)
        assertEquals("recording:\"Title\\: Subtitle \\(Live\\)\"", query)
    }

    @Test
    fun queryBuilder_bengaliText_isPreservedInQueryAndUrlEncoded() {
        val query = MusicBrainzSongInfoLookup.buildLuceneQuery("বইশ", "শিল্পীর নাম")
        assertEquals("recording:\"বইশ\" AND artist:\"শিল্পীর নাম\"", query)

        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        assertTrue(encoded.contains("%E0%A6%AC%E0%A6%87%E0%A6%B6"))
    }

    @Test
    fun jsonParsing_parsesRealFixture() {
        val fixtureStream = javaClass.classLoader?.getResourceAsStream("musicbrainz_recording_search.json")
            ?: error("Resource not found: musicbrainz_recording_search.json")
        val json = fixtureStream.bufferedReader().use { it.readText() }

        val candidates = MusicBrainzSongInfoLookup.parseCandidates(json)
        assertEquals(5, candidates.size)

        val first = candidates[0]
        assertEquals("738ce955-61a0-4202-bef5-e0d23dc2f9e6", first.recordingId)
        assertEquals("We Will Rock You: We Will Rock You", first.title)
        assertEquals("Alex Melcher", first.artist)
        assertEquals(100, first.score)
        assertEquals("2009", first.year)
        assertEquals(144880L, first.durationMs)
        assertEquals("Best of Musical: Gala 2010", first.releaseTitle)
        assertEquals(listOf("96930a7f-b711-41ad-9bae-14c95f8e784c"), first.releaseIds)

        val second = candidates[1]
        assertEquals("3920e93e-e5cd-4ee9-9667-d1382729b599", second.recordingId)
        assertEquals("Noorkuu", second.artist)
        assertEquals(93, second.score)
        assertEquals(2, second.releaseIds.size)
        assertEquals("10", second.releaseTitle)
    }

    @Test
    fun rateLimiter_enforcesAtLeast1100msBetweenRequests() = runBlocking {
        val clock = FakeClock(1000L)
        var totalSleptMs = 0L
        val sleeper: suspend (Long) -> Unit = {
            totalSleptMs += it
            clock.current += it
        }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val limiter = RateLimiter(clock = clock, sleeper = sleeper)

        // First request at t = 1000
        val r1 = limiter.execute { client.newCall(okhttp3.Request.Builder().url("https://example.com/1").build()) }
        assertEquals(200, r1.code)
        assertEquals(0L, totalSleptMs)

        // Advance only 200 ms -> t = 1200
        clock.current = 1200L

        // Second request requires 900 ms throttle wait
        val r2 = limiter.execute { client.newCall(okhttp3.Request.Builder().url("https://example.com/2").build()) }
        assertEquals(200, r2.code)
        assertEquals(900L, totalSleptMs)
        assertEquals(2100L, clock.current)
    }

    @Test
    fun rateLimiter_503RetryOnce_succeedsOnSecondAttempt() = runBlocking {
        val clock = FakeClock(1000L)
        var sleptMs = 0L
        val sleeper: suspend (Long) -> Unit = {
            sleptMs += it
            clock.current += it
        }

        var attempt = 0
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempt++
                val code = if (attempt == 1) 503 else 200
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code == 200) "OK" else "Busy")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val limiter = RateLimiter(clock = clock, sleeper = sleeper)
        val response = limiter.execute { client.newCall(okhttp3.Request.Builder().url("https://example.com/").build()) }
        assertEquals(200, response.code)
        assertEquals(2, attempt)
        assertEquals(3000L, sleptMs)
    }

    @Test
    fun rateLimiter_503RetryOnce_failsWhenSecondAttemptAlso503() = runBlocking {
        val clock = FakeClock(1000L)
        val sleeper: suspend (Long) -> Unit = { clock.current += it }

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Busy")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val limiter = RateLimiter(clock = clock, sleeper = sleeper)
        try {
            limiter.execute { client.newCall(okhttp3.Request.Builder().url("https://example.com/").build()) }
            fail("Expected LookupBusyException")
        } catch (e: LookupBusyException) {
            // Success
        }
    }

    @Test
    fun fetchCover_404ThenSuccessAcrossReleases() = runBlocking {
        val validJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                if (url.contains("release-1")) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validJpeg.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }
            }
            .build()

        val lookup = MusicBrainzSongInfoLookup(
            versionName = "0.3.2",
            client = client,
            rateLimiter = RateLimiter(clock = FakeClock(), sleeper = {}),
        )

        val candidate = Candidate(
            recordingId = "rec-1",
            title = "Test",
            artist = "Artist",
            releaseTitle = "Album",
            year = "2020",
            durationMs = 120000L,
            score = 100,
            releaseIds = listOf("release-1", "release-2"),
        )

        val coverBytes = lookup.fetchCover(candidate)
        assertNotNull(coverBytes)
        assertArrayEquals(validJpeg, coverBytes)
    }

    @Test
    fun fetchCover_nonImageBytes_rejected() = runBlocking {
        val htmlBytes = "<html><body>Not an image</body></html>".toByteArray()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(htmlBytes.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val lookup = MusicBrainzSongInfoLookup(
            versionName = "0.3.2",
            client = client,
            rateLimiter = RateLimiter(clock = FakeClock(), sleeper = {}),
        )

        val candidate = Candidate(
            recordingId = "rec-1",
            title = "Test",
            artist = "Artist",
            releaseTitle = "Album",
            year = "2020",
            durationMs = 120000L,
            score = 100,
            releaseIds = listOf("release-1"),
        )

        val coverBytes = lookup.fetchCover(candidate)
        assertNull(coverBytes)
    }

    @Test
    fun fetchCover_oversizeBytes_rejected() = runBlocking {
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val largeBytes = header + ByteArray(2 * 1024 * 1024 + 10)

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(largeBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()

        val lookup = MusicBrainzSongInfoLookup(
            versionName = "0.3.2",
            client = client,
            rateLimiter = RateLimiter(clock = FakeClock(), sleeper = {}),
        )

        val candidate = Candidate(
            recordingId = "rec-1",
            title = "Test",
            artist = "Artist",
            releaseTitle = "Album",
            year = "2020",
            durationMs = 120000L,
            score = 100,
            releaseIds = listOf("release-1"),
        )

        val coverBytes = lookup.fetchCover(candidate)
        assertNull(coverBytes)
    }
}
