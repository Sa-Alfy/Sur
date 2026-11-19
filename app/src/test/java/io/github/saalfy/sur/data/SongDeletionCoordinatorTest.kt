package io.github.saalfy.sur.data

import io.github.saalfy.sur.data.info.CoverStore
import io.github.saalfy.sur.data.info.SongInfoEntity
import io.github.saalfy.sur.data.info.SongInfoRepository
import io.github.saalfy.sur.data.info.SongInfoStatus
import io.github.saalfy.sur.data.info.SongKeyResolver
import io.github.saalfy.sur.data.info.StoredCover
import io.github.saalfy.sur.data.playlist.Playlist
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.PlaylistSong
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SongDeletionCoordinatorTest {

    @Test
    fun verifiedSuccessCleansInOrderAndResolvesKeyBeforeDeletion() = runBlocking {
        val events = mutableListOf<String>()
        val info = FakeInfoRepository().apply {
            rows["key"] = info("cover.jpg")
        }
        val coordinator = coordinator(
            events = events,
            keyResolver = FakeKeyResolver(events),
            deleter = FakeDeleter(DeleteAttempt.Deleted, events),
            info = info,
        )

        assertEquals(SongDeleteStart.Deleted, coordinator.begin(7L))
        // R2-07: cover file must be deleted BEFORE the DB row is reset.
        assertEquals(listOf("resolve", "delete", "queue", "playlist", "info-get", "info-reset", "cover", "artwork"), events)
    }

    @Test
    fun cancelAndFailureDoNotCleanAnything() = runBlocking {
        val events = mutableListOf<String>()
        val cancelled = coordinator(
            events = events,
            deleter = FakeDeleter(
                DeleteAttempt.Confirmation(null, DeleteConfirmationMode.VERIFY),
                events,
            ),
        )
        val pending = cancelled.begin(7L) as SongDeleteStart.Confirmation
        assertFalse(cancelled.finish(pending, confirmed = false))
        assertEquals(listOf("resolve", "delete"), events)

        events.clear()
        val failed = coordinator(events = events, deleter = FakeDeleter(DeleteAttempt.Failed, events))
        assertEquals(SongDeleteStart.Failed, failed.begin(7L))
        assertEquals(listOf("resolve", "delete"), events)
    }

    // R2-07: the row is reset before its cover is deleted, so a row never points at a missing
    // file and stale overrides cannot re-attach to a new file at the same path. A cover left
    // behind by a failure is removed by the orphan sweep (see tests below).
    @Test
    fun r2_07_dbRowResetBeforeCoverDelete() = runBlocking {
        val order = mutableListOf<String>()
        val info = FakeInfoRepository().apply { rows["key"] = info("cover.jpg") }
        coordinator(events = order, deleter = FakeDeleter(DeleteAttempt.Deleted, order), info = info).begin(7L)
        assertTrue(order.indexOf("info-reset") in 0 until order.indexOf("cover"))
    }

    @Test
    fun r2_07_resetFailure_keepsCoverFile() = runBlocking {
        val events = mutableListOf<String>()
        val info = FakeInfoRepository().apply { rows["key"] = info("cover.jpg"); failReset = true }
        val result = coordinator(events = events, deleter = FakeDeleter(DeleteAttempt.Deleted, events), info = info).begin(7L)
        assertEquals(SongDeleteStart.Deleted, result)
        assertFalse("row still references the cover, so it must stay", events.contains("cover"))
        assertTrue("artwork cache still evicted", events.contains("artwork"))
    }

    @Test
    fun r2_04_failingStepDoesNotSkipTheRest() = runBlocking {
        val events = mutableListOf<String>()
        val info = FakeInfoRepository().apply { rows["key"] = info("cover.jpg") }
        val playlist = FakePlaylistRepository(failDelete = true)
        val result = coordinator(events = events, deleter = FakeDeleter(DeleteAttempt.Deleted, events), playlist = playlist, info = info).begin(7L)
        assertEquals(SongDeleteStart.Deleted, result)
        assertTrue(events.containsAll(listOf("info-reset", "cover", "artwork")))
        assertTrue(info.rows.isEmpty())
    }

    @Test
    fun coverSharedWithAnotherRow_isNotDeleted() = runBlocking {
        val events = mutableListOf<String>()
        val info = FakeInfoRepository().apply {
            rows["key"] = info("shared.jpg")
            rows["other"] = info("shared.jpg").copy(key = "other")
        }
        coordinator(events = events, deleter = FakeDeleter(DeleteAttempt.Deleted, events), info = info).begin(7L)
        assertTrue(events.contains("info-reset"))
        assertFalse(events.contains("cover"))
    }

    @Test
    fun r2_08_deleterThrows_reportsFailed_noCleanup() = runBlocking {
        val events = mutableListOf<String>()
        val deleter = object : SongDeleter {
            override suspend fun begin(songId: Long): DeleteAttempt = throw IllegalStateException("createDeleteRequest")
            override suspend fun finish(songId: Long, mode: DeleteConfirmationMode): Boolean = throw IllegalStateException()
        }
        val c = coordinator(events = events, deleter = deleter)
        assertEquals(SongDeleteStart.Failed, c.begin(7L))
        assertFalse(c.finish(SongDeleteStart.Confirmation(7L, "key", null, DeleteConfirmationMode.VERIFY), confirmed = true))
        assertEquals(listOf("resolve"), events)
    }

    @Test
    fun r2_08_userCancelledConfirmation_cleansNothing() = runBlocking {
        val events = mutableListOf<String>()
        val c = coordinator(events = events, deleter = FakeDeleter(DeleteAttempt.Deleted, events))
        assertFalse(c.finish(SongDeleteStart.Confirmation(7L, "key", null, DeleteConfirmationMode.VERIFY), confirmed = false))
        assertTrue(events.isEmpty())
    }

    @Test
    fun orphanSweep_removesOnlyOldUnreferencedCovers() = runBlocking {
        val now = 10 * SongDeletionCoordinator.ORPHAN_MIN_AGE_MS
        val old = now - SongDeletionCoordinator.ORPHAN_MIN_AGE_MS - 1
        val store = ListingCoverStore(
            mutableMapOf("orphan-old.jpg" to old, "orphan-new.jpg" to now - 1000, "used.jpg" to old, "unknown-age.jpg" to 0L),
        )
        val info = FakeInfoRepository().apply { rows["k"] = info("used.jpg").copy(key = "k") }
        val c = coordinator(deleter = FakeDeleter(DeleteAttempt.Deleted, mutableListOf()), info = info, coverStore = store, now = { now })
        assertEquals(1, c.sweepOrphanCovers())
        assertEquals(setOf("orphan-new.jpg", "used.jpg", "unknown-age.jpg"), store.files.keys)
    }

    @Test
    fun orphanSweep_runsAfterDeletion_andRetriesEarlierLeftovers() = runBlocking {
        val now = 10 * SongDeletionCoordinator.ORPHAN_MIN_AGE_MS
        val store = ListingCoverStore(mutableMapOf("left-from-crash.jpg" to 1L))
        val c = coordinator(deleter = FakeDeleter(DeleteAttempt.Deleted, mutableListOf()), coverStore = store, now = { now })
        c.begin(7L)
        assertTrue(store.files.isEmpty())
    }

    // -------------------------------------------------------------------------
    // R2-06 regression: finish() returning false must NOT trigger cleanup
    // -------------------------------------------------------------------------

    @Test
    fun r2_06_regression_finishReturnsFalse_doesNotCleanPlaylist_metadata_artwork() = runBlocking {
        val events = mutableListOf<String>()
        val info = FakeInfoRepository().apply { rows["key"] = info("cover.jpg") }

        // Deleter returns a Confirmation on begin(), and finish() returns false
        // (simulates the case where finish() cannot verify deletion — e.g. null cursor)
        val deleter = object : SongDeleter {
            override suspend fun begin(songId: Long): DeleteAttempt {
                events += "delete"
                return DeleteAttempt.Confirmation(null, DeleteConfirmationMode.VERIFY)
            }
            override suspend fun finish(songId: Long, mode: DeleteConfirmationMode): Boolean {
                events += "finish"
                return false  // verification failed / inconclusive
            }
        }

        val coordinator = coordinator(events = events, deleter = deleter, info = info)
        val pending = coordinator.begin(7L) as SongDeleteStart.Confirmation
        val result = coordinator.finish(pending, confirmed = true)

        assertFalse("finish() must return false when deletion unverified", result)
        // No cleanup events must have fired
        assertFalse("playlist must not be cleaned when deletion unverified", events.contains("playlist"))
        assertFalse("info-reset must not be called when deletion unverified", events.contains("info-reset"))
        assertFalse("artwork eviction must not happen when deletion unverified", events.contains("artwork"))
        assertFalse("queue removal must not happen when deletion unverified", events.contains("queue"))
        assertFalse("cover file deletion must not happen when deletion unverified", events.contains("cover"))
        assertEquals("Only resolve, delete, and finish should be invoked; no cleanup", listOf("resolve", "delete", "finish"), events)
        // The info row must still be present
        assertEquals("info row must survive inconclusive finish", "cover.jpg", info.rows["key"]?.coverFile)
    }

    @Test
    fun songInTwoPlaylistsLosesBothEntriesAndRemainingPositionsStayContiguous() = runBlocking {
        val playlists = FakePlaylistRepository(
            mutableMapOf(
                1L to mutableListOf(10L, 7L, 11L),
                2L to mutableListOf(7L, 20L),
            ),
        )
        val coordinator = coordinator(
            playlist = playlists,
            deleter = FakeDeleter(DeleteAttempt.Deleted, mutableListOf()),
        )

        coordinator.begin(7L)

        assertEquals(listOf(10L, 11L), playlists.entries[1L])
        assertEquals(listOf(20L), playlists.entries[2L])
        assertEquals(0, playlists.entries[1L]?.indices?.first)
        assertEquals(1, playlists.entries[1L]?.indices?.last)
        assertEquals(0, playlists.entries[2L]?.indices?.first)
        assertEquals(0, playlists.entries[2L]?.indices?.last)
    }

    private fun coordinator(
        events: MutableList<String> = mutableListOf(),
        keyResolver: SongKeyResolver = FakeKeyResolver(events),
        deleter: SongDeleter,
        playlist: FakePlaylistRepository = FakePlaylistRepository(),
        info: FakeInfoRepository = FakeInfoRepository(),
        now: () -> Long = { 0L },
        coverStore: CoverStore = object : CoverStore by FakeCoverStore() {
            override fun delete(name: String): Boolean {
                events += "cover"
                return true
            }
        },
    ) = SongDeletionCoordinator(
        deleter = deleter,
        keyResolver = keyResolver,
        queueSongRemover = object : QueueSongRemover {
            override suspend fun remove(songId: Long) { events += "queue" }
        },
        playlistRepository = object : PlaylistRepository by playlist {
            override suspend fun deleteEntriesForSong(songId: Long) {
                events += "playlist"
                playlist.deleteEntriesForSong(songId)
            }
        },
        infoRepository = object : SongInfoRepository by info {
            override suspend fun get(key: String): SongInfoEntity? {
                events += "info-get"
                return info.get(key)
            }

            override suspend fun reset(key: String) {
                events += "info-reset"
                info.reset(key)
            }
        },
        coverStore = coverStore,
        artworkEvictor = object : ArtworkEvictor {
            override fun evict(songId: Long) { events += "artwork" }
        },
        now = now,
    )

    private fun info(cover: String) = SongInfoEntity(
        key = "key",
        title = "title",
        artist = null,
        album = null,
        coverFile = cover,
        mbRecordingId = null,
        status = SongInfoStatus.APPLIED,
        updatedAt = 0,
    )

    private class FakeDeleter(
        private val attempt: DeleteAttempt,
        private val events: MutableList<String>,
    ) : SongDeleter {
        override suspend fun begin(songId: Long): DeleteAttempt {
            events += "delete"
            return attempt
        }

        override suspend fun finish(songId: Long, mode: DeleteConfirmationMode): Boolean = true
    }

    private class FakeKeyResolver(private val events: MutableList<String>) : SongKeyResolver {
        override suspend fun resolveKeys(): Map<Long, String> = emptyMap()

        override suspend fun resolveKey(songId: Long): String? {
            events += "resolve"
            return "key"
        }
    }

    private class FakeInfoRepository : SongInfoRepository {
        val rows = mutableMapOf<String, SongInfoEntity>()
        override suspend fun get(key: String): SongInfoEntity? = rows[key]
        var failReset = false
        override fun observeAll(): Flow<List<SongInfoEntity>> = flow { emit(rows.values.toList()) }
        override suspend fun apply(key: String, title: String?, artist: String?, album: String?, coverFile: String?, mbRecordingId: String?) = Unit
        override suspend fun skip(key: String) = Unit
        override suspend fun reset(key: String) {
            if (failReset) throw IllegalStateException("db error")
            rows.remove(key)
        }
    }

    private class FakeCoverStore : CoverStore {
        override fun save(bytes: ByteArray): String = "cover"
        override fun file(name: String): File? = null
        override fun delete(name: String): Boolean = true
    }

    private class ListingCoverStore(val files: MutableMap<String, Long>) : CoverStore {
        override fun save(bytes: ByteArray): String = "cover"
        override fun file(name: String): File? = null
        override fun delete(name: String): Boolean = files.remove(name) != null
        override fun list(): List<StoredCover> = files.map { StoredCover(it.key, it.value) }
    }

    private class FakePlaylistRepository(
        val entries: MutableMap<Long, MutableList<Long>> = mutableMapOf(),
        val failDelete: Boolean = false,
    ) : PlaylistRepository {
        override val playlists: Flow<List<PlaylistSummary>> = emptyFlow()
        override fun playlist(id: Long): Flow<Playlist?> = emptyFlow()
        override fun playlistSongs(playlistId: Long): Flow<List<PlaylistSong>> = emptyFlow()
        override suspend fun create(name: String): Long = 1L
        override suspend fun rename(id: Long, name: String) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun addSongs(playlistId: Long, songIds: List<Long>) = Unit
        override suspend fun reconcile() = Unit
        override suspend fun deleteEntriesForSong(songId: Long) {
            if (failDelete) throw IllegalStateException("db error")
            entries.replaceAll { _, ids -> ids.filterNot { it == songId }.toMutableList() }
        }
        override suspend fun removeEntry(entryId: Long) = Unit
        override suspend fun moveEntry(playlistId: Long, fromEntryId: Long, toEntryId: Long) = Unit
    }
}