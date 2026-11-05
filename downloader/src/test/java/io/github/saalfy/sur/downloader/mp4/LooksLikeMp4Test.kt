package io.github.saalfy.sur.downloader.mp4

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LooksLikeMp4Test {

    @get:org.junit.Rule
    val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray): File =
        tmp.newFile().apply { writeBytes(bytes) }

    @Test
    fun validMp4Header_returnsTrue() {
        val bytes = byteArrayOf(0, 0, 0, 24) +
            "ftyp".toByteArray(Charsets.US_ASCII) +
            "M4A ".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0)
        assertTrue(looksLikeMp4(file(bytes)))
    }

    @Test
    fun htmlText_returnsFalse() {
        assertFalse(looksLikeMp4(file("<!DOCTYPE html><html>".toByteArray(Charsets.US_ASCII))))
    }

    @Test
    fun emptyFile_returnsFalse() {
        assertFalse(looksLikeMp4(file(ByteArray(0))))
    }

    @Test
    fun truncated8ByteFile_returnsFalse() {
        val bytes = byteArrayOf(0, 0, 0, 8) +
            "ftyp".toByteArray(Charsets.US_ASCII)
        assertFalse(looksLikeMp4(file(bytes)))
    }

    @Test
    fun ftypInWrongPosition_returnsFalse() {
        val bytes = "ftyp".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0) +
            "M4A ".toByteArray(Charsets.US_ASCII)
        assertFalse(looksLikeMp4(file(bytes)))
    }
}
