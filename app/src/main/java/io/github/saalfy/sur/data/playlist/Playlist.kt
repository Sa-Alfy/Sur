package io.github.saalfy.sur.data.playlist

import io.github.saalfy.sur.data.Song

data class Playlist(val id: Long, val name: String, val createdAt: Long)

/**
 * A playlist with the number of its songs that currently exist on the device, and the first
 * (up to [MAX_COVER_SONGS]) of those songs for its cover mosaic.
 */
data class PlaylistSummary(val playlist: Playlist, val songCount: Int, val coverSongs: List<Song> = emptyList())

const val MAX_COVER_SONGS = 4

/** A song in a playlist. [entryId] identifies this occurrence for remove/reorder. */
data class PlaylistSong(val entryId: Long, val song: Song)
