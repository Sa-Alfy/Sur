package io.github.saalfy.sur.data.search

import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.playlist.PlaylistSummary

/** Delay after the last keystroke before filtering. */
const val SEARCH_DEBOUNCE_MS = 200L

/** Case-insensitive literal substring match against any field. A blank query matches everything. */
fun matchesQuery(query: String, vararg fields: String?): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return fields.any { it?.contains(q, ignoreCase = true) == true }
}

fun filterSongs(songs: List<Song>, query: String): List<Song> =
    if (query.isBlank()) songs else songs.filter { matchesQuery(query, it.title, it.artist, it.album) }

fun filterPlaylists(playlists: List<PlaylistSummary>, query: String): List<PlaylistSummary> =
    if (query.isBlank()) playlists else playlists.filter { matchesQuery(query, it.playlist.name) }
