package io.github.saalfy.sur.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import io.github.saalfy.sur.data.MediaStoreUris
import io.github.saalfy.sur.data.Song
import java.io.File

/**
 * Converts a [Song] to a Media3 [MediaItem].
 * Note: The currently playing MediaItem is NOT replaced when an override changes.
 * It takes effect the next time the song is queued or played.
 */
fun Song.toMediaItem(cover: File? = coverFile): MediaItem {
    val artUri = (cover ?: coverFile)?.let { Uri.fromFile(it) } ?: MediaStoreUris.albumArt(albumId)
    return MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artUri)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()
}

/** MediaStore song id of an item built by [toMediaItem]. */
val MediaItem.songId: Long? get() = mediaId.toLongOrNull()

internal fun MediaItem.withPlayableUri(): MediaItem {
    if (localConfiguration != null) return this
    val uri = requestMetadata.mediaUri ?: return this
    return buildUpon().setUri(uri).build()
}
