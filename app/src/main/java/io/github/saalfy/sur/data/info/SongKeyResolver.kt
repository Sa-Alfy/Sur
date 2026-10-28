package io.github.saalfy.sur.data.info

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import io.github.saalfy.sur.data.MediaStoreUris
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val STORAGE_PREFIX_REGEX = Regex(
    "^(/storage/emulated/\\d+/|/storage/[^/]+/|/sdcard/|/mnt/sdcard/)"
)

/**
 * Strips known storage volume prefixes from an absolute path to produce a relative path.
 * Strips ^/storage/emulated/<digits>/ , ^/storage/<volume-id>/ , ^/sdcard/ , ^/mnt/sdcard/ ;
 * if none match, returns the input unchanged.
 */
fun relativeFromAbsolute(absPath: String): String =
    absPath.replaceFirst(STORAGE_PREFIX_REGEX, "")

interface SongKeyResolver {
    /** Maps MediaStore song ID to a stable SongInfoKey. */
    suspend fun resolveKeys(): Map<Long, String>

    /** Resolves the stable SongInfoKey for a single song ID, or null if not found. */
    suspend fun resolveKey(songId: Long): String? = resolveKeys()[songId]
}

class MediaStoreSongKeyResolver(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SongKeyResolver {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    @Suppress("DEPRECATION")
    override suspend fun resolveKey(songId: Long): String? = withContext(ioDispatcher) {
        val collection = MediaStoreUris.audioCollection
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
            )
        } else {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA,
            )
        }

        val selection = "${MediaStore.Audio.Media._ID} = ?"
        val selectionArgs = arrayOf(songId.toString())

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            }

            if (cursor.moveToFirst()) {
                val displayName = cursor.getString(nameCol)
                val relPath = if (pathCol >= 0) {
                    val raw = cursor.getString(pathCol)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        raw
                    } else {
                        raw?.let { relativeFromAbsolute(it) }
                    }
                } else {
                    null
                }
                SongInfoKey.buildKey(relPath, displayName)
            } else {
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun resolveKeys(): Map<Long, String> = withContext(ioDispatcher) {
        val collection = MediaStoreUris.audioCollection
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
            )
        } else {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA,
            )
        }

        val result = mutableMapOf<Long, String>()
        resolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol)
                val relPath = if (pathCol >= 0) {
                    val raw = cursor.getString(pathCol)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        raw
                    } else {
                        raw?.let { relativeFromAbsolute(it) }
                    }
                } else {
                    null
                }
                result[id] = SongInfoKey.buildKey(relPath, displayName)
            }
        }
        result
    }
}
