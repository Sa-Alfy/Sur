package io.github.saalfy.sur.downloader

import java.io.File

data class VideoInfo(
    val videoId: String,
    val title: String,
    /** Channel name with " - Topic" removed; null if unknown. */
    val artist: String?,
    val durationSeconds: Long,
    val streamUrl: String,
    val itag: Int,
    val bitrateKbps: Int,
    /** Size of the audio stream if YouTube reports it. */
    val contentLength: Long?,
    /** URL of the largest available thumbnail; null if none found. */
    val thumbnailUrl: String? = null,
)

/** The whole downloader feature behind one small interface, so it can be removed or swapped. */
interface AudioDownloader {
    /** Parses [link], fetches video details and picks the best m4a audio stream. Throws [DownloadException]. */
    suspend fun resolve(link: String): VideoInfo

    /** Downloads the audio stream to [target]. Deletes [target] on failure or cancellation. Throws [DownloadException]. */
    suspend fun download(info: VideoInfo, target: File, onProgress: (downloaded: Long, total: Long?) -> Unit)

    /**
     * Fetches the thumbnail from [info.thumbnailUrl], decodes it, center-crops to a square,
     * scales so the longest side is at most 600 px, and re-encodes as JPEG quality 85.
     * Returns null on any failure (network, OOM, bad format, etc.) — never throws.
     */
    suspend fun fetchThumbnail(info: VideoInfo): ByteArray?
}
