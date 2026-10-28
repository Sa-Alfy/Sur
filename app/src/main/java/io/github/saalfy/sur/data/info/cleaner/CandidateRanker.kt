package io.github.saalfy.sur.data.info.cleaner

import io.github.saalfy.sur.data.info.lookup.Candidate
import kotlin.math.abs

object CandidateRanker {

    /**
     * Labels duration closeness.
     * "Duration matches" if within 5 seconds (<= 5s), otherwise "Duration differs by m:ss".
     * Returns null if either duration is null.
     */
    fun durationNote(candidateDurationMs: Long?, targetDurationMs: Long?): String? {
        if (candidateDurationMs == null || targetDurationMs == null) return null
        val diffSec = abs(candidateDurationMs - targetDurationMs) / 1000
        return if (diffSec <= 5) {
            "Duration matches"
        } else {
            val minutes = diffSec / 60
            val seconds = diffSec % 60
            val secStr = if (seconds < 10) "0$seconds" else "$seconds"
            "Duration differs by $minutes:$secStr"
        }
    }

    private const val ARTIST_MATCH_THRESHOLD = 0.8

    /**
     * Orders candidates whose artist is close to [wantedArtist] first, then by MusicBrainz
     * score descending, tie-breaking by duration closeness.
     */
    fun rank(
        candidates: List<Candidate>,
        targetDurationMs: Long?,
        wantedArtist: String? = null,
    ): List<Candidate> {
        val artistMatch = candidates.associateWith {
            artistSimilarity(it.artist, wantedArtist) >= ARTIST_MATCH_THRESHOLD
        }
        return candidates.sortedWith { a, b ->
            val artistCmp = artistMatch.getValue(b).compareTo(artistMatch.getValue(a))
            if (artistCmp != 0) return@sortedWith artistCmp

            val scoreCmp = b.score.compareTo(a.score)
            if (scoreCmp != 0) return@sortedWith scoreCmp

            if (targetDurationMs != null) {
                val aDiff = if (a.durationMs != null) abs(a.durationMs - targetDurationMs) else Long.MAX_VALUE
                val bDiff = if (b.durationMs != null) abs(b.durationMs - targetDurationMs) else Long.MAX_VALUE
                aDiff.compareTo(bDiff)
            } else {
                0
            }
        }
    }
}
