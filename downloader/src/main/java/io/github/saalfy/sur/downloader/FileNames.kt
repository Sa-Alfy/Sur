package io.github.saalfy.sur.downloader

private const val MAX_BASE_NAME_LENGTH = 100
private val ILLEGAL_CHARS = Regex("""[/\\:*?"<>|\p{Cntrl}]""")
private val WHITESPACE = Regex("""\s+""")
private val TOPIC_SUFFIX = Regex("""\s*-\s*Topic$""")

/** "Artist - Topic" (auto-generated YouTube Music channels) → "Artist". */
fun cleanChannelName(channel: String?): String? =
    channel?.trim()?.replace(TOPIC_SUFFIX, "")?.trim()?.takeIf { it.isNotEmpty() }

/** Safe file base name: no path separators or reserved characters, collapsed spaces, capped length. */
fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(ILLEGAL_CHARS, " ").replace(WHITESPACE, " ").trim().trim('.', ' ')
    val capped = if (cleaned.length > MAX_BASE_NAME_LENGTH) cleaned.take(MAX_BASE_NAME_LENGTH).trimEnd() else cleaned
    return capped.ifEmpty { "audio" }
}

/** "Artist - Title.m4a", or "Title.m4a" when the artist is unknown. */
fun audioFileName(title: String, artist: String?): String {
    val base = if (artist.isNullOrBlank()) title else "$artist - $title"
    return sanitizeFileName(base) + ".m4a"
}
