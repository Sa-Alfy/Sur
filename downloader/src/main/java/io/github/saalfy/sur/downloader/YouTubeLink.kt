package io.github.saalfy.sur.downloader

import java.net.URI
import java.net.URISyntaxException

enum class LinkRejection { NOT_A_LINK, UNSUPPORTED_SITE, PLAYLIST, CHANNEL, UNSUPPORTED_YOUTUBE_PAGE }

sealed interface ParsedLink {
    data class Video(val videoId: String) : ParsedLink {
        /** Clean link with every playlist/tracking parameter removed. */
        val canonicalUrl: String get() = "https://www.youtube.com/watch?v=$videoId"
    }

    data class Rejected(val reason: LinkRejection) : ParsedLink
}

/** Accepts only single-video links on youtube.com, m./www./music.youtube.com and youtu.be. */
object YouTubeLink {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val URL_IN_TEXT = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val SHORT_HOSTS = setOf("youtu.be", "www.youtu.be")
    private val CHANNEL_PREFIXES = listOf("/channel/", "/c/", "/user/", "/@")

    /** First http(s) URL in shared text such as "Check this out https://youtu.be/…". */
    fun extractUrl(text: String): String? =
        URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    fun parse(text: String): ParsedLink {
        val raw = extractUrl(text.trim()) ?: return ParsedLink.Rejected(LinkRejection.NOT_A_LINK)
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return ParsedLink.Rejected(LinkRejection.NOT_A_LINK)
        }
        val host = uri.host?.lowercase() ?: return ParsedLink.Rejected(LinkRejection.NOT_A_LINK)
        val path = uri.path.orEmpty()

        if (host in SHORT_HOSTS) {
            return videoOrReject(path.removePrefix("/").substringBefore('/'))
        }
        if (host !in YOUTUBE_HOSTS) return ParsedLink.Rejected(LinkRejection.UNSUPPORTED_SITE)

        val query = parseQuery(uri.rawQuery)
        return when {
            path == "/watch" || path == "/watch/" -> {
                val id = query["v"]
                if (id == null && query.containsKey("list")) {
                    ParsedLink.Rejected(LinkRejection.PLAYLIST)
                } else {
                    videoOrReject(id)
                }
            }
            path.startsWith("/shorts/") -> videoOrReject(path.removePrefix("/shorts/").substringBefore('/'))
            path.startsWith("/live/") -> videoOrReject(path.removePrefix("/live/").substringBefore('/'))
            path.startsWith("/embed/") -> videoOrReject(path.removePrefix("/embed/").substringBefore('/'))
            path.startsWith("/playlist") -> ParsedLink.Rejected(LinkRejection.PLAYLIST)
            CHANNEL_PREFIXES.any { path.startsWith(it) } -> ParsedLink.Rejected(LinkRejection.CHANNEL)
            else -> ParsedLink.Rejected(LinkRejection.UNSUPPORTED_YOUTUBE_PAGE)
        }
    }

    private fun videoOrReject(id: String?): ParsedLink =
        if (id != null && VIDEO_ID.matches(id)) {
            ParsedLink.Video(id)
        } else {
            ParsedLink.Rejected(LinkRejection.UNSUPPORTED_YOUTUBE_PAGE)
        }

    private fun parseQuery(rawQuery: String?): Map<String, String> =
        rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            key to java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
        }
}
