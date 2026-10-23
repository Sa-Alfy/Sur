package io.github.saalfy.sur.data.playlist.m3u

/**
 * A song as seen by the matcher: only the fields needed for matching.
 * No Android types so the matcher can be unit-tested on the JVM.
 */
data class MatchableSong(
    val id: Long,
    val title: String,
    val artist: String?,
    val durationSec: Int,
    /** Relative path including file name, forward slashes, e.g. "Music/Sur/Artist - Title.m4a" */
    val relativePath: String?,
    /** Just the file name, e.g. "Artist - Title.m4a" */
    val displayName: String?,
)
