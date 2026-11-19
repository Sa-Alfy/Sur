package io.github.saalfy.sur.download

import io.github.saalfy.sur.downloader.DownloadError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** R2-03 / R2-05 / R2-11: only the active run may change download state. */
class DownloadOwnershipTest {

    @Before
    fun setUp() = DownloadManager.clearForTest()

    @Test
    fun progressAfterCancel_isDropped_stateStaysIdle() {
        val t = DownloadManager.begin()
        assertTrue(DownloadManager.update(t, DownloadState.Downloading(0.3f)))
        DownloadManager.cancelActive()
        var notified = false
        assertFalse(DownloadManager.update(t, DownloadState.Downloading(0.4f)) { notified = true })
        assertEquals(DownloadState.Idle, DownloadManager.state.value)
        assertFalse("no notification after cancel", notified)
    }

    @Test
    fun doneAfterCancel_isNotReported() {
        val t = DownloadManager.begin()
        DownloadManager.cancelActive()
        assertFalse(DownloadManager.update(t, DownloadState.Done(1L, "x")))
        assertEquals(DownloadState.Idle, DownloadManager.state.value)
    }

    @Test
    fun oldRun_cannotTouchNewerRun() {
        val old = DownloadManager.begin()
        DownloadManager.cancelActive()
        val new = DownloadManager.begin()
        DownloadManager.update(new, DownloadState.Downloading(0.1f))

        assertFalse(DownloadManager.update(old, DownloadState.Downloading(0.9f)))
        assertFalse(DownloadManager.update(old, DownloadState.Error(DownloadError.Failed)))
        assertFalse(DownloadManager.update(old, DownloadState.Idle))
        assertEquals(0.1f, (DownloadManager.state.value as DownloadState.Downloading).progress, 0f)
        assertTrue(DownloadManager.isOwner(new))
    }

    @Test
    fun terminalState_releasesOwnership() {
        val t = DownloadManager.begin()
        assertTrue(DownloadManager.update(t, DownloadState.Done(7L, "song")))
        assertFalse(DownloadManager.isOwner(t))
        assertFalse(DownloadManager.update(t, DownloadState.Downloading(0.5f)))
        assertTrue(DownloadManager.state.value is DownloadState.Done)
    }

    @Test
    fun reset_doesNotClobberActiveRun_butDismissesTerminalCard() {
        val t = DownloadManager.begin()
        DownloadManager.update(t, DownloadState.Saving)
        DownloadManager.reset()
        assertEquals(DownloadState.Saving, DownloadManager.state.value)
        DownloadManager.update(t, DownloadState.Error(DownloadError.Failed))
        DownloadManager.reset()
        assertEquals(DownloadState.Idle, DownloadManager.state.value)
    }

    @Test
    fun beginReplacesLeakedOwner_soStartIsNeverStuck() {
        val leaked = DownloadManager.begin()
        val fresh = DownloadManager.begin()
        assertFalse(DownloadManager.isOwner(leaked))
        assertTrue(DownloadManager.update(fresh, DownloadState.Done(1L, "ok")))
    }

    /** A background "download loop" keeps calling back while the user cancels and restarts. */
    @Test
    fun concurrentCallbacksFromCancelledRun_neverLeakIntoNewRun() = runBlocking {
        repeat(50) {
            DownloadManager.clearForTest()
            val old = DownloadManager.begin()
            val started = CountDownLatch(1)
            val staleWins = AtomicInteger()
            val job = launch(Dispatchers.Default) {
                started.countDown()
                var p = 0f
                // Like NewPipeAudioDownloader: progress callback, then a cancellation check.
                while (isActive) {
                    p = (p + 0.01f) % 1f
                    DownloadManager.update(old, DownloadState.Downloading(p))
                }
                if (DownloadManager.update(old, DownloadState.Done(1L, "stale"))) staleWins.incrementAndGet()
            }
            withContext(Dispatchers.IO) { started.await() }
            DownloadManager.cancelActive()
            job.cancel()
            val new = DownloadManager.begin()
            DownloadManager.update(new, DownloadState.Downloading(0.5f, "new"))
            job.join()
            assertEquals(0, staleWins.get())
            val s = DownloadManager.state.value as DownloadState.Downloading
            assertEquals("new", s.title)
        }
    }

    /** Cancelling a run that is suspended reports Idle through the CE path, never Done. */
    @Test
    fun cancellationPath_reportsIdleNotDone() = runBlocking {
        val t = DownloadManager.begin()
        val job = launch(Dispatchers.Default) {
            try {
                DownloadManager.update(t, DownloadState.Saving)
                delay(10_000)
                DownloadManager.update(t, DownloadState.Done(1L, "x"))
            } catch (e: CancellationException) {
                DownloadManager.update(t, DownloadState.Idle)
                throw e
            }
        }
        delay(50)
        job.cancel()
        job.join()
        assertEquals(DownloadState.Idle, DownloadManager.state.value)
        assertFalse(DownloadManager.isOwner(t))
    }
}
