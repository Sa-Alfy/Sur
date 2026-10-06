package io.github.saalfy.sur.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.SongRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class NeedsPermission(val permanentlyDenied: Boolean) : LibraryUiState
    data object Empty : LibraryUiState
    data class Songs(val songs: List<Song>) : LibraryUiState
}

private enum class PermissionStatus { Unchecked, Granted, Denied, PermanentlyDenied }

class LibraryViewModel(private val repository: SongRepository) : ViewModel() {

    private val permission = MutableStateFlow(PermissionStatus.Unchecked)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<LibraryUiState> = permission
        .flatMapLatest { status ->
            when (status) {
                PermissionStatus.Unchecked -> flowOf(LibraryUiState.Loading)
                PermissionStatus.Denied -> flowOf(LibraryUiState.NeedsPermission(permanentlyDenied = false))
                PermissionStatus.PermanentlyDenied -> flowOf(LibraryUiState.NeedsPermission(permanentlyDenied = true))
                PermissionStatus.Granted -> repository.songs
                    .map { if (it.isEmpty()) LibraryUiState.Empty else LibraryUiState.Songs(it) }
                    .onStart { emit(LibraryUiState.Loading) }
                    .catch { emit(LibraryUiState.NeedsPermission(permanentlyDenied = false)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState.Loading)

    /** Called on every resume with the current grant state (covers returning from Settings). */
    fun onPermissionChecked(granted: Boolean) {
        permission.value = when {
            granted -> PermissionStatus.Granted
            permission.value == PermissionStatus.PermanentlyDenied -> PermissionStatus.PermanentlyDenied
            else -> PermissionStatus.Denied
        }
    }

    /** Called with the result of a permission request dialog. */
    fun onPermissionResult(granted: Boolean, permanentlyDenied: Boolean) {
        permission.value = when {
            granted -> PermissionStatus.Granted
            permanentlyDenied -> PermissionStatus.PermanentlyDenied
            else -> PermissionStatus.Denied
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as SurApplication
                LibraryViewModel(app.container.songRepository)
            }
        }
    }
}
