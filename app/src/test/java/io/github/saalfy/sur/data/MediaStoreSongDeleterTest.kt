package io.github.saalfy.sur.data

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.IntentSender
import android.database.Cursor
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

/**
 * Regression tests for R2-06: null MediaStore cursor must not be treated as confirmed deletion.
 *
 * The original defect: `isGone()` returned `?: true`, meaning a null cursor (from a failed or
 * unavailable query) was interpreted as "song is gone", triggering destructive cleanup.
 *
 * Required invariant: a null cursor, a query exception, or any inconclusive result must
 * return UNKNOWN — never CONFIRMED_ABSENT — so cleanup is not triggered.
 */
class MediaStoreSongDeleterTest {

    private val songId = 42L

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateInstance(clazz: Class<T>): T {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(clazz) as T
    }

    private fun deleter(
        presence: DeletionVerification = DeletionVerification.CONFIRMED_ABSENT,
        deleteRows: Int = 1,
        deleteThrows: Throwable? = null,
    ): MediaStoreSongDeleter = MediaStoreSongDeleter(
        hasStoragePermission = { true },
        queryPresenceForSong = { presence },
        deleteSong = { _ ->
            if (deleteThrows != null) throw deleteThrows
            deleteRows
        },
        createDeleteRequestSender = null,
    )

