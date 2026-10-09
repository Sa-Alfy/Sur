package io.github.saalfy.sur.download

import io.github.saalfy.sur.downloader.DownloadError

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Resolving : DownloadState
    data class Downloading(val progress: Float) : DownloadState {
        constructor(percent: Int) : this(percent / 100f)
    }
    data object Saving : DownloadState
    data class Done(val songId: Long, val title: String) : DownloadState
    data class Error(val error: DownloadError) : DownloadState
}

fun DownloadError.userMessage(): String = when (this) {
    is DownloadError.NoInternet -> "No internet connection."
    is DownloadError.Unavailable -> "Video is unavailable or private."
    is DownloadError.AgeRestricted -> "Video is age-restricted."
    is DownloadError.RegionBlocked -> "Video is not available in your region."
    is DownloadError.NotDownloadable -> "Audio stream is not downloadable."
    is DownloadError.LiveStream -> "Cannot download live streams."
    is DownloadError.TooLong -> "Video is too long."
    is DownloadError.UnsupportedLink -> "Link is not supported."
    is DownloadError.NotEnoughStorage -> {
        if (neededBytes <= 0) {
            "Not enough storage"
        } else {
            val mb = String.format(java.util.Locale.US, "%.1f MB", neededBytes.toDouble() / (1024 * 1024))
            "Not enough storage ($mb needed)"
        }
    }
    is DownloadError.ExtractionFailed -> detail ?: "Download failed."
    is DownloadError.Failed -> "Download failed."
}
