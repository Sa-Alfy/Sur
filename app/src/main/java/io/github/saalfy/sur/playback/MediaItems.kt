package io.github.saalfy.sur.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.saalfy.sur.data.Song

fun Song.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

internal fun MediaItem.withPlayableUri(): MediaItem {
    if (localConfiguration != null) return this
    val uri = requestMetadata.mediaUri ?: return this
    return buildUpon().setUri(uri).build()
}
