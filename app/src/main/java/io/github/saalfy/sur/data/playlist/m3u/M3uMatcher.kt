package io.github.saalfy.sur.data.playlist.m3u

/**
 * Matches M3U entries to songs in the current library.
 *
 * Priority order (stops at the first step that gives an unambiguous match):
 *  1. Exact relative path + filename  (entry path == song relativePath)
 *  2. Same filename only              (entry filename == song displayName)
 *  3. Normalized title+artist with duration within 2 seconds
 *
 * Ambiguous = more than one candidate at the same step → not matched.
 * Pure Kotlin, no Android types.
 */
object M3uMatcher {

    private const val DURATION_TOLERANCE_SEC = 2

    data class MatchResult(
        /** Song IDs in entry order, null where no unambiguous match was found. */
        val matched: List<Long?>,
        val notMatchedCount: Int,
    )

    fun match(entries: List<M3uEntry>, library: List<MatchableSong>): MatchResult {
        val byRelativePath: Map<String, List<MatchableSong>> =
            library.filter { !it.relativePath.isNullOrBlank() }
                .groupBy { it.relativePath!! }

        val byDisplayName: Map<String, List<MatchableSong>> =
            library.filter { !it.displayName.isNullOrBlank() }
                .groupBy { it.displayName!! }

        val matched = entries.map { entry ->
            matchEntry(entry, byRelativePath, byDisplayName, library)
        }

        val notMatchedCount = matched.count { it == null }
        return MatchResult(matched, notMatchedCount)
    }

    private fun matchEntry(
        entry: M3uEntry,
        byRelativePath: Map<String, List<MatchableSong>>,
        byDisplayName: Map<String, List<MatchableSong>>,
        library: List<MatchableSong>,
    ): Long? {
        // Step 1: exact relative path
        val byPath = byRelativePath[entry.path]
        if (byPath != null) {
            return if (byPath.size == 1) byPath[0].id else null // ambiguous → skip
        }

        // Step 2: filename only (last path segment of the entry)
        val entryFileName = entry.path.substringAfterLast('/')
        if (entryFileName.isNotEmpty()) {
            val byName = byDisplayName[entryFileName]
            if (byName != null) {
                return if (byName.size == 1) byName[0].id else null
            }
        }

        // Step 3: normalized title+artist + duration within 2 s
        val normTitle = normalize(entry.title ?: return null)
        val normArtist = normalize(entry.artist)
        val entryDur = entry.durationSec ?: -1

        val candidates = library.filter { song ->
            normalize(song.title) == normTitle &&
                normalize(song.artist) == normArtist &&
                (entryDur < 0 || Math.abs(song.durationSec - entryDur) <= DURATION_TOLERANCE_SEC)
        }

        return if (candidates.size == 1) candidates[0].id else null
    }

    /** Lowercase, collapse whitespace, strip common punctuation. */
    private fun normalize(s: String?): String {
        if (s == null) return ""
        return s.lowercase()
            .replace(Regex("[\\p{Punct}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
