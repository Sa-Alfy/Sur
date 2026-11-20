package io.github.saalfy.sur.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateManagerTest {
    private val signer = TestSigner()
    private val root: File = Files.createTempDirectory("sur-mgr").toFile()
    private val dir = File(root, "updates")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manifestRequests = AtomicInteger()
    private var now = 1_000_000_000L

    private class MemoryStore : UpdateStore {
        override var autoCheck = true
        override var lastSuccessfulCheckMs = 0L
        override var lastAttemptMs = 0L
        override var cachedEnvelope: String? = null
        override var notifiedVersionCode = 0L
        override var pendingInstallVersionCode = 0L
    }

    private class FakePlatform(var installed: Long = 9) : UpdatePlatform {
        var canInstall = true
        var identity: (File) -> ApkIdentity? = { ApkIdentity(PKG, 10, setOf("cert-a")) }
        override val installedVersionCode get() = installed
        override val installedVersionName get() = "1.0.0"
        override val sdkInt = 28
        override val packageName = PKG
        override val installedSignerSha256: Set<String>? = setOf("cert-a")
        override fun canRequestPackageInstalls() = canInstall
        override fun inspectApk(file: File) = identity(file)
    }

    private val store = MemoryStore()
    private val platform = FakePlatform()
    private var manifestBody: () -> Response? = { null }
    private var apkHandler: (Request) -> Response = { respond(it, 200, bytesBody(TEST_APK)) }

    private fun serve(fields: Map<String, Any?> = manifestFields()) {
        val env = signer.envelope(fields)
        manifestBody = { respond(Request.Builder().url(UpdateChecker.MANIFEST_URL).build(), 200, bytesBody(env)) }
    }

    private val client = fakeClient { req ->
        if (req.url.toString() == UpdateChecker.MANIFEST_URL) {
            manifestRequests.incrementAndGet()
            manifestBody() ?: respond(req, 404)
        } else {
            apkHandler(req)
        }
    }

    private fun manager(): UpdateManager {
        val parser = signer.parser()
        return UpdateManager(
            scope = scope,
            checker = UpdateChecker(client, parser),
            parser = parser,
            downloader = ApkDownloader(client, dir),
            store = store,
            platform = platform,
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private suspend fun UpdateManager.awaitStatus(predicate: (UpdateStatus) -> Boolean): UpdateStatus =
        withTimeout(10_000) {
            while (!predicate(status.value)) delay(10)
            status.value
        }

    @Test
    fun newerRelease_isOfferedAndNotifiedOnce() = runBlocking {
        serve()
        val m = manager()
        m.checkIfDue()!!.join()
        val s = m.status.value
        assertTrue(s is UpdateStatus.Available)
        assertEquals(10L, m.notice.value!!.versionCode)
        m.noticeShown()
        assertNull(m.notice.value)
        assertEquals(10L, store.notifiedVersionCode)
        // Next app start: cached, already notified.
        val again = manager()
        again.restore().join()
        assertTrue(again.status.value is UpdateStatus.Available)
        assertNull(again.notice.value)
    }

    @Test
    fun sameOrOlderRelease_isUpToDate() = runBlocking {
        serve(manifestFields(versionCode = 9))
        val m = manager()
        m.checkNow()!!.join()
        assertEquals(UpdateStatus.UpToDate, m.status.value)
        assertNull(m.notice.value)
        platform.installed = 12
        serve(manifestFields(versionCode = 10))
        m.checkNow()!!.join()
        assertEquals(UpdateStatus.UpToDate, m.status.value)
    }

    @Test
    fun cooldown_avoidsRepeatedRequests() = runBlocking {
        serve()
        val m = manager()
        m.checkIfDue()!!.join()
        assertEquals(1, manifestRequests.get())
        now += 60_000
        assertNull(m.checkIfDue())
        now += UpdateManager.CHECK_INTERVAL_MS
        m.checkIfDue()!!.join()
        assertEquals(2, manifestRequests.get())
    }

    @Test
    fun autoCheckOff_makesNoRequestButManualCheckWorks() = runBlocking {
        serve()
        val m = manager()
        m.setAutoCheck(false)
        assertNull(m.checkIfDue())
        assertEquals(0, manifestRequests.get())
        m.checkNow()!!.join()
        assertEquals(1, manifestRequests.get())
        assertTrue(m.status.value is UpdateStatus.Available)
    }

    @Test
    fun failedAutoCheck_isQuietAndRetriedLater() = runBlocking {
        val m = manager() // manifest endpoint answers 404
        m.checkIfDue()!!.join()
        assertEquals(UpdateStatus.Idle, m.status.value)
        assertNull(m.checkError.value)
        now += 60_000
        assertNull(m.checkIfDue()) // within retry interval
        now += UpdateManager.RETRY_INTERVAL_MS
        assertTrue(m.checkIfDue() != null)
    }

    @Test
    fun failedManualCheck_keepsKnownUpdateAndShowsError() = runBlocking {
        serve()
        val m = manager()
        m.checkNow()!!.join()
        manifestBody = { null }
        m.checkNow()!!.join()
        assertTrue(m.status.value is UpdateStatus.Available)
        assertEquals(CheckError(NetworkFailure.Http(404), null), m.checkError.value)
    }

    @Test
    fun invalidSignature_neverOffersAnUpdate() = runBlocking {
        val env = TestSigner().envelope(manifestFields())
        manifestBody = { respond(Request.Builder().url(UpdateChecker.MANIFEST_URL).build(), 200, bytesBody(env)) }
        val m = manager()
        m.checkNow()!!.join()
        assertEquals(UpdateStatus.Idle, m.status.value)
        assertEquals(CheckError(null, ManifestRejectedException.Reason.BAD_SIGNATURE), m.checkError.value)
        assertNull(store.cachedEnvelope)
    }

    @Test
    fun tamperedCache_isDiscardedOnRestore() = runBlocking {
        store.cachedEnvelope = String(TestSigner().envelope(manifestFields()))
        val m = manager()
        m.restore().join()
        assertEquals(UpdateStatus.Idle, m.status.value)
        assertNull(store.cachedEnvelope)
    }

    @Test
    fun download_thenInstall_happyPath() = runBlocking {
        serve()
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        assertTrue(m.status.value is UpdateStatus.ReadyToInstall)
        val step = m.prepareInstall()
        assertTrue(step is InstallStep.Launch)
        assertEquals(File(dir, "sur-10.apk"), (step as InstallStep.Launch).file)
        assertEquals(10L, store.pendingInstallVersionCode)
    }

    @Test
    fun duplicateDownloadRequests_startOnlyOneDownload() = runBlocking {
        serve()
        val apkRequests = AtomicInteger()
        val release = CountDownLatch(1)
        apkHandler = { req ->
            apkRequests.incrementAndGet()
            release.await(10, TimeUnit.SECONDS)
            respond(req, 200, bytesBody(TEST_APK))
        }
        val m = manager()
        m.checkNow()!!.join()
        val first = m.startDownload()
        assertTrue(first != null)
        assertNull(m.startDownload())
        assertNull(m.checkNow()) // no check while downloading either
        release.countDown()
        first!!.join()
        assertEquals(1, apkRequests.get())
    }

    @Test
    fun checksumMismatch_failsAndCleansUp() = runBlocking {
        serve()
        apkHandler = { respond(it, 200, bytesBody(TEST_APK.reversedArray())) }
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        assertEquals(DownloadProblem.CHECKSUM_MISMATCH, (m.status.value as UpdateStatus.DownloadFailed).problem)
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
        assertEquals(InstallStep.NotReady, m.prepareInstall())
    }

    @Test
    fun interruptedDownload_canResume() = runBlocking {
        serve()
        apkHandler = { respond(it, 200, brokenBody(TEST_APK, failAfter = 120_000)) }
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        val s = m.status.value as UpdateStatus.DownloadInterrupted
        assertEquals(120_000L, s.bytes)
        apkHandler = { req ->
            respond(
                req, 206, bytesBody(TEST_APK.copyOfRange(120_000, TEST_APK.size)),
                mapOf("Content-Range" to "bytes 120000-${TEST_APK.size - 1}/${TEST_APK.size}"),
            )
        }
        m.startDownload()!!.join()
        assertTrue(m.status.value is UpdateStatus.ReadyToInstall)
    }

    @Test
    fun cancelDownload_deletesPartialAndReturnsToAvailable() = runBlocking {
        serve()
        val entered = CountDownLatch(1)
        apkHandler = { req ->
            entered.countDown()
            Thread.sleep(500)
            respond(req, 200, brokenBody(TEST_APK, failAfter = TEST_APK.size))
        }
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        m.cancelDownload().join()
        assertTrue(m.status.value is UpdateStatus.Available)
        assertFalse(File(dir, "sur-10.apk.part").exists())
        assertFalse(File(dir, "sur-10.apk").exists())
    }

    @Test
    fun wrongPackageInsideApk_isRejected() = runBlocking {
        serve()
        platform.identity = { ApkIdentity("com.other.app", 10, setOf("cert-a")) }
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        assertEquals(DownloadProblem.WRONG_PACKAGE, (m.status.value as UpdateStatus.DownloadFailed).problem)
        assertFalse(File(dir, "sur-10.apk").exists())
    }

    @Test
    fun differentSigningCertificate_isRejected() = runBlocking {
        serve()
        platform.identity = { ApkIdentity(PKG, 10, setOf("cert-b")) }
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        assertEquals(DownloadProblem.WRONG_SIGNER, (m.status.value as UpdateStatus.DownloadFailed).problem)
    }

    @Test
    fun unknownArchiveCertificate_isLeftToAndroidInstaller() = runBlocking {
        serve()
        platform.identity = { ApkIdentity(PKG, 10, null) }
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        assertTrue(m.status.value is UpdateStatus.ReadyToInstall)
    }

    @Test
    fun missingInstallPermission_isRequestedAndRechecked() = runBlocking {
        serve()
        platform.canInstall = false
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        assertEquals(InstallStep.NeedsPermission, m.prepareInstall())
        assertTrue(m.status.value is UpdateStatus.InstallPermissionRequired)
        assertEquals(0L, store.pendingInstallVersionCode)
        m.recheckInstallPermission() // user came back without granting
        assertTrue(m.status.value is UpdateStatus.InstallPermissionRequired)
        platform.canInstall = true
        m.recheckInstallPermission()
        assertTrue(m.status.value is UpdateStatus.ReadyToInstall)
        assertTrue(m.prepareInstall() is InstallStep.Launch)
    }

    @Test
    fun installerCancelled_keepsApkAndAllowsRetry() = runBlocking {
        serve()
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        assertTrue(m.prepareInstall() is InstallStep.Launch)
        m.onInstallerResult(InstallerOutcome.Cancelled)
        assertTrue(m.status.value is UpdateStatus.InstallCancelled)
        assertEquals(0L, store.pendingInstallVersionCode)
        assertTrue(File(dir, "sur-10.apk").exists())
        assertTrue(m.prepareInstall() is InstallStep.Launch)
    }

    @Test
    fun installerFailure_isReportedNotClaimedAsSuccess() = runBlocking {
        serve()
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        m.prepareInstall()
        m.onInstallerResult(InstallerOutcome.Failed)
        assertTrue(m.status.value is UpdateStatus.InstallFailed)
        m.onInstallerResult(InstallerOutcome.Succeeded) // never turns into a success state by itself
        assertTrue(m.status.value is UpdateStatus.InstallFailed)
        assertFalse(m.justUpdated.value)
    }

    @Test
    fun apkModifiedAfterVerification_isNotInstalled() = runBlocking {
        serve()
        val m = manager()
        m.checkNow()!!.join()
        m.startDownload()!!.join()
        File(dir, "sur-10.apk").appendBytes(byteArrayOf(1))
        assertEquals(InstallStep.NotReady, m.prepareInstall())
        assertTrue(m.status.value is UpdateStatus.Available)
    }

    @Test
    fun restartOnNewVersion_reportsUpdateAndCleansFiles_withoutTouchingUserData() = runBlocking {
        // Before: v9 installed, v10 downloaded and handed to the installer.
        serve()
        val before = manager()
        before.checkNow()!!.join()
        before.startDownload()!!.join()
        before.prepareInstall()
        val playlists = File(root, "databases/sur.db").apply { parentFile!!.mkdirs(); writeText("playlists") }
        val settings = File(root, "shared_prefs/sur_prefs.xml").apply { parentFile!!.mkdirs(); writeText("settings") }

        // Android replaced the app in place; the process restarts as v10.
        platform.installed = 10
        val after = manager()
        after.restore().join()
        assertTrue(after.justUpdated.value)
        assertEquals(0L, store.pendingInstallVersionCode)
        assertEquals(UpdateStatus.UpToDate, after.awaitStatus { it != UpdateStatus.Idle })
        assertEquals(emptyList<String>(), dir.list()?.toList().orEmpty())
        assertEquals("playlists", playlists.readText())
        assertEquals("settings", settings.readText())
    }

    @Test
    fun mandatoryNotice_showsOncePerSession() = runBlocking {
        serve(manifestFields(overrides = mapOf("mandatory" to true)))
        val m = manager()
        m.checkNow()!!.join()
        assertNull(m.notice.value) // manual checks don't pop notices
        m.checkIfDue()
        now += UpdateManager.CHECK_INTERVAL_MS * 2
        m.checkIfDue()!!.join()
        assertTrue(m.notice.value!!.mandatory)
        m.noticeShown()
        now += UpdateManager.CHECK_INTERVAL_MS * 2
        m.checkIfDue()!!.join()
        assertNull(m.notice.value)
    }
}
