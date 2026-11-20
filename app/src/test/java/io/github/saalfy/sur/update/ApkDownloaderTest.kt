package io.github.saalfy.sur.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApkDownloaderTest {
    private val signer = TestSigner()
    private val root: File = Files.createTempDirectory("sur-upd").toFile()
    private val dir = File(root, "updates")
    private val manifest = signer.parser().parse(signer.envelope(manifestFields()))

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun downloader(handler: (okhttp3.Request) -> okhttp3.Response) = ApkDownloader(fakeClient(handler), dir)

    @Test
    fun download_streamsVerifiesAndRenames() = runBlocking {
        val progress = mutableListOf<Long>()
        val d = downloader { respond(it, 200, bytesBody(TEST_APK)) }
        val result = d.download(manifest) { bytes, _ -> progress += bytes }
        assertTrue(result is ApkDownloadResult.Verified)
        val file = (result as ApkDownloadResult.Verified).file
        assertEquals(File(dir, "sur-10.apk"), file)
        assertArrayEquals(TEST_APK, file.readBytes())
        assertFalse(d.partFile(manifest).exists())
        assertEquals(TEST_APK.size.toLong(), progress.last())
        assertEquals(file, d.verifiedApk(manifest))
    }

    @Test
    fun checksumMismatch_deletesEverything() = runBlocking {
        val wrong = TEST_APK.copyOf().also { it[100] = (it[100] + 1).toByte() }
        val d = downloader { respond(it, 200, bytesBody(wrong)) }
        assertEquals(ApkDownloadResult.Invalid(ApkDownloadResult.Problem.CHECKSUM_MISMATCH), d.download(manifest))
        assertFalse(d.partFile(manifest).exists())
        assertFalse(d.finalFile(manifest).exists())
        assertNull(d.verifiedApk(manifest))
    }

    @Test
    fun bodyLongerThanSigned_isRejected() = runBlocking {
        val d = downloader { respond(it, 200, bytesBody(TEST_APK + ByteArray(10))) }
        assertEquals(ApkDownloadResult.Invalid(ApkDownloadResult.Problem.SIZE_MISMATCH), d.download(manifest))
        assertFalse(d.partFile(manifest).exists())
    }

    @Test
    fun httpError_isReportedAndNothingIsKept() = runBlocking {
        val d = downloader { respond(it, 500) }
        assertEquals(ApkDownloadResult.Interrupted(NetworkFailure.Http(500), 0), d.download(manifest))
        assertNull(d.verifiedApk(manifest))
    }

    @Test
    fun interruptedDownload_keepsPartAndResumesWithRange() = runBlocking {
        val first = downloader { respond(it, 200, brokenBody(TEST_APK, failAfter = 100_000)) }
        val r1 = first.download(manifest)
        assertTrue(r1 is ApkDownloadResult.Interrupted)
        assertEquals(100_000L, first.partFile(manifest).length())

        var rangeHeader: String? = null
        val second = downloader { req ->
            rangeHeader = req.header("Range")
            val from = 100_000
            respond(
                req, 206, bytesBody(TEST_APK.copyOfRange(from, TEST_APK.size)),
                mapOf("Content-Range" to "bytes $from-${TEST_APK.size - 1}/${TEST_APK.size}"),
            )
        }
        val r2 = second.download(manifest)
        assertEquals("bytes=100000-", rangeHeader)
        assertTrue(r2 is ApkDownloadResult.Verified)
        assertArrayEquals(TEST_APK, (r2 as ApkDownloadResult.Verified).file.readBytes())
    }

    @Test
    fun serverIgnoringRange_restartsFromZero() = runBlocking {
        downloader { respond(it, 200, brokenBody(TEST_APK, failAfter = 50_000)) }.download(manifest)
        val d = downloader { respond(it, 200, bytesBody(TEST_APK)) }
        val result = d.download(manifest)
        assertTrue(result is ApkDownloadResult.Verified)
        assertArrayEquals(TEST_APK, (result as ApkDownloadResult.Verified).file.readBytes())
    }

    @Test
    fun wrongContentRange_restartsFromZero() = runBlocking {
        downloader { respond(it, 200, brokenBody(TEST_APK, failAfter = 50_000)) }.download(manifest)
        var calls = 0
        val d = downloader { req ->
            calls++
            if (req.header("Range") != null) {
                respond(req, 206, bytesBody(TEST_APK), mapOf("Content-Range" to "bytes 0-${TEST_APK.size - 1}/${TEST_APK.size}"))
            } else {
                respond(req, 200, bytesBody(TEST_APK))
            }
        }
        assertTrue(d.download(manifest) is ApkDownloadResult.Verified)
        assertEquals(2, calls)
    }

    @Test
    fun corruptPartial_isCaughtByChecksumAfterResume() = runBlocking {
        dir.mkdirs()
        File(dir, "sur-10.apk.part").writeBytes(ByteArray(100_000)) // wrong bytes on disk
        val d = downloader { req ->
            respond(
                req, 206, bytesBody(TEST_APK.copyOfRange(100_000, TEST_APK.size)),
                mapOf("Content-Range" to "bytes 100000-${TEST_APK.size - 1}/${TEST_APK.size}"),
            )
        }
        assertEquals(ApkDownloadResult.Invalid(ApkDownloadResult.Problem.CHECKSUM_MISMATCH), d.download(manifest))
        assertFalse(d.partFile(manifest).exists())
    }

    @Test
    fun alreadyVerifiedFile_isReusedWithoutNetwork() = runBlocking {
        dir.mkdirs()
        File(dir, "sur-10.apk").writeBytes(TEST_APK)
        val d = downloader { error("no network expected") }
        assertTrue(d.download(manifest) is ApkDownloadResult.Verified)
    }

    @Test
    fun secondConcurrentDownload_isRefused() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val d = downloader { req ->
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            respond(req, 200, bytesBody(TEST_APK))
        }
        val first = async(Dispatchers.IO) { d.download(manifest) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        assertEquals(ApkDownloadResult.Busy, d.download(manifest))
        release.countDown()
        assertTrue(withTimeout(10_000) { first.await() } is ApkDownloadResult.Verified)
    }

    @Test
    fun cancellation_stopsTheDownload() = runBlocking {
        val entered = CountDownLatch(1)
        val d = downloader { req ->
            entered.countDown()
            Thread.sleep(1_500) // a slow server answering after the user cancelled
            respond(req, 200, bytesBody(TEST_APK))
        }
        val job = async(Dispatchers.IO) { d.download(manifest) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val started = System.nanoTime()
        job.cancel()
        runCatching { job.await() }
        assertTrue(job.isCancelled)
        assertNull(d.verifiedApk(manifest))
        d.deletePartial(manifest)
        assertFalse(d.partFile(manifest).exists())
        // The downloader is free again afterwards.
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 10_000)
    }

    @Test
    fun cleanup_onlyTouchesUpdateFiles() {
        dir.mkdirs()
        val keepFinal = File(dir, "sur-10.apk").apply { writeText("x") }
        val stale = File(dir, "sur-9.apk").apply { writeText("x") }
        val stalePart = File(dir, "sur-8.apk.part").apply { writeText("x") }
        val unrelatedHere = File(dir, "notes.txt").apply { writeText("x") }
        val database = File(root, "sur.db").apply { writeText("playlists") }
        val prefs = File(root, "sur_prefs.xml").apply { writeText("settings") }

        downloader { error("unused") }.deleteAllExcept(manifest)

        assertTrue(keepFinal.exists())
        assertFalse(stale.exists())
        assertFalse(stalePart.exists())
        assertTrue(unrelatedHere.exists())
        assertTrue(database.exists())
        assertTrue(prefs.exists())
    }

    @Test
    fun fileNames_comeOnlyFromTheVersionCode() {
        val d = downloader { error("unused") }
        assertEquals(dir, d.finalFile(manifest).parentFile)
        assertEquals("sur-10.apk", d.finalFile(manifest).name)
        assertEquals("sur-10.apk.part", d.partFile(manifest).name)
    }
}
