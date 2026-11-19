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

private val PRIMARY_VOLUME_REGEX = Regex("^(/storage/emulated/\\d+/|/sdcard/|/mnt/sdcard/)")
private val NAMED_VOLUME_REGEX = Regex("^/storage/([^/]+)/")

/** MediaStore's name for the primary shared storage volume (VOLUME_NAME on Android 10+). */
const val PRIMARY_VOLUME = "external_primary"

/**
 * R1-02: storage volume of an absolute path, named the way Android 10+ reports VOLUME_NAME
 * ("external_primary", or the lower-case SD card id such as "1234-abcd"). Null if unknown.
 */
fun volumeFromAbsolute(absPath: String): String? {
    if (PRIMARY_VOLUME_REGEX.containsMatchIn(absPath)) return PRIMARY_VOLUME
    val name = NAMED_VOLUME_REGEX.find(absPath)?.groupValues?.get(1) ?: return null
    return if (name == "emulated" || name == "self") null else name.lowercase()
}

/**
 * What identifies a song file across MediaStore id changes: its path key, the storage volume
 * it lives on and its duration. Volume and duration are null when unknown.
 */
data class SongIdentity(
    val key: String,
    val volume: String? = null,
    val durationMs: Long? = null,
)

interface SongKeyResolver {
    /** Maps MediaStore song ID to a stable SongInfoKey. */
    suspend fun resolveKeys(): Map<Long, String>

    /** Resolves the stable SongInfoKey for a single song ID, or null if not found. */
    suspend fun resolveKey(songId: Long): String? = resolveKeys()[songId]

    /** Maps MediaStore song ID to its [SongIdentity]. Default: key only (volume/duration unknown). */
    suspend fun resolveIdentities(): Map<Long, SongIdentity> =
        resolveKeys().mapValues { SongIdentity(it.value) }
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

    @Suppress("DEPRECATION")
    override suspend fun resolveIdentities(): Map<Long, SongIdentity> = withContext(ioDispatcher) {
        val atLeastQ = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = if (atLeastQ) {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.VOLUME_NAME,
                MediaStore.Audio.Media.DURATION,
            )
        } else {
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
            )
        }
        val result = mutableMapOf<Long, SongIdentity>()
        resolver.query(MediaStoreUris.audioCollection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val pathCol = cursor.getColumnIndex(if (atLeastQ) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
            val volCol = if (atLeastQ) cursor.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME) else -1
            while (cursor.moveToNext()) {
                val raw = if (pathCol >= 0) cursor.getString(pathCol) else null
                val relPath = if (atLeastQ) raw else raw?.let { relativeFromAbsolute(it) }
                val volume = if (atLeastQ) {
                    if (volCol >= 0) cursor.getString(volCol)?.lowercase() else null
                } else {
                    raw?.let { volumeFromAbsolute(it) }
                }
                val duration = if (durCol >= 0 && !cursor.isNull(durCol)) cursor.getLong(durCol) else null
                result[cursor.getLong(idCol)] = SongIdentity(
                    key = SongInfoKey.buildKey(relPath, cursor.getString(nameCol)),
                    volume = volume,
                    durationMs = duration?.takeIf { it > 0 },
                )
            }
        }
        result
    }
}
