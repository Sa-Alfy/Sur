package io.github.saalfy.sur.data

import io.github.saalfy.sur.data.info.CoverStore
import io.github.saalfy.sur.data.info.SongInfoEntity
import io.github.saalfy.sur.data.info.SongInfoRepository
import io.github.saalfy.sur.data.info.SongInfoStatus
import io.github.saalfy.sur.data.info.SongKeyResolver
import io.github.saalfy.sur.data.playlist.Playlist
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.PlaylistSong
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
        coverStore = object : CoverStore by FakeCoverStore() {
            override fun delete(name: String): Boolean {
                events += "cover"
                return true
            }
        },
        artworkEvictor = object : ArtworkEvictor {
            override fun evict(songId: Long) { events += "artwork" }
        },
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
        override fun observeAll(): Flow<List<SongInfoEntity>> = emptyFlow()
        override suspend fun apply(key: String, title: String?, artist: String?, album: String?, coverFile: String?, mbRecordingId: String?) = Unit
        override suspend fun skip(key: String) = Unit
        override suspend fun reset(key: String) { rows.remove(key) }
    }

    private class FakeCoverStore : CoverStore {
        override fun save(bytes: ByteArray): String = "cover"
        override fun file(name: String): File? = null
        override fun delete(name: String): Boolean = true
    }

    private class FakePlaylistRepository(
        val entries: MutableMap<Long, MutableList<Long>> = mutableMapOf(),
    ) : PlaylistRepository {
        override val playlists: Flow<List<PlaylistSummary>> = emptyFlow()
        override fun playlist(id: Long): Flow<Playlist?> = emptyFlow()
        override fun playlistSongs(playlistId: Long): Flow<List<PlaylistSong>> = emptyFlow()
        override suspend fun create(name: String): Long = 1L
        override suspend fun rename(id: Long, name: String) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun addSongs(playlistId: Long, songIds: List<Long>) = Unit
        override suspend fun deleteEntriesForSong(songId: Long) {
            entries.replaceAll { _, ids -> ids.filterNot { it == songId }.toMutableList() }
        }
        override suspend fun removeEntry(entryId: Long) = Unit
        override suspend fun moveEntry(playlistId: Long, fromEntryId: Long, toEntryId: Long) = Unit
    }
}