package io.github.saalfy.sur.data

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import io.github.saalfy.sur.ui.hasPermission
import kotlinx.coroutines.CancellationException
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
        val intentSender: IntentSender?,
        val mode: DeleteConfirmationMode,
    ) : DeleteAttempt
}

enum class DeletionVerification {
    CONFIRMED_ABSENT,
    CONFIRMED_PRESENT,
    UNKNOWN,
}

interface SongDeleter {
    suspend fun begin(songId: Long): DeleteAttempt
    suspend fun finish(songId: Long, mode: DeleteConfirmationMode): Boolean
}

internal fun verifyPresenceFromCursor(cursor: Cursor?): DeletionVerification {
    if (cursor == null) return DeletionVerification.UNKNOWN
    return cursor.use {
        if (it.moveToFirst()) {
            DeletionVerification.CONFIRMED_PRESENT
        } else {
            DeletionVerification.CONFIRMED_ABSENT
        }
    }
}

internal fun queryResolverPresence(
    resolver: ContentResolver? = null,
    uri: Uri? = null,
    query: (() -> Cursor?)? = null,
): DeletionVerification {
    if (query == null && (resolver == null || uri == null)) return DeletionVerification.UNKNOWN
    return try {
        val cursor = if (query != null) {
            query()
        } else {
            resolver?.query(uri!!, arrayOf(MediaStore.Audio.Media._ID), null, null, null)
        }
        verifyPresenceFromCursor(cursor)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        DeletionVerification.UNKNOWN
    }
}

class MediaStoreSongDeleter internal constructor(
    private val hasStoragePermission: () -> Boolean,
    private val queryPresenceForSong: (Long) -> DeletionVerification,
    private val deleteSong: (Long) -> Int,
    private val createDeleteRequestSender: ((Long) -> IntentSender?)? = null,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val extractRecoverableSender: (RecoverableSecurityException) -> IntentSender? = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            defaultRecoverableSender(it)
        } else {
            null
        }
    },
) : SongDeleter {

    companion object {
        @RequiresApi(Build.VERSION_CODES.Q)
        internal fun defaultRecoverableSender(exception: RecoverableSecurityException): IntentSender? {
            return exception.userAction.actionIntent.intentSender
        }
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
    private fun isAtLeastQ(): Boolean = sdkInt >= Build.VERSION_CODES.Q

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    private fun isAtLeastR(): Boolean = sdkInt >= Build.VERSION_CODES.R

    constructor(context: Context) : this(
        hasStoragePermission = {
            context.applicationContext.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        },
        queryPresenceForSong = { songId ->
            queryResolverPresence(context.applicationContext.contentResolver, MediaStoreUris.song(songId))
        },
        deleteSong = { songId ->
            context.applicationContext.contentResolver.delete(MediaStoreUris.song(songId), null, null)
        },
        createDeleteRequestSender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            { songId ->
                MediaStore.createDeleteRequest(
                    context.applicationContext.contentResolver,
                    listOf(MediaStoreUris.song(songId)),
                ).intentSender
            }
        } else {
            null
        },
    )

    override suspend fun begin(songId: Long): DeleteAttempt = withContext(Dispatchers.IO) {
        when {
            isAtLeastR() -> {
                val intentSender = createDeleteRequestSender?.invoke(songId)
                DeleteAttempt.Confirmation(
                    intentSender,
                    DeleteConfirmationMode.VERIFY,
                )
            }
            isAtLeastQ() -> deleteWithRecoverableAction(songId)
            !hasStoragePermission() -> DeleteAttempt.PermissionRequired
            else -> deleteAndVerify(songId)
        }
    }

    override suspend fun finish(songId: Long, mode: DeleteConfirmationMode): Boolean = withContext(Dispatchers.IO) {
        when (mode) {
            DeleteConfirmationMode.VERIFY -> queryPresenceForSong(songId) == DeletionVerification.CONFIRMED_ABSENT
            DeleteConfirmationMode.RETRY -> deleteAndVerify(songId) == DeleteAttempt.Deleted
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    internal fun deleteWithRecoverableAction(songId: Long): DeleteAttempt {
        return try {
            deleteAndVerify(songId)
        } catch (exception: RecoverableSecurityException) {
            DeleteAttempt.Confirmation(extractRecoverableSender(exception), DeleteConfirmationMode.RETRY)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DeleteAttempt.Failed
        }
    }

    private fun deleteAndVerify(songId: Long): DeleteAttempt {
        return try {
            val rowsDeleted = deleteSong(songId)
            if (rowsDeleted <= 0 || queryPresenceForSong(songId) != DeletionVerification.CONFIRMED_ABSENT) {
                DeleteAttempt.Failed
            } else {
                DeleteAttempt.Deleted
            }
        } catch (securityException: SecurityException) {
            if (isAtLeastQ() && securityException is RecoverableSecurityException) {
                throw securityException
            }
            DeleteAttempt.Failed
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            DeleteAttempt.Failed
        }
    }

    internal fun isGone(resolver: ContentResolver?, uri: Uri?): Boolean =
        queryResolverPresence(resolver, uri) == DeletionVerification.CONFIRMED_ABSENT
}
