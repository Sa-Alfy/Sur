package io.github.saalfy.sur.data.playlist.m3u

import android.content.ContentResolver
import android.os.Build
import android.provider.MediaStore
import io.github.saalfy.sur.data.MIN_SONG_DURATION_MS
import io.github.saalfy.sur.data.MediaStoreUris

/**
 * Reads relative paths and display names from MediaStore for a given set of song IDs.
 * Returns a map from song ID to [MatchableSong].
 *
 * RELATIVE_PATH is only available on API 29+. On API 26-28 only the display name is populated;
 * path-based matching falls back to filename-only or title/artist/duration.
 */
object MediaStoreSongFiles {

    fun query(resolver: ContentResolver, songIds: Set<Long>): Map<Long, MatchableSong> {
        if (songIds.isEmpty()) return emptyMap()

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val placeholders = songIds.joinToString(",") { "?" }
        val selection = "${MediaStore.Audio.Media._ID} IN ($placeholders)"
        val selectionArgs = songIds.map { it.toString() }.toTypedArray()

        val result = mutableMapOf<Long, MatchableSong>()

        resolver.query(
            MediaStoreUris.audioCollection,
            projection,
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol) ?: continue
                val relDir = if (relPathCol >= 0) cursor.getString(relPathCol)?.trimEnd('/') else null
                val relativePath = if (relDir != null) "$relDir/$displayName" else null

                result[id] = MatchableSong(
                    id = id,
                    title = cursor.getString(titleCol) ?: displayName,
                    artist = cursor.getString(artistCol)?.takeUnless { it == MediaStore.UNKNOWN_STRING },
                    durationSec = (cursor.getLong(durationCol) / 1000).toInt(),
                    relativePath = relativePath?.replace('\\', '/'),
                    displayName = displayName,
                )
            }
        }

        return result
    }

    /** Query ALL songs (for import matching). */
    fun queryAll(resolver: ContentResolver): List<MatchableSong> {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= $MIN_SONG_DURATION_MS"
        val result = mutableListOf<MatchableSong>()

        resolver.query(
            MediaStoreUris.audioCollection,
            projection,
            selection,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            } else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol) ?: continue
                val relDir = if (relPathCol >= 0) cursor.getString(relPathCol)?.trimEnd('/') else null
                val relativePath = if (relDir != null) "$relDir/$displayName" else null

                result += MatchableSong(
                    id = id,
                    title = cursor.getString(titleCol) ?: displayName,
                    artist = cursor.getString(artistCol)?.takeUnless { it == MediaStore.UNKNOWN_STRING },
                    durationSec = (cursor.getLong(durationCol) / 1000).toInt(),
                    relativePath = relativePath?.replace('\\', '/'),
                    displayName = displayName,
                )
            }
        }

        return result
    }
}
