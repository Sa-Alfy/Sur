package io.github.saalfy.sur.ui.songs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.SongRepository
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SongsViewModel(
    songRepository: SongRepository,
    private val playlistRepository: PlaylistRepository,
) : ViewModel() {

    /** null while loading. */
    val songs: StateFlow<List<Song>?> =
        songRepository.songs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val playlists: StateFlow<List<PlaylistSummary>> =
        playlistRepository.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addToPlaylist(playlistId: Long, songIds: List<Long>) =
        viewModelScope.launch { playlistRepository.addSongs(playlistId, songIds) }

    fun addToNewPlaylist(name: String, songIds: List<Long>) = viewModelScope.launch {
        val id = playlistRepository.create(name)
        playlistRepository.addSongs(id, songIds)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as SurApplication).container
                SongsViewModel(container.songRepository, container.playlistRepository)
            }
        }
    }
}
