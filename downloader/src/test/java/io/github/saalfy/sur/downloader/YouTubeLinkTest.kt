package io.github.saalfy.sur.downloader

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeLinkTest {

    private val id = "dQw4w9WgXcQ"

    private fun video(text: String) = assertEquals(text, ParsedLink.Video(id), YouTubeLink.parse(text))

    private fun rejected(text: String, reason: LinkRejection) =
        assertEquals(text, ParsedLink.Rejected(reason), YouTubeLink.parse(text))

    @Test
    fun acceptsSingleVideoForms() {
        video("https://www.youtube.com/watch?v=$id")
        video("https://youtube.com/watch?v=$id")
        video("http://m.youtube.com/watch?v=$id")
        video("https://music.youtube.com/watch?v=$id&feature=share")
        video("https://youtu.be/$id")
        video("https://youtu.be/$id?si=AbCdEf123")
        video("https://www.youtube.com/shorts/$id")
        video("https://www.youtube.com/embed/$id")
        video("https://WWW.YOUTUBE.COM/watch?v=$id")
    }

    @Test
    fun stripsPlaylistAndTrackingParams() {
        val parsed = YouTubeLink.parse("https://www.youtube.com/watch?v=$id&list=PL123&index=4&t=42s&pp=xyz")
        assertEquals(ParsedLink.Video(id), parsed)
        assertEquals("https://www.youtube.com/watch?v=$id", (parsed as ParsedLink.Video).canonicalUrl)
    }

    @Test
    fun extractsLinkFromSharedText() {
        video("Listen to this: https://youtu.be/$id?si=x great song")
        video("  https://www.youtube.com/watch?v=$id.  ")
    }

    @Test
    fun rejectsPlaylistsAndChannels() {
        rejected("https://www.youtube.com/playlist?list=PL123", LinkRejection.PLAYLIST)
        rejected("https://music.youtube.com/watch?list=RDAMVM123", LinkRejection.PLAYLIST)
        rejected("https://www.youtube.com/@SomeChannel", LinkRejection.CHANNEL)
        rejected("https://www.youtube.com/channel/UC123", LinkRejection.CHANNEL)
        rejected("https://www.youtube.com/c/Name", LinkRejection.CHANNEL)
    }

    @Test
    fun rejectsOtherSitesAndJunk() {
        rejected("https://vimeo.com/12345", LinkRejection.UNSUPPORTED_SITE)
        rejected("https://notyoutube.com/watch?v=$id", LinkRejection.UNSUPPORTED_SITE)
        rejected("hello there", LinkRejection.NOT_A_LINK)
        rejected("", LinkRejection.NOT_A_LINK)
        rejected("https://www.youtube.com/watch?v=short", LinkRejection.UNSUPPORTED_YOUTUBE_PAGE)
        rejected("https://www.youtube.com/feed/trending", LinkRejection.UNSUPPORTED_YOUTUBE_PAGE)
        rejected("https://youtu.be/", LinkRejection.UNSUPPORTED_YOUTUBE_PAGE)
    }
}
