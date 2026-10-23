package io.github.saalfy.sur.data.playlist.m3u

/**
 * Tolerant M3U parser.
 * Pure Kotlin – no Android types.
 *
 * Accepts:
 *  - UTF-8 with or without BOM
 *  - \n or \r\n line endings
 *  - missing #EXTM3U header
 *  - missing #PLAYLIST (name falls back to the supplied [fallbackName])
 *  - blank lines and unknown # comments
 *
 * Never throws; skips malformed lines and counts them in [ParseResult.badLineCount].
 */
object M3uParser {

    private val BOM = "\uFEFF"

    fun parse(text: String, fallbackName: String): ParseResult {
        var content = text
        if (content.startsWith(BOM)) content = content.substring(1)

        val lines = content.split('\n').map { it.trimEnd('\r') }

        var playlistName: String? = null
        val entries = mutableListOf<M3uEntry>()
        var pendingExtInf: ExtInf? = null
        var badLines = 0

        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isBlank() -> Unit
                line.startsWith("#EXTM3U") -> Unit
                line.startsWith("#PLAYLIST:") -> {
                    val name = line.removePrefix("#PLAYLIST:").trim()
                    if (name.isNotEmpty()) playlistName = name
                }
                line.startsWith("#EXTINF:") -> {
                    pendingExtInf = parseExtInf(line) ?: run { badLines++; null }
                }
                line.startsWith('#') -> Unit // unknown comment, ignore
                else -> {
                    // Path line
                    val path = line.replace('\\', '/')
                    val extinf = pendingExtInf
                    entries += M3uEntry(
                        title = extinf?.title,
                        artist = extinf?.artist,
                        durationSec = extinf?.durationSec,
                        path = path,
                    )
                    pendingExtInf = null
                }
            }
        }

        return ParseResult(
            playlistName = playlistName ?: fallbackName,
            entries = entries,
            badLineCount = badLines,
        )
    }

    /**
     * Parses:  #EXTINF:<sec>,<artist> - <title>
     * The comma is mandatory; the display string after it may be absent.
     * Artist/title split on the first " - " in the display string.
     */
    private fun parseExtInf(line: String): ExtInf? {
        val afterPrefix = line.removePrefix("#EXTINF:").trim()
        val commaIdx = afterPrefix.indexOf(',')
        if (commaIdx < 0) return null

        val secStr = afterPrefix.substring(0, commaIdx).trim()
        val durationSec = secStr.toIntOrNull() // may be -1 or missing

        val display = if (commaIdx + 1 < afterPrefix.length) afterPrefix.substring(commaIdx + 1) else ""

        val dashIdx = display.indexOf(" - ")
        val (artist, title) = if (dashIdx >= 0) {
            display.substring(0, dashIdx).trim().nullIfBlank() to
                display.substring(dashIdx + 3).trim().nullIfBlank()
        } else {
            null to display.trim().nullIfBlank()
        }

        return ExtInf(durationSec, artist, title)
    }

    private fun String.nullIfBlank() = if (isBlank()) null else this

    private data class ExtInf(val durationSec: Int?, val artist: String?, val title: String?)

    data class ParseResult(
        val playlistName: String,
        val entries: List<M3uEntry>,
        val badLineCount: Int,
    )
}
