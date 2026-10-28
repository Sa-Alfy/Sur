package io.github.saalfy.sur.ui.playlists

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.playlist.PlaylistRepository
import io.github.saalfy.sur.data.playlist.PlaylistSummary
import io.github.saalfy.sur.data.playlist.m3u.M3uMatcher
import io.github.saalfy.sur.data.playlist.m3u.M3uParser
import io.github.saalfy.sur.data.playlist.m3u.MediaStoreSongFiles
import io.github.saalfy.sur.data.search.SEARCH_DEBOUNCE_MS
import io.github.saalfy.sur.data.search.filterPlaylists
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportResult {
    data class Success(val name: String, val added: Int, val notFound: Int) : ImportResult
    data object NothingMatched : ImportResult
    data object FileTooLarge : ImportResult
    data object Failed : ImportResult
}

class PlaylistsViewModel(
    private val repository: PlaylistRepository,
    private val contentResolver: ContentResolver,
) : ViewModel() {

    /** null while loading. */
    val playlists: StateFlow<List<PlaylistSummary>?> =
        repository.playlists.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

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

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val maxBytes = 2 * 1024 * 1024
                    var exceeded = false
                    val stream = contentResolver.openInputStream(uri)
                    if (stream == null) {
                        _importResult.value = ImportResult.Failed
                        return@withContext
                    }
                    val bytes = stream.use { s ->
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        var totalRead = 0
                        var read: Int
                        while (s.read(chunk).also { read = it } != -1) {
                            totalRead += read
                            if (totalRead > maxBytes) {
                                exceeded = true
                                return@use null
                            }
                            buffer.write(chunk, 0, read)
                        }
                        buffer.toByteArray()
                    }

                    if (exceeded) {
                        _importResult.value = ImportResult.FileTooLarge
                        return@withContext
                    }
                    if (bytes == null) {
                        _importResult.value = ImportResult.Failed
                        return@withContext
                    }

                    val text = bytes.toString(Charsets.UTF_8)
                    val displayName = try {
                        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (idx >= 0) cursor.getString(idx) else null
                            } else null
                        }
                    } catch (e: Exception) {
                        null
                    }

                    val fallbackName = displayName?.substringBeforeLast('.')?.trim()?.ifEmpty { null }
                        ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.trim()?.ifEmpty { null }
                        ?: "Imported Playlist"

                    val parsed = M3uParser.parse(text, fallbackName)
                    if (parsed.entries.isEmpty()) {
                        _importResult.value = ImportResult.NothingMatched
                        return@withContext
                    }

                    val library = MediaStoreSongFiles.queryAll(contentResolver)
                    val matchResult = M3uMatcher.match(parsed.entries, library)
                    val matchedSongIds = matchResult.matched.filterNotNull().distinct()

                    if (matchedSongIds.isEmpty()) {
                        _importResult.value = ImportResult.NothingMatched
                        return@withContext
                    }

                    val existingPlaylists = repository.playlists.first()
                    val existingNames = existingPlaylists.map { it.playlist.name }.toSet()

                    var targetName = parsed.playlistName.trim().ifEmpty { fallbackName }
                    if (targetName in existingNames) {
                        var counter = 2
                        while ("$targetName ($counter)" in existingNames) {
                            counter++
                        }
                        targetName = "$targetName ($counter)"
                    }

                    val newId = repository.create(targetName)
                    repository.addSongs(newId, matchedSongIds)

                    val added = matchedSongIds.size
                    val notFound = parsed.entries.size - added
                    _importResult.value = ImportResult.Success(targetName, added, notFound)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _importResult.value = ImportResult.Failed
                }
            }
        }
    }

    fun clearImportResult() {
        _importResult.value = null
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as SurApplication
                PlaylistsViewModel(
                    app.container.playlistRepository,
                    app.contentResolver,
                )
            }
        }
    }
}
