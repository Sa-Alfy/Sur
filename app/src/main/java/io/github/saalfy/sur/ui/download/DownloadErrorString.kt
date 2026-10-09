package io.github.saalfy.sur.ui.download

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.saalfy.sur.R
import io.github.saalfy.sur.downloader.DownloadError
import io.github.saalfy.sur.downloader.LinkRejection
import java.util.Locale

@StringRes
fun DownloadError.stringRes(): Int = when (this) {
    is DownloadError.NoInternet -> R.string.download_err_no_internet
    is DownloadError.Unavailable -> R.string.download_err_unavailable
    is DownloadError.AgeRestricted -> R.string.download_err_age_restricted
    is DownloadError.RegionBlocked -> R.string.download_err_region_blocked
    is DownloadError.NotDownloadable -> R.string.download_err_not_downloadable
    is DownloadError.ExtractionFailed -> R.string.download_err_extraction_failed
    is DownloadError.NotEnoughStorage -> {
        if (neededBytes <= 0) R.string.download_err_not_enough_storage
        else R.string.download_err_not_enough_storage_size
    }
    is DownloadError.LiveStream -> R.string.download_err_live_stream
    is DownloadError.TooLong -> R.string.download_err_too_long
    is DownloadError.UnsupportedLink -> when (reason) {
        LinkRejection.PLAYLIST -> R.string.download_err_unsupported_playlist
        LinkRejection.CHANNEL -> R.string.download_err_unsupported_channel
        LinkRejection.NOT_A_LINK,
        LinkRejection.UNSUPPORTED_SITE,
        LinkRejection.UNSUPPORTED_YOUTUBE_PAGE -> R.string.download_err_unsupported_link
    }
    is DownloadError.Failed -> R.string.download_err_failed
}

fun DownloadError.userMessage(context: Context): String = when (this) {
    is DownloadError.NotEnoughStorage -> {
        if (neededBytes <= 0) {
            context.getString(R.string.download_err_not_enough_storage)
        } else {
            val mb = String.format(Locale.US, "%.1f", neededBytes.toDouble() / (1024 * 1024))
            context.getString(R.string.download_err_not_enough_storage_size, mb)
        }
    }
    else -> context.getString(stringRes())
}

@Composable
fun DownloadError.userMessage(): String = when (this) {
    is DownloadError.NotEnoughStorage -> {
        if (neededBytes <= 0) {
            stringResource(R.string.download_err_not_enough_storage)
        } else {
            val mb = String.format(Locale.US, "%.1f", neededBytes.toDouble() / (1024 * 1024))
            stringResource(R.string.download_err_not_enough_storage_size, mb)
        }
    }
    else -> stringResource(stringRes())
}
