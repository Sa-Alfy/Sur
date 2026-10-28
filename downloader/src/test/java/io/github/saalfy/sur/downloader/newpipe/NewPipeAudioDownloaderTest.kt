package io.github.saalfy.sur.downloader.newpipe

import io.github.saalfy.sur.downloader.DownloadError
import io.github.saalfy.sur.downloader.DownloadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NewPipeAudioDownloaderTest {

    @Test
    fun mapError_unknownHostException_mapsToNoInternet() {
        val result = NewPipeAudioDownloader.mapError(UnknownHostException("Unable to resolve host"))
        assertTrue(result is DownloadException)
        assertEquals(DownloadError.NoInternet, (result as DownloadException).error)
    }

    @Test
    fun mapError_connectException_mapsToNoInternet() {
        val result = NewPipeAudioDownloader.mapError(ConnectException("Connection refused"))
        assertTrue(result is DownloadException)
        assertEquals(DownloadError.NoInternet, (result as DownloadException).error)
    }

    @Test
    fun mapError_noRouteToHostException_mapsToNoInternet() {
        val result = NewPipeAudioDownloader.mapError(NoRouteToHostException("No route to host"))
        assertTrue(result is DownloadException)
        assertEquals(DownloadError.NoInternet, (result as DownloadException).error)
    }

    @Test
    fun mapError_socketTimeoutException_mapsToNoInternet() {
        val result = NewPipeAudioDownloader.mapError(SocketTimeoutException("Read timed out"))
        assertTrue(result is DownloadException)
        assertEquals(DownloadError.NoInternet, (result as DownloadException).error)
    }

    @Test
    fun mapError_plainIOException_mapsToFailed() {
        val result = NewPipeAudioDownloader.mapError(IOException("Connection reset by peer"))
        assertTrue(result is DownloadException)
        assertEquals(DownloadError.Failed, (result as DownloadException).error)
    }

    @Test
    fun mapError_plainIOExceptionWithoutMessage_mapsToFailed() {
        val result = NewPipeAudioDownloader.mapError(IOException())
        assertTrue(result is DownloadException)
        assertEquals(DownloadError.Failed, (result as DownloadException).error)
    }

    @Test
    fun mapError_ioExceptionWithEnospc_mapsToNotEnoughStorage() {
        val result = NewPipeAudioDownloader.mapError(IOException("write failed: ENOSPC (No space left on device)"))
        assertTrue(result is DownloadException)
        assertEquals(DownloadError.NotEnoughStorage(0), (result as DownloadException).error)
    }

    @Test
    fun mapError_ioExceptionWithNoSpaceLeft_mapsToNotEnoughStorage() {
        val result = NewPipeAudioDownloader.mapError(IOException("No space left on device"))
        assertTrue(result is DownloadException)
        assertEquals(DownloadError.NotEnoughStorage(0), (result as DownloadException).error)
    }

    @Test
    fun checkDownloadComplete_matchingLength_doesNotThrow() {
        NewPipeAudioDownloader.checkDownloadComplete(1000L, 1000L)
    }

    @Test
    fun checkDownloadComplete_nullExpectedLength_doesNotThrow() {
        NewPipeAudioDownloader.checkDownloadComplete(null, 500L)
    }

    @Test
    fun checkDownloadComplete_truncated_throwsDownloadExceptionFailed() {
        try {
            NewPipeAudioDownloader.checkDownloadComplete(1000L, 800L)
            org.junit.Assert.fail("Expected DownloadException")
        } catch (e: DownloadException) {
            assertEquals(DownloadError.Failed, e.error)
        }
    }

    @Test
    fun checkDownloadComplete_oversized_throwsDownloadExceptionFailed() {
        try {
            NewPipeAudioDownloader.checkDownloadComplete(1000L, 1200L)
            org.junit.Assert.fail("Expected DownloadException")
        } catch (e: DownloadException) {
            assertEquals(DownloadError.Failed, e.error)
        }
    }
}
