package io.github.saalfy.sur.ui.playlists

import io.github.saalfy.sur.data.playlist.Playlist
import io.github.saalfy.sur.data.playlist.PlaylistError
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.PlaylistSong
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class PlaylistsViewModelTest {

    private val testDispatcher = Dispatchers.Unconfined

    @Test
    fun create_whenRepositoryThrows_setsErrorToUpdateFailed() = runBlocking {
        val repo = ThrowingPlaylistRepository(throwOnCreate = true)
        val vm = PlaylistsViewModel(repository = repo, ioDispatcher = testDispatcher)

        assertNull(vm.error.value)
        vm.create("Favorites")
        assertEquals(PlaylistError.UpdateFailed, vm.error.value)
    }

    @Test
    fun rename_whenRepositoryThrows_setsErrorToUpdateFailed() = runBlocking {
        val repo = ThrowingPlaylistRepository(throwOnRename = true)
        val vm = PlaylistsViewModel(repository = repo, ioDispatcher = testDispatcher)

        assertNull(vm.error.value)
        vm.rename(42L, "New Name")
        assertEquals(PlaylistError.UpdateFailed, vm.error.value)
    }

    @Test
    fun delete_whenRepositoryThrows_setsErrorToUpdateFailed() = runBlocking {
        val repo = ThrowingPlaylistRepository(throwOnDelete = true)
        val vm = PlaylistsViewModel(repository = repo, ioDispatcher = testDispatcher)

        assertNull(vm.error.value)
        vm.delete(42L)
        assertEquals(PlaylistError.UpdateFailed, vm.error.value)
    }

    @Test
    fun clearError_resetsErrorToNull() = runBlocking {
        val repo = ThrowingPlaylistRepository(throwOnCreate = true)
        val vm = PlaylistsViewModel(repository = repo, ioDispatcher = testDispatcher)

        vm.create("Test")
        assertEquals(PlaylistError.UpdateFailed, vm.error.value)
        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun create_whenCancellationExceptionThrown_doesNotCatchOrSetUpdateFailed() = runBlocking {
        val repo = ThrowingPlaylistRepository(cancellationOnCreate = true)
        val vm = PlaylistsViewModel(repository = repo, ioDispatcher = testDispatcher)

        vm.create("Cancelled")
        assertNull(vm.error.value)
    }

    private class ThrowingPlaylistRepository(
        var throwOnCreate: Boolean = false,
        var throwOnRename: Boolean = false,
        var throwOnDelete: Boolean = false,
        var cancellationOnCreate: Boolean = false,
    ) : PlaylistRepository {
        override val playlists: Flow<List<PlaylistSummary>> = emptyFlow()
        override fun playlist(id: Long): Flow<Playlist?> = emptyFlow()
        override fun playlistSongs(playlistId: Long): Flow<List<PlaylistSong>> = emptyFlow()

        override suspend fun create(name: String): Long {
            if (cancellationOnCreate) throw CancellationException("Job was cancelled")
            if (throwOnCreate) throw IOException("Disk I/O failure")
            return 1L
        }

        override suspend fun rename(id: Long, name: String) {
            if (throwOnRename) throw RuntimeException("Database locked")
        }

        override suspend fun delete(id: Long) {
            if (throwOnDelete) throw RuntimeException("Database error")
        }

        override suspend fun addSongs(playlistId: Long, songIds: List<Long>) = Unit
        override suspend fun deleteEntriesForSong(songId: Long) = Unit
        override suspend fun removeEntry(entryId: Long) = Unit
        override suspend fun moveEntry(playlistId: Long, fromEntryId: Long, toEntryId: Long) = Unit
    }
}

