package io.github.saalfy.sur.downloader

/** Longest video we accept. Songs and long mixes fit; hour-long streams don't. */
const val MAX_DURATION_SECONDS = 30 * 60L

sealed interface DownloadError {
    data object NoInternet : DownloadError
    data object Unavailable : DownloadError
    data object AgeRestricted : DownloadError
    data object RegionBlocked : DownloadError

    /** Paid, Premium-only, or no unencrypted m4a audio stream. */
    data object NotDownloadable : DownloadError
    data object LiveStream : DownloadError
    data class TooLong(val durationSeconds: Long) : DownloadError
    data class UnsupportedLink(val reason: LinkRejection) : DownloadError
    data class NotEnoughStorage(val neededBytes: Long) : DownloadError

    /** YouTube changed something or blocked the request; usually fixed by updating Sur. */
    data class ExtractionFailed(val detail: String?) : DownloadError

    /** Generic fallback: "Download failed, try again". */
    data object Failed : DownloadError

    /** A download is already in progress. */
    data object Busy : DownloadError
}

class DownloadException(val error: DownloadError, cause: Throwable? = null) :
    Exception(error.toString(), cause)
