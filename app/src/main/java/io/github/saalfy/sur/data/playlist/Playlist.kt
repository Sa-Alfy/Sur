package io.github.saalfy.sur.data.playlist

import io.github.saalfy.sur.data.Song

data class Playlist(val id: Long, val name: String, val createdAt: Long)

/** A playlist with the number of its songs that currently exist on the device. */
data class PlaylistSummary(val playlist: Playlist, val songCount: Int)

/** A song in a playlist. [entryId] identifies this occurrence for remove/reorder. */
data class PlaylistSong(val entryId: Long, val song: Song)
