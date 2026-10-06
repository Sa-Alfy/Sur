package io.github.saalfy.sur.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.playlist.Playlist
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.PlaylistSong
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState
    data object Missing : PlaylistDetailUiState
    data class Loaded(val playlist: Playlist, val songs: List<PlaylistSong>) : PlaylistDetailUiState
}

class PlaylistDetailViewModel(
    private val playlistId: Long,
    private val repository: PlaylistRepository,
) : ViewModel() {

    val uiState: StateFlow<PlaylistDetailUiState> =
        combine(repository.playlist(playlistId), repository.playlistSongs(playlistId)) { playlist, songs ->
            if (playlist == null) PlaylistDetailUiState.Missing else PlaylistDetailUiState.Loaded(playlist, songs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistDetailUiState.Loading)

    fun remove(entryId: Long) = viewModelScope.launch { repository.removeEntry(entryId) }

    fun move(fromEntryId: Long, toEntryId: Long) =
        viewModelScope.launch { repository.moveEntry(playlistId, fromEntryId, toEntryId) }

    companion object {
        fun factory(playlistId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PlaylistDetailViewModel(
                    playlistId,
                    (this[APPLICATION_KEY] as SurApplication).container.playlistRepository,
                )
            }
        }
    }
}
