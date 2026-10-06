package io.github.saalfy.sur.data

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

object MediaStoreUris {
    val audioCollection: Uri by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private val albumArtBase: Uri by lazy { Uri.parse("content://media/external/audio/albumart") }

    fun song(id: Long): Uri = ContentUris.withAppendedId(audioCollection, id)

    /** Legacy album art URI; the only art source on API 26-28. */
    fun albumArt(albumId: Long): Uri = ContentUris.withAppendedId(albumArtBase, albumId)
}
