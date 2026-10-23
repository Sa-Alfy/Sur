package io.github.saalfy.sur.ui.playlist

import android.content.ContentResolver
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
import io.github.saalfy.sur.data.playlist.m3u.M3uEntry
import io.github.saalfy.sur.data.playlist.m3u.M3uWriter
import io.github.saalfy.sur.data.playlist.m3u.MediaStoreSongFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface PlaylistDetailUiState {
    data object Loading : PlaylistDetailUiState
    data object Missing : PlaylistDetailUiState
    data class Loaded(val playlist: Playlist, val songs: List<PlaylistSong>) : PlaylistDetailUiState
}

/** Shown after a successful export, then cleared. null = no message. */
data class ExportResult(val songCount: Int, val weakCount: Int)

class PlaylistDetailViewModel(
    private val playlistId: Long,
    private val repository: PlaylistRepository,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    val uiState: StateFlow<PlaylistDetailUiState> =
        combine(repository.playlist(playlistId), repository.playlistSongs(playlistId)) { playlist, songs ->
            if (playlist == null) PlaylistDetailUiState.Missing else PlaylistDetailUiState.Loaded(playlist, songs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistDetailUiState.Loading)

    private val _exportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = _exportResult.asStateFlow()

    fun remove(entryId: Long) = viewModelScope.launch { repository.removeEntry(entryId) }

    fun move(fromEntryId: Long, toEntryId: Long) =
        viewModelScope.launch { repository.moveEntry(playlistId, fromEntryId, toEntryId) }

    fun exportToUri(uri: android.net.Uri) {
        val state = uiState.value as? PlaylistDetailUiState.Loaded ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val songs = state.songs
                val songIds = songs.map { it.song.id }.toSet()
                val songFiles = MediaStoreSongFiles.query(contentResolver, songIds)

                val entries = songs.map { ps ->
                    val sf = songFiles[ps.song.id]
                    M3uEntry(
                        title = ps.song.title,
                        artist = ps.song.artist,
                        durationSec = (ps.song.durationMs / 1000).toInt(),
                        path = sf?.relativePath ?: ps.song.title,
                    )
                }

                val writeResult = M3uWriter.write(state.playlist.name, entries)
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(writeResult.text.toByteArray(Charsets.UTF_8))
                }
                _exportResult.value = ExportResult(songs.size, writeResult.weakCount)
            }
        }
    }

    fun clearExportResult() {
        _exportResult.value = null
    }

    companion object {
        fun factory(playlistId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as SurApplication
                PlaylistDetailViewModel(
                    playlistId,
                    app.container.playlistRepository,
                    app.contentResolver,
                )
            }
        }
    }
}
