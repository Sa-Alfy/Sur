package io.github.saalfy.sur.download

import io.github.saalfy.sur.R
import io.github.saalfy.sur.ui.download.stringRes
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.downloader.DownloadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadManagerTest {

    @Before
    fun setUp() {
        DownloadManager.reset()
    }

    @Test
    fun initialState_isIdle() {
        assertEquals(DownloadState.Idle, DownloadManager.state.value)
    }

    @Test
    fun updateState_transitionsThroughDownloadLifecycle() {
        DownloadManager.updateState(DownloadState.Resolving)
        assertEquals(DownloadState.Resolving, DownloadManager.state.value)

        DownloadManager.updateState(DownloadState.Downloading(0.42f))
        val downloading = DownloadManager.state.value
        assertTrue(downloading is DownloadState.Downloading)
        assertEquals(0.42f, (downloading as DownloadState.Downloading).progress, 0.001f)

        DownloadManager.updateState(DownloadState.Saving)
        assertEquals(DownloadState.Saving, DownloadManager.state.value)

        DownloadManager.updateState(DownloadState.Done(42L, "Test Song"))
        val done = DownloadManager.state.value
        assertTrue(done is DownloadState.Done)
        assertEquals(42L, (done as DownloadState.Done).songId)
        assertEquals("Test Song", done.title)

        DownloadManager.reset()
        assertEquals(DownloadState.Idle, DownloadManager.state.value)
    }

    @Test
    fun updateState_errorState() {
        DownloadManager.updateState(DownloadState.Error(DownloadError.NoInternet))
        val error = DownloadManager.state.value
        assertTrue(error is DownloadState.Error)
        assertEquals(DownloadError.NoInternet, (error as DownloadState.Error).error)
    }

    @Test
    fun emitEvent_busyEventDoesNotOverwriteCurrentProgress() {
        DownloadManager.updateState(DownloadState.Downloading(0.65f))

        // Emitting Busy must not touch the StateFlow
        DownloadManager.emitEvent(DownloadEvent.Busy)

        val currentState = DownloadManager.state.value
        assertTrue(currentState is DownloadState.Downloading)
        assertEquals(0.65f, (currentState as DownloadState.Downloading).progress, 0.001f)
    }

    @Test
    fun downloading_percentConstructorConvertsToRatio() {
        val state = DownloadState.Downloading(75)
        assertEquals(0.75f, state.progress, 0.001f)
    }

    @Test
    fun notEnoughStorage_stringResDiffersWhenZeroVsNonZero() {
        val errorZero = DownloadError.NotEnoughStorage(0)
        assertEquals(R.string.download_err_not_enough_storage, errorZero.stringRes())

        val errorWithSize = DownloadError.NotEnoughStorage(15 * 1024 * 1024L)
        assertEquals(R.string.download_err_not_enough_storage_size, errorWithSize.stringRes())
    }

    @Test
    fun surApplication_instanceExposesDownloadManager() {
        val app = SurApplication()
        DownloadManager.updateState(DownloadState.Resolving)
        assertEquals(DownloadState.Resolving, app.downloadManager.state.value)
    }
}
