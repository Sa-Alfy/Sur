package io.github.saalfy.sur.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val repository: PlaylistRepository) : ViewModel() {

    /** null while loading. */
    val playlists: StateFlow<List<PlaylistSummary>?> =
        repository.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(name: String) = viewModelScope.launch { repository.create(name) }

    fun rename(id: Long, name: String) = viewModelScope.launch { repository.rename(id, name) }

    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                PlaylistsViewModel((this[APPLICATION_KEY] as SurApplication).container.playlistRepository)
            }
        }
    }
}
