package io.github.saalfy.sur.downloader.newpipe

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

            VideoInfo(
                videoId = videoId,
                title = extractor.name.trim().ifEmpty { videoId },
                artist = cleanChannelName(extractor.uploaderName),
                durationSeconds = duration,
                streamUrl = stream.content,
                itag = stream.itag,
                bitrateKbps = stream.averageBitrate,
                contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 },
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
                        // 200 means the server ignored Range and sent the whole file.
                        if (response.code == 200 || readThisChunk == 0L) total = position
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw mapError(t)
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
            is IOException -> DownloadException(DownloadError.NoInternet, t)
            else -> DownloadException(DownloadError.ExtractionFailed(t.message), t)
        }
    }
}
