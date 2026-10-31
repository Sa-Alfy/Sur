package io.github.saalfy.sur.data

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import io.github.saalfy.sur.ui.hasPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DeleteConfirmationMode {
    VERIFY,
    RETRY,
}

sealed interface DeleteAttempt {
    data object Deleted : DeleteAttempt
    data object PermissionRequired : DeleteAttempt
    data object Failed : DeleteAttempt
    data class Confirmation(
        val intentSender: IntentSender,
        val mode: DeleteConfirmationMode,
    ) : DeleteAttempt
}

interface SongDeleter {
    suspend fun begin(songId: Long): DeleteAttempt
    suspend fun finish(songId: Long, mode: DeleteConfirmationMode): Boolean
}

class MediaStoreSongDeleter(
    context: Context,
) : SongDeleter {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver

    override suspend fun begin(songId: Long): DeleteAttempt = withContext(Dispatchers.IO) {
        val uri = MediaStoreUris.song(songId)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                DeleteAttempt.Confirmation(
                    MediaStore.createDeleteRequest(resolver, listOf(uri)).intentSender,
                    DeleteConfirmationMode.VERIFY,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> deleteWithRecoverableAction(songId, uri)
            !appContext.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> DeleteAttempt.PermissionRequired
            else -> deleteAndVerify(uri)
        }
    }

    override suspend fun finish(songId: Long, mode: DeleteConfirmationMode): Boolean = withContext(Dispatchers.IO) {
        val uri = MediaStoreUris.song(songId)
        when (mode) {
            DeleteConfirmationMode.VERIFY -> isGone(uri)
            DeleteConfirmationMode.RETRY -> deleteAndVerify(uri) == DeleteAttempt.Deleted
        }
    }

    private fun deleteWithRecoverableAction(songId: Long, uri: android.net.Uri): DeleteAttempt {
        return try {
            deleteAndVerify(uri)
        } catch (exception: RecoverableSecurityException) {
            DeleteAttempt.Confirmation(exception.userAction.actionIntent.intentSender, DeleteConfirmationMode.RETRY)
        } catch (_: SecurityException) {
            DeleteAttempt.Failed
        }
    }

    private fun deleteAndVerify(uri: android.net.Uri): DeleteAttempt {
        return try {
            if (resolver.delete(uri, null, null) <= 0 || !isGone(uri)) DeleteAttempt.Failed else DeleteAttempt.Deleted
        } catch (_: SecurityException) {
            DeleteAttempt.Failed
        }
    }

    private fun isGone(uri: android.net.Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { cursor ->
            !cursor.moveToFirst()
        } ?: true
}
