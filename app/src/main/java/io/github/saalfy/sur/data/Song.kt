package io.github.saalfy.sur.data

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: Long,
    val durationMs: Long,
) {
    val uri: Uri get() = MediaStoreUris.song(id)
}
