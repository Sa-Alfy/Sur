package io.github.saalfy.sur.download

import io.github.saalfy.sur.downloader.DownloadError
import io.github.saalfy.sur.downloader.DownloadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TechnicalSummaryTest {

    @Test
    fun chainOfThreeCauses_joinsWithArrow() {
        val cause2 = IllegalArgumentException("third")
        val cause1 = IllegalStateException("second", cause2)
        val root = RuntimeException("first", cause1)

        val summary = technicalSummary(root)

        assertEquals("RuntimeException: first <- IllegalStateException: second <- IllegalArgumentException: third", summary)
    }

    @Test
    fun fourthCause_isDropped() {
        val cause3 = Exception("fourth")
        val cause2 = IllegalArgumentException("third", cause3)
        val cause1 = IllegalStateException("second", cause2)
        val root = RuntimeException("first", cause1)

        val summary = technicalSummary(root)

        assertEquals("RuntimeException: first <- IllegalStateException: second <- IllegalArgumentException: third", summary)
        assertFalse(summary.contains("fourth"))
    }

    @Test
    fun nullMessage_printsNoMessage() {
        val error = RuntimeException(null as String?)

        val summary = technicalSummary(error)

        assertEquals("RuntimeException: no message", summary)
    }

    @Test
    fun outputIsAtMost300Chars() {
        val longMessage = "a".repeat(400)
        val error = RuntimeException(longMessage)

        val summary = technicalSummary(error)

        assertTrue(summary.length <= 300)
        assertEquals(300, summary.length)
    }

    @Test
    fun downloadExceptionWrappingExtractionFailed_printsDetail() {
        val extractionFailed = DownloadError.ExtractionFailed("Sign in to confirm you're not a bot")
        val error = DownloadException(extractionFailed)

        val summary = technicalSummary(error)

        assertEquals("DownloadException: Sign in to confirm you're not a bot", summary)
    }
}

