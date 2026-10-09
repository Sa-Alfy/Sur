package io.github.saalfy.sur.ui.download

import io.github.saalfy.sur.R
import io.github.saalfy.sur.downloader.DownloadError
import io.github.saalfy.sur.downloader.LinkRejection
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadErrorStringTest {

    @Test
    fun mapsAllDownloadErrorsToStringResources() {
        assertEquals(R.string.download_err_no_internet, DownloadError.NoInternet.stringRes())
        assertEquals(R.string.download_err_unavailable, DownloadError.Unavailable.stringRes())
        assertEquals(R.string.download_err_age_restricted, DownloadError.AgeRestricted.stringRes())
        assertEquals(R.string.download_err_region_blocked, DownloadError.RegionBlocked.stringRes())
        assertEquals(R.string.download_err_not_downloadable, DownloadError.NotDownloadable.stringRes())
        assertEquals(R.string.download_err_extraction_failed, DownloadError.ExtractionFailed("technical detail").stringRes())
        assertEquals(R.string.download_err_not_enough_storage, DownloadError.NotEnoughStorage(0).stringRes())
        assertEquals(R.string.download_err_not_enough_storage, DownloadError.NotEnoughStorage(-5).stringRes())
        assertEquals(R.string.download_err_not_enough_storage_size, DownloadError.NotEnoughStorage(1024).stringRes())
        assertEquals(R.string.download_err_live_stream, DownloadError.LiveStream.stringRes())
        assertEquals(R.string.download_err_too_long, DownloadError.TooLong(3600).stringRes())
        assertEquals(R.string.download_err_unsupported_playlist, DownloadError.UnsupportedLink(LinkRejection.PLAYLIST).stringRes())
        assertEquals(R.string.download_err_unsupported_channel, DownloadError.UnsupportedLink(LinkRejection.CHANNEL).stringRes())
        assertEquals(R.string.download_err_unsupported_link, DownloadError.UnsupportedLink(LinkRejection.NOT_A_LINK).stringRes())
        assertEquals(R.string.download_err_unsupported_link, DownloadError.UnsupportedLink(LinkRejection.UNSUPPORTED_SITE).stringRes())
        assertEquals(R.string.download_err_unsupported_link, DownloadError.UnsupportedLink(LinkRejection.UNSUPPORTED_YOUTUBE_PAGE).stringRes())
        assertEquals(R.string.download_err_failed, DownloadError.Failed.stringRes())
    }
}
