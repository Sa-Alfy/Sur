package io.github.saalfy.sur.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn

/** Tracks shorter than this are treated as clips or sound effects, not music. */
const val MIN_SONG_DURATION_MS = 30_000L

/** Quiet period before rescanning after MediaStore reports changes (scans fire many events). */
private const val LIBRARY_CHANGE_DEBOUNCE_MS = 1_000L

class MediaStoreSongRepository(
    context: Context,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SongRepository {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val manualRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val collection: Uri = MediaStoreUris.audioCollection

    /** Shared so the Songs tab, playlist counts and playlist details run one query and one observer. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override val songs: Flow<List<Song>> =
        libraryQueries(
            triggers = merge(flowOf(Unit), manualRefresh, libraryChanges().debounce(LIBRARY_CHANGE_DEBOUNCE_MS)),
            query = ::querySongs,
        )
            .flowOn(ioDispatcher)
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    override suspend fun refresh() {
        manualRefresh.emit(Unit)
    }

    private fun libraryChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun querySongs(): List<Song> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val selectionArgs = arrayOf(MIN_SONG_DURATION_MS.toString())

        val songs = mutableListOf<Song>()
        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val ringtoneCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_RINGTONE)
            val notificationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_NOTIFICATION)
            val alarmCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_ALARM)

            while (cursor.moveToNext()) {
                // Filtered here rather than in SQL so NULL flags don't hide real songs.
                if (cursor.getInt(ringtoneCol) == 1 ||
                    cursor.getInt(notificationCol) == 1 ||
                    cursor.getInt(alarmCol) == 1
                ) continue

                val id = cursor.getLong(idCol)
                songs += Song(
                    id = id,
                    title = cursor.getString(titleCol).known()
                        ?: cursor.getString(nameCol).known()
                        ?: id.toString(),
                    artist = cursor.getString(artistCol).known(),
                    album = cursor.getString(albumCol).known(),
                    albumId = cursor.getLong(albumIdCol),
                    durationMs = cursor.getLong(durationCol),
                )
            }
        }
        return songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    private fun String?.known(): String? =
        this?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
}

/**
 * Runs [query] for every trigger, never letting one failed query end the stream.
 *
 * Before, a MediaStore failure other than SecurityException (e.g. CursorWindowAllocationException
 * under memory pressure, SQLiteException during a media scan) was rethrown inside the app-wide
 * scope and crashed the app, and a SecurityException completed the shared flow so later
 * refreshes did nothing.
 * Now: SecurityException → empty list (no access); any other error → keep the last good list
 * (nothing emitted) and wait for the next trigger. Cancellation still propagates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun libraryQueries(
    triggers: Flow<Unit>,
    query: () -> List<Song>,
    onError: (Throwable) -> Unit = { e -> runCatching { Log.w("SurLibrary", "Song query failed", e) } },
): Flow<List<Song>> = triggers.transformLatest {
    val result = try {
        query()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        onError(e)
        emptyList()
    } catch (e: Exception) {
        onError(e)
        null
    }
    if (result != null) emit(result)
}
