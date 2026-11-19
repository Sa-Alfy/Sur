package io.github.saalfy.sur.downloader.mp4

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * R2-16: [looksLikeMp4] only proves the container header. This asks Android's own extractor
 * (the same one the player relies on) for an audio track and reads its first sample, so a file
 * with a valid header but no usable audio is rejected before it reaches the library.
 * Returns false on any error.
 */
fun hasReadableAudio(file: File): Boolean {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return false
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 256 * 1024)
        } else {
            1024 * 1024
        }
        extractor.readSampleData(ByteBuffer.allocate(size), 0) > 0
    } catch (_: Exception) {
        false
    } finally {
        extractor.release()
    }
}
