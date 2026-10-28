package io.github.saalfy.sur.downloader.newpipe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.saalfy.sur.downloader.AudioDownloader
import io.github.saalfy.sur.downloader.DownloadError
import io.github.saalfy.sur.downloader.DownloadException
import io.github.saalfy.sur.downloader.MAX_DURATION_SECONDS
import io.github.saalfy.sur.downloader.ParsedLink
import io.github.saalfy.sur.downloader.VideoInfo
import io.github.saalfy.sur.downloader.YouTubeLink
import io.github.saalfy.sur.downloader.cleanChannelName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** YouTube serves full-speed audio only in ranges; bigger single requests get throttled. */
private const val CHUNK_BYTES = 10L * 1024 * 1024
private const val COPY_BUFFER_BYTES = 64 * 1024

class NewPipeAudioDownloader(
    private val client: OkHttpClient = defaultClient(),
) : AudioDownloader {

    override suspend fun resolve(link: String): VideoInfo = withContext(Dispatchers.IO) {
        val parsed = YouTubeLink.parse(link)
        if (parsed is ParsedLink.Rejected) throw DownloadException(DownloadError.UnsupportedLink(parsed.reason))
        val videoId = (parsed as ParsedLink.Video).videoId
        ensureInitialized(client)

        val extractor = try {
            ServiceList.YouTube.getStreamExtractor(parsed.canonicalUrl).also { it.fetchPage() }
        } catch (t: Throwable) {
            throw mapError(t)
        }

        try {
            when (extractor.streamType) {
                StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM ->
                    throw DownloadException(DownloadError.LiveStream)
                else -> Unit
            }
            val duration = extractor.length
            if (duration > MAX_DURATION_SECONDS) throw DownloadException(DownloadError.TooLong(duration))

            val stream = pickAudioStream(extractor.audioStreams)
                ?: throw DownloadException(DownloadError.NotDownloadable)

            // Pick largest thumbnail: known sizes sorted by height desc, UNKNOWN sort last.
            val thumbnailUrl = runCatching { extractor.thumbnails }
                .getOrNull()
                ?.maxWithOrNull(compareBy(
                    { it.estimatedResolutionLevel != Image.ResolutionLevel.UNKNOWN },
                    { if (it.height != Image.HEIGHT_UNKNOWN) it.height else 0 },
                ))
                ?.url

            VideoInfo(
                videoId = videoId,
                title = extractor.name.trim().ifEmpty { videoId },
                artist = cleanChannelName(extractor.uploaderName),
                durationSeconds = duration,
                streamUrl = stream.content,
                itag = stream.itag,
                bitrateKbps = stream.averageBitrate,
                contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 },
                thumbnailUrl = thumbnailUrl,
            )
        } catch (t: Throwable) {
            throw mapError(t)
        }
    }

    override suspend fun download(
        info: VideoInfo,
        target: File,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        try {
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var position = 0L
                var total = info.contentLength
                while (total == null || position < total) {
                    val request = okhttp3.Request.Builder()
                        .url(info.streamUrl)
                        .header("User-Agent", USER_AGENT)
                        .header("Range", "bytes=$position-${position + CHUNK_BYTES - 1}")
                        .build()
                    client.newCall(request).execute().use { response ->
                        when (response.code) {
                            200, 206 -> Unit
                            403, 410 -> throw DownloadException(
                                DownloadError.ExtractionFailed("Stream refused (HTTP ${response.code})"),
                            )
                            in 500..599 -> throw DownloadException(DownloadError.Failed)
                            else -> throw IOException("HTTP ${response.code}")
                        }
                        if (total == null) total = contentRangeTotal(response.header("Content-Range"))
                        var readThisChunk = 0L
                        response.body.byteStream().use { input ->
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                position += n
                                readThisChunk += n
                                onProgress(position, total)
                            }
                        }
                        if (total != null && position < total && readThisChunk == 0L) {
                            throw IOException("Stream returned 0 bytes while $position < $total")
                        }
                        // 200 means the server ignored Range and sent the whole file.
                        if (response.code == 200 || readThisChunk == 0L) total = position
                    }
                }
                checkDownloadComplete(info.contentLength, position)
            }
        } catch (t: Throwable) {
            target.delete()
            throw mapError(t)
        }
    }

    override suspend fun fetchThumbnail(info: VideoInfo): ByteArray? {
        val url = info.thumbnailUrl ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val thumbnailClient = client.newBuilder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).build()
                val bytes = thumbnailClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG_THUMB, "Thumbnail HTTP ${response.code} for $url")
                        return@withContext null
                    }
                    val body = response.body
                    val contentLength = body.contentLength()
                    if (contentLength > MAX_THUMB_BYTES) {
                        Log.w(TAG_THUMB, "Thumbnail too large: $contentLength bytes")
                        return@withContext null
                    }
                    body.bytes()
                }
                if (bytes.size > MAX_THUMB_BYTES) {
                    Log.w(TAG_THUMB, "Thumbnail body too large: ${bytes.size} bytes")
                    return@withContext null
                }
                if (!isJpegOrPng(bytes)) {
                    Log.w(TAG_THUMB, "Thumbnail is not JPEG or PNG")
                    return@withContext null
                }
                cropAndScaleToJpeg(bytes)
            } catch (e: Throwable) {
                Log.w(TAG_THUMB, "fetchThumbnail failed", e)
                null
            }
        }
    }

    private fun pickAudioStream(streams: List<AudioStream>): AudioStream? =
        streams
            .filter { it.format == MediaFormat.M4A && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            // Prefer the original language track over dubbed/descriptive ones.
            .sortedWith(
                compareByDescending<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                    .thenByDescending { it.averageBitrate },
            )
            .firstOrNull()

    private fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()

    companion object {
        private const val TAG_THUMB = "SurThumbnail"
        private const val MAX_THUMB_BYTES = 2 * 1024 * 1024 // 2 MB
        private const val MAX_SIDE_PX = 600

        @Volatile private var initialized = false

        private fun ensureInitialized(client: OkHttpClient) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    NewPipe.init(OkHttpNewPipeClient(client), Localization("en", "US"))
                    initialized = true
                }
            }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Returns true if bytes start with JPEG (FF D8 FF) or PNG (89 50 4E 47) magic bytes. */
        internal fun isJpegOrPng(bytes: ByteArray): Boolean {
            if (bytes.size < 4) return false
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val b3 = bytes[3].toInt() and 0xFF
            return (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) || // JPEG
                (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) // PNG
        }

        /**
         * Decodes [bytes] to a Bitmap, center-crops to a square, scales so the longest side is at
         * most [MAX_SIDE_PX], and re-encodes as JPEG quality 85.
         * Returns null on any failure.
         */
        internal fun cropAndScaleToJpeg(bytes: ByteArray): ByteArray? {
            return try {
                val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val w = src.width
                val h = src.height
                val side = minOf(w, h)
                val left = (w - side) / 2
                val top = (h - side) / 2
                val cropped = Bitmap.createBitmap(src, left, top, side, side)
                if (cropped !== src) src.recycle()

                val scale = if (side > MAX_SIDE_PX) MAX_SIDE_PX.toFloat() / side else 1f
                val scaled = if (scale < 1f) {
                    val s = Bitmap.createScaledBitmap(cropped, (side * scale).toInt(), (side * scale).toInt(), true)
                    if (s !== cropped) cropped.recycle()
                    s
                } else {
                    cropped
                }

                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                scaled.recycle()
                out.toByteArray()
            } catch (e: Throwable) {
                Log.w(TAG_THUMB, "cropAndScaleToJpeg failed", e)
                null
            }
        }

        internal fun checkDownloadComplete(expectedLength: Long?, position: Long) {
            if (expectedLength != null && position != expectedLength) {
                throw DownloadException(DownloadError.Failed)
            }
        }

        /** Maps library and network exceptions to user-facing [DownloadError]s. */
        internal fun mapError(t: Throwable): Throwable = when (t) {
            is DownloadException, is CancellationException -> t
            is AgeRestrictedContentException -> DownloadException(DownloadError.AgeRestricted, t)
            is GeographicRestrictionException -> DownloadException(DownloadError.RegionBlocked, t)
            is PrivateContentException -> DownloadException(DownloadError.Unavailable, t)
            is ContentNotAvailableException -> DownloadException(DownloadError.Unavailable, t)
            is ReCaptchaException -> DownloadException(DownloadError.ExtractionFailed("YouTube asked for verification"), t)
            is ExtractionException -> DownloadException(DownloadError.ExtractionFailed(t.message), t)
            is UnknownHostException, is ConnectException, is NoRouteToHostException,
            is SocketTimeoutException -> DownloadException(DownloadError.NoInternet, t)
            is InterruptedIOException -> CancellationException("Interrupted").apply { initCause(t) }
            is IOException -> {
                val message = t.message
                if (message != null && (message.contains("ENOSPC") || message.contains("No space left"))) {
                    DownloadException(DownloadError.NotEnoughStorage(0), t)
                } else {
                    DownloadException(DownloadError.Failed, t)
                }
            }
            else -> DownloadException(DownloadError.ExtractionFailed(t.message), t)
        }
    }
}