    private fun fakeCursor(hasRows: Boolean): Cursor {
        return Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "moveToFirst" -> hasRows
                "close" -> null
                "isClosed" -> false
                else -> null
            }
        } as Cursor
    }

    // -------------------------------------------------------------------------
    // verifyPresenceFromCursor: cursor level verification
    // -------------------------------------------------------------------------

    @Test
    fun verifyPresence_rowReturned_confirmsPresent() {
        val cursor = fakeCursor(hasRows = true)
        assertEquals(DeletionVerification.CONFIRMED_PRESENT, verifyPresenceFromCursor(cursor))
    }

    @Test
    fun verifyPresence_emptyCursor_confirmsAbsent() {
        val cursor = fakeCursor(hasRows = false)
        assertEquals(DeletionVerification.CONFIRMED_ABSENT, verifyPresenceFromCursor(cursor))
    }

    /**
     * THE DEFECT CASE (R2-06): null cursor must yield UNKNOWN, not CONFIRMED_ABSENT.
     * Before the fix, `?: true` mapped null -> true (gone), triggering destructive cleanup.
     */
    @Test
    fun verifyPresence_nullCursor_returnsUnknown_notAbsent() {
        val result = verifyPresenceFromCursor(null)
        assertEquals(
            "A null cursor must not be treated as confirmed absence",
            DeletionVerification.UNKNOWN,
            result,
        )
        assertFalse(
            "Null cursor must never produce CONFIRMED_ABSENT",
            result == DeletionVerification.CONFIRMED_ABSENT,
        )
    }

    // -------------------------------------------------------------------------
    // queryResolverPresence: ContentResolver level verification
    // -------------------------------------------------------------------------

    @Test
    fun queryResolverPresence_nullCursor_returnsUnknown() {
        val result = queryResolverPresence(query = { null })
        assertEquals(
            "queryResolverPresence must return UNKNOWN when query returns null",
            DeletionVerification.UNKNOWN,
            result,
        )
    }

    @Test
    fun queryResolverPresence_queryThrows_returnsUnknown() {
        val result = queryResolverPresence(query = {
            throw IllegalStateException("Query failed")
        })
        assertEquals(
            "queryResolverPresence must return UNKNOWN when query throws",
            DeletionVerification.UNKNOWN,
            result,
        )
    }

    @Test
    fun queryResolverPresence_nullCursorAndThrows_neitherProducesDeleted() = runBlocking {
        // Case 1: null cursor
        val nullResult = queryResolverPresence(query = { null })
        val deleterNull = deleter(presence = nullResult, deleteRows = 1)
        val attemptNull = deleterNull.begin(songId)
        assertFalse(
            "Null cursor presence must never produce DeleteAttempt.Deleted",
            attemptNull is DeleteAttempt.Deleted,
        )
        assertEquals(DeleteAttempt.Failed, attemptNull)

        // Case 2: query throws
        val throwResult = queryResolverPresence(query = {
            throw RuntimeException("Query error")
        })
        val deleterThrow = deleter(presence = throwResult, deleteRows = 1)
        val attemptThrow = deleterThrow.begin(songId)
        assertFalse(
            "Query throwing presence must never produce DeleteAttempt.Deleted",
            attemptThrow is DeleteAttempt.Deleted,
        )
        assertEquals(DeleteAttempt.Failed, attemptThrow)
    }

    // -------------------------------------------------------------------------
    // isGone: backward-compat helper, must not map null -> true
    // -------------------------------------------------------------------------

    @Test
    fun isGone_nullResolverOrUri_returnsFalse() {
        val deleter = deleter()
        assertFalse(
            "isGone must return false when resolver or uri is null — the defect returned true",
            deleter.isGone(null, null),
        )
    }

    // -------------------------------------------------------------------------
    // deleteAndVerify via begin() on API 28 (bypasses OS-level permission flows)
    // -------------------------------------------------------------------------

    @Test
    fun begin_deleteReturnsZeroRows_returnsFailed() = runBlocking {
        val d = deleter(deleteRows = 0, presence = DeletionVerification.CONFIRMED_ABSENT)
        assertEquals(DeleteAttempt.Failed, d.begin(songId))
    }

    @Test
    fun begin_deleteSucceeds_queryConfirmsAbsent_returnsDeleted() = runBlocking {
        val d = deleter(deleteRows = 1, presence = DeletionVerification.CONFIRMED_ABSENT)
        assertEquals(DeleteAttempt.Deleted, d.begin(songId))
    }

    @Test
    fun begin_deleteSucceeds_queryUnknown_returnsFailed_notDeleted() = runBlocking {
        // After deleting, if verification is UNKNOWN (e.g. null cursor or exception), must yield Failed
        val d = deleter(deleteRows = 1, presence = DeletionVerification.UNKNOWN)
        val result = d.begin(songId)
        assertEquals(
            "Inconclusive post-delete verification must yield Failed, not Deleted",
            DeleteAttempt.Failed,
            result,
        )
    }

    @Test
    fun begin_deleteSucceeds_rowStillPresent_returnsFailed() = runBlocking {
        // Row still found after delete — not actually deleted
        val d = deleter(deleteRows = 1, presence = DeletionVerification.CONFIRMED_PRESENT)
        assertEquals(DeleteAttempt.Failed, d.begin(songId))
    }

    @Test
    fun begin_deleteThrowsSecurityException_returnsFailed() = runBlocking {
        val d = deleter(deleteThrows = SecurityException("no permission"))
        assertEquals(DeleteAttempt.Failed, d.begin(songId))
    }

    @Test
    fun begin_deleteThrowsOtherException_returnsFailed() = runBlocking {
        val d = deleter(deleteThrows = IOException("disk error"))
        assertEquals(DeleteAttempt.Failed, d.begin(songId))
    }

    // -------------------------------------------------------------------------
    // finish(VERIFY mode): null/unknown must not claim success
    // -------------------------------------------------------------------------

    @Test
    fun finish_verifyMode_presenceUnknown_returnsFalse() = runBlocking {
        val d = deleter(presence = DeletionVerification.UNKNOWN)
        val result = d.finish(songId, DeleteConfirmationMode.VERIFY)
        assertFalse(
            "VERIFY finish with UNKNOWN presence must return false (not 'deleted')",
            result,
        )
    }

    @Test
    fun finish_verifyMode_confirmedAbsent_returnsTrue() = runBlocking {
        val d = deleter(presence = DeletionVerification.CONFIRMED_ABSENT)
        assertTrue(d.finish(songId, DeleteConfirmationMode.VERIFY))
    }

    @Test
    fun finish_verifyMode_confirmedPresent_returnsFalse() = runBlocking {
        val d = deleter(presence = DeletionVerification.CONFIRMED_PRESENT)
        assertFalse(d.finish(songId, DeleteConfirmationMode.VERIFY))
    }

    // -------------------------------------------------------------------------
    // finish(RETRY mode)
    // -------------------------------------------------------------------------

    @Test
    fun finish_retryMode_deleteSucceedsAndAbsent_returnsTrue() = runBlocking {
        val d = deleter(deleteRows = 1, presence = DeletionVerification.CONFIRMED_ABSENT)
        assertTrue(d.finish(songId, DeleteConfirmationMode.RETRY))
    }

    @Test
    fun finish_retryMode_deleteReturnsZero_returnsFalse() = runBlocking {
        val d = deleter(deleteRows = 0, presence = DeletionVerification.CONFIRMED_ABSENT)
        assertFalse(d.finish(songId, DeleteConfirmationMode.RETRY))
    }

    @Test
    fun finish_retryMode_deleteSucceedsButPresenceUnknown_returnsFalse() = runBlocking {
        val d = deleter(deleteRows = 1, presence = DeletionVerification.UNKNOWN)
        assertFalse(d.finish(songId, DeleteConfirmationMode.RETRY))
    }

    // -------------------------------------------------------------------------
    // Android 10 (Q) RecoverableSecurityException flow
    // -------------------------------------------------------------------------

    @Test
    fun begin_recoverableSecurityException_onAndroid10_producesConfirmationWithRetryAndPreservesSender() = runBlocking {
        val expectedSender = allocateInstance(IntentSender::class.java)
        val recoverableException = allocateInstance(RecoverableSecurityException::class.java)

        val deleter = MediaStoreSongDeleter(
            hasStoragePermission = { true },
            queryPresenceForSong = { DeletionVerification.CONFIRMED_ABSENT },
            deleteSong = { throw recoverableException },
            createDeleteRequestSender = null,
            sdkInt = Build.VERSION_CODES.Q,
            extractRecoverableSender = { expectedSender },
        )

        val result = deleter.begin(songId)

        assertTrue(
            "RecoverableSecurityException on Android 10 must produce DeleteAttempt.Confirmation",
            result is DeleteAttempt.Confirmation,
        )
        val confirmation = result as DeleteAttempt.Confirmation
        assertEquals(
            "Confirmation mode on Android 10 must be RETRY",
            DeleteConfirmationMode.RETRY,
            confirmation.mode,
        )
        assertSame(
            "The exact IntentSender from RecoverableSecurityException must be preserved",
            expectedSender,
            confirmation.intentSender,
        )
    }

    @Test
    fun finish_retryMode_afterRecoverableException_verifiesWithDeleteAndVerify() = runBlocking {
        // When user grants recoverable permission, finish(RETRY) retries deleteAndVerify
        var deletedInvoked = false
        val deleter = MediaStoreSongDeleter(
            hasStoragePermission = { true },
            queryPresenceForSong = { DeletionVerification.CONFIRMED_ABSENT },
            deleteSong = {
                deletedInvoked = true
                1
            },
            createDeleteRequestSender = null,
            sdkInt = Build.VERSION_CODES.Q,
        )

        val result = deleter.finish(songId, DeleteConfirmationMode.RETRY)

        assertTrue("finish(RETRY) must return true when retry delete succeeds and is absent", result)
        assertTrue("deleteSong must be invoked during retry", deletedInvoked)
    }
}
