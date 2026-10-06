package io.github.saalfy.sur.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import io.github.saalfy.sur.data.search.SEARCH_DEBOUNCE_MS
import io.github.saalfy.sur.data.search.filterPlaylists
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val repository: PlaylistRepository) : ViewModel() {

    /** null while loading. */
    val playlists: StateFlow<List<PlaylistSummary>?> =
        repository.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** [playlists] filtered by name with the debounced query; null while loading. */
    @OptIn(FlowPreview::class)
    val visiblePlaylists: StateFlow<List<PlaylistSummary>?> =
        combine(playlists, _query.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }) { list, query ->
            list?.let { filterPlaylists(it, query) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onQueryChange(query: String) {
        _query.value = query
    }

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
