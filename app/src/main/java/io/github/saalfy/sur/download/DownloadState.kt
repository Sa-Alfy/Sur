package io.github.saalfy.sur.download

import io.github.saalfy.sur.downloader.DownloadError

sealed interface DownloadState {
    data object Idle : DownloadState
    data object Resolving : DownloadState
    data class Downloading(val progress: Float, val title: String? = null, val artist: String? = null) : DownloadState {
        constructor(percent: Int) : this(percent / 100f)
    }
    data object Saving : DownloadState
    data class Done(val songId: Long, val title: String) : DownloadState
    /** [technical] is the underlying exception (class + message), shown small on the error card. */
    data class Error(val error: DownloadError, val technical: String? = null) : DownloadState
}
