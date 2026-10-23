package io.github.saalfy.sur.data.playlist.m3u

/**
 * Writes an M3U playlist as UTF-8 text (no BOM).
 * Pure Kotlin – no Android types.
 */
object M3uWriter {

    /**
     * @param playlistName   playlist title written in the #PLAYLIST header
     * @param entries        songs in playlist order
     * @return UTF-8 text and the count of entries that had no usable path
     */
    fun write(playlistName: String, entries: List<M3uEntry>): Result {
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#PLAYLIST:").append(playlistName).append('\n')

        var weakCount = 0
        for (entry in entries) {
            val durationSec = entry.durationSec ?: -1
            val artistTitle = buildArtistTitle(entry.artist, entry.title)
            sb.append("#EXTINF:").append(durationSec).append(',').append(artistTitle).append('\n')
            sb.append(entry.path).append('\n')
            if (entry.path == (entry.title ?: "") || isWeakPath(entry.path)) weakCount++
        }

        return Result(sb.toString(), weakCount)
    }

    private fun buildArtistTitle(artist: String?, title: String?): String {
        return when {
            !artist.isNullOrBlank() && !title.isNullOrBlank() -> "$artist - $title"
            !title.isNullOrBlank() -> title
            !artist.isNullOrBlank() -> artist
            else -> ""
        }
    }

    /** A path is "weak" if it has no directory separator – it's just a file name. */
    private fun isWeakPath(path: String) = '/' !in path

    data class Result(val text: String, val weakCount: Int)
}
