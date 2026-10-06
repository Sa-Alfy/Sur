package io.github.saalfy.sur.data

import android.net.Uri

data class Song(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)
