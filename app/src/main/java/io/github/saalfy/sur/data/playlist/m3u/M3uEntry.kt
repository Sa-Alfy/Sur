package io.github.saalfy.sur.data.playlist.m3u

/**
 * One track as written/read by the M3U format.
 * Pure data, no Android types. Used by both writer and parser.
 */
data class M3uEntry(
    val title: String?,
    val artist: String?,
    val durationSec: Int?,
    /** Forward-slash path as stored in the file. */
    val path: String,
)
