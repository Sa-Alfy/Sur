package io.github.saalfy.sur.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

private const val SUR_FOLDER = "Sur"
private const val MIME_M4A = "audio/mp4"

enum class SaveMethod { MEDIASTORE_PENDING, LEGACY_FILE_AND_SCAN }

data class SavedAudio(val songId: Long, val uri: Uri, val method: SaveMethod, val location: String)

/** Saves a finished audio file into the shared Music/Sur folder so the library picks it up. */
class AudioFileSaver(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun save(source: File, fileName: String, title: String, artist: String?, album: String?): SavedAudio =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(source, fileName, title, artist, album)
            } else {
                saveLegacy(source, fileName)
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveViaMediaStore(source: File, fileName: String, title: String, artist: String?, album: String?): SavedAudio {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_MUSIC}/$SUR_FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_M4A)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.TITLE, title)
            artist?.let { put(MediaStore.Audio.Media.ARTIST, it) }
            album?.let { put(MediaStore.Audio.Media.ALBUM, it) }
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            coroutineContext.ensureActive()
            val out = resolver.openOutputStream(uri) ?: throw IOException("Can't open $uri")
            out.use { stream -> source.inputStream().use { it.copyTo(stream) } }
            coroutineContext.ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            coroutineContext.ensureActive()
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
        return SavedAudio(ContentUris.parseId(uri), uri, SaveMethod.MEDIASTORE_PENDING, "$relativePath/$fileName")
    }

    private suspend fun saveLegacy(source: File, fileName: String): SavedAudio {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUR_FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create ${dir.path}")
        val file = uniqueFile(dir, fileName)
        var uri: Uri? = null
        try {
            coroutineContext.ensureActive()
            source.copyTo(file, overwrite = true)
            coroutineContext.ensureActive()
            uri = scan(file)
            coroutineContext.ensureActive()
        } catch (t: Throwable) {
            file.delete()
            if (uri != null) {
                resolver.delete(uri, null, null)
            }
            throw t
        }

        if (uri == null) {
            uri = queryAudioUri(file)
        }
        val songId = uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() } ?: -1L
        return SavedAudio(songId, uri ?: Uri.fromFile(file), SaveMethod.LEGACY_FILE_AND_SCAN, file.path)
    }

    private suspend fun scan(file: File): Uri? = withTimeoutOrNull(5000L) {
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(MIME_M4A)) { _, uri ->
                if (cont.isActive) cont.resume(uri)
            }
        }
    }

    private fun queryAudioUri(file: File): Uri? {
        return try {
            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(file.absolutePath),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun uniqueFile(dir: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "m4a")
        var candidate = File(dir, fileName)
        var n = 2
        while (candidate.exists()) candidate = File(dir, "$base ($n).$ext").also { n++ }
        return candidate
    }
}
