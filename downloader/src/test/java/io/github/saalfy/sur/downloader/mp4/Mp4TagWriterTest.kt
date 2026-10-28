package io.github.saalfy.sur.downloader.mp4

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class Mp4TagWriterTest {

    @get:org.junit.Rule
    val tmp = TemporaryFolder()

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray = ByteArray(0)) =
        int(8 + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload

    private val ftyp = box("ftyp", "M4A ".toByteArray() + int(0))
    private val mvhd = box("mvhd", ByteArray(100) { 7 })
    private val mdatPayload = ByteArray(5000) { (it % 251).toByte() }
    private val mdat = box("mdat", mdatPayload)

    private fun file(vararg parts: ByteArray): File {
        val out = ByteArrayOutputStream()
        parts.forEach(out::write)
        return tmp.newFile().apply { writeBytes(out.toByteArray()) }
    }

    private fun topLevelTypes(f: File): List<Pair<String, Long>> {
        val result = mutableListOf<Pair<String, Long>>()
        RandomAccessFile(f, "r").use { raf ->
            var offset = 0L
            while (offset < raf.length()) {
                raf.seek(offset)
                val size = raf.readInt().toLong()
                val type = ByteArray(4).also(raf::readFully)
                result += String(type, Charsets.ISO_8859_1) to offset
                offset += size
            }
        }
        return result
    }

    private fun mdatBytes(f: File): ByteArray {
        val offset = topLevelTypes(f).first { it.first == "mdat" }.second
        return f.readBytes().copyOfRange(offset.toInt() + 8, offset.toInt() + 8 + mdatPayload.size)
    }

    @Test
    fun moovAtEnd_rewrittenInPlace() {
        val f = file(ftyp, mdat, box("moov", mvhd))
        val placement = Mp4TagWriter.write(f, "Song", "Artist", "Album")

        assertEquals(TagPlacement.MOOV_AT_END, placement)
        assertEquals(mapOf("©nam" to "Song", "©ART" to "Artist", "©alb" to "Album"), Mp4TagWriter.readTags(f))
        assertEquals(listOf("ftyp", "mdat", "moov"), topLevelTypes(f).map { it.first })
        assertArrayEquals(mdatPayload, mdatBytes(f))
    }

    @Test
    fun moovBeforeFreeBox_usesFreeSpaceWithoutMovingData() {
        val f = file(ftyp, box("moov", mvhd), box("free", ByteArray(1024)), mdat)
        val lengthBefore = f.length()
        val mdatOffsetBefore = topLevelTypes(f).first { it.first == "mdat" }.second

        val placement = Mp4TagWriter.write(f, "Song", "Artist", null)

        assertEquals(TagPlacement.MOOV_INTO_FREE_BOX, placement)
        assertEquals(lengthBefore, f.length())
        assertEquals(mdatOffsetBefore, topLevelTypes(f).first { it.first == "mdat" }.second)
        assertEquals(listOf("ftyp", "moov", "free", "mdat"), topLevelTypes(f).map { it.first })
        assertEquals(mapOf("©nam" to "Song", "©ART" to "Artist"), Mp4TagWriter.readTags(f))
        assertArrayEquals(mdatPayload, mdatBytes(f))
    }

    @Test(expected = TagWriteException::class)
    fun moovBeforeData_withoutFreeSpace_refuses() {
        val f = file(ftyp, box("moov", mvhd), mdat)
        Mp4TagWriter.write(f, "Song", "Artist", "Album")
    }

    @Test(expected = TagWriteException::class)
    fun freeBoxTooSmall_refuses() {
        val f = file(ftyp, box("moov", mvhd), box("free", ByteArray(4)), mdat)
        Mp4TagWriter.write(f, "Song", "Artist", "Album")
    }

    @Test
    fun existingUdtaWithoutMeta_getsTagsInside() {
        val udta = box("udta", box("©xyz", ByteArray(12)))
        val f = file(ftyp, mdat, box("moov", mvhd + udta))
        Mp4TagWriter.write(f, "Song", null, null)
        assertEquals(mapOf("©nam" to "Song"), Mp4TagWriter.readTags(f))
    }

    @Test
    fun unicodeRoundTrips() {
        val f = file(ftyp, mdat, box("moov", mvhd))
        Mp4TagWriter.write(f, "২২.১২ বইশ", "Smooches", "Sur downloads")
        assertEquals("২২.১২ বইশ", Mp4TagWriter.readTags(f)["©nam"])
    }

    @Test(expected = TagWriteException::class)
    fun notAnMp4_refuses() {
        val f = tmp.newFile().apply { writeBytes(ByteArray(64) { 0x41 }) }
        Mp4TagWriter.write(f, "Song", null, null)
    }

    // --- Cover art tests ---

    /** Fake JPEG bytes (magic + payload). Bypass BitmapFactory; we test the tag plumbing only. */
    private val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(96) { (it + 1).toByte() }

    @Test
    fun coverRoundTrip_exactBytesAndTextTagsReadable() {
        val f = file(ftyp, mdat, box("moov", mvhd))
        Mp4TagWriter.write(f, "Title", "Artist", null, fakeJpeg)

        assertArrayEquals(fakeJpeg, Mp4TagWriter.readCover(f))
        assertEquals(mapOf("©nam" to "Title", "©ART" to "Artist"), Mp4TagWriter.readTags(f))
    }

    @Test
    fun coverFallback_notEnoughRoomForCover_writesTextOnly() {
        // Free box of 100 bytes is big enough for text-only tags (growth < 100)
        // but too small for cover art (fakeJpeg is 100 bytes alone, total growth > 200).
        // With-cover attempt fails and falls back to text-only tags.
        val f = file(ftyp, box("moov", mvhd), box("free", ByteArray(100)), mdat)
        val placement = Mp4TagWriter.write(f, "Song", null, null, fakeJpeg)

        assertEquals(TagPlacement.MOOV_INTO_FREE_BOX, placement)
        assertEquals(mapOf("©nam" to "Song"), Mp4TagWriter.readTags(f))
        assertEquals(null, Mp4TagWriter.readCover(f))
        assertArrayEquals(mdatPayload, mdatBytes(f))
    }

    @Test
    fun noCover_readCoverReturnsNull() {
        val f = file(ftyp, mdat, box("moov", mvhd))
        Mp4TagWriter.write(f, "Song", null, null)
        assertEquals(null, Mp4TagWriter.readCover(f))
    }
}

// --- Companion helper tests (pure, no android.graphics) ---

class IsJpegOrPngTest {
    private fun isJpegOrPng(bytes: ByteArray) =
        io.github.saalfy.sur.downloader.newpipe.NewPipeAudioDownloader.isJpegOrPng(bytes)

    @Test fun jpegMagicAccepted() {
        val b = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(true, isJpegOrPng(b))
    }

    @Test fun pngMagicAccepted() {
        val b = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        assertEquals(true, isJpegOrPng(b))
    }

    @Test fun randomBytesRejected() {
        val b = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertEquals(false, isJpegOrPng(b))
    }

    @Test fun tooShortRejected() {
        assertEquals(false, isJpegOrPng(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}
