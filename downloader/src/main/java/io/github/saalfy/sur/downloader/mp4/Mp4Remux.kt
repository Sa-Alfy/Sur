package io.github.saalfy.sur.downloader.mp4

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

private const val DEFAULT_SAMPLE_BUFFER_BYTES = 256 * 1024

/**
 * Copies the audio track of a (fragmented, DASH-style) MP4 into a plain MP4 with Android's own
 * MediaExtractor/MediaMuxer. No decoding or re-encoding: samples are copied as-is.
 * Plain MP4 gives Android 9's media scanner a correct duration.
 */
object Mp4Remux {

    fun remuxAudio(input: File, output: File, isCancelled: () -> Boolean = { false }) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(input.path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in downloaded file")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val bufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), DEFAULT_SAMPLE_BUFFER_BYTES)
            } else {
                DEFAULT_SAMPLE_BUFFER_BYTES
            }

            val mux = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val outTrack = mux.addTrack(format)
            mux.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (isCancelled()) throw InterruptedException("Remux cancelled")
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, extractor.sampleTime, flags)
                mux.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            mux.stop()
            muxerStarted = false
        } catch (t: Throwable) {
            output.delete()
            throw t
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
