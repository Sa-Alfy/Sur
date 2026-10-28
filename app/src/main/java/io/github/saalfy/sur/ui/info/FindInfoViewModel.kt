package io.github.saalfy.sur.ui.info

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.saalfy.sur.SurApplication
import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.info.CoverStore
import io.github.saalfy.sur.data.info.SongInfoEntity
import io.github.saalfy.sur.data.info.SongInfoKey
import io.github.saalfy.sur.data.info.SongInfoRepository
import io.github.saalfy.sur.data.info.SongInfoStatus
import io.github.saalfy.sur.data.info.SongKeyResolver
import io.github.saalfy.sur.data.info.cleaner.CandidateRanker
import io.github.saalfy.sur.data.info.cleaner.QueryCleaner
import io.github.saalfy.sur.data.info.lookup.Candidate
import io.github.saalfy.sur.data.info.lookup.LookupBusyException
import io.github.saalfy.sur.data.info.lookup.LookupHttpException
import io.github.saalfy.sur.data.info.lookup.LookupQuery
import io.github.saalfy.sur.data.info.lookup.SongInfoLookup
import io.github.saalfy.sur.data.playlist.m3u.MediaStoreSongFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface SearchState {
    data object Idle : SearchState
    data object Searching : SearchState
    data object Empty : SearchState
    data object Success : SearchState
    sealed interface Error : SearchState {
        data object Busy : Error
        data class Http(val code: Int) : Error
        data object Network : Error
        data class Generic(val message: String?) : Error
    }
}

sealed interface CoverState {
    data object Idle : CoverState
    data object Loading : CoverState
    data class Success(val bytes: ByteArray) : CoverState {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Success && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data object NotFound : CoverState
}

data class FindInfoUiState(
    val titleInput: String = "",
    val artistInput: String = "",
    val variants: List<LookupQuery> = emptyList(),
    val currentVariantIndex: Int = 0,
    val resolvedKey: String? = null,
    val hasExistingApplied: Boolean = false,
    val searchState: SearchState = SearchState.Idle,
    val candidates: List<Candidate> = emptyList(),
    val selectedCandidate: Candidate? = null,
    val coverState: CoverState = CoverState.Idle,
    val isApplying: Boolean = false,
)

class FindInfoViewModel(
    val song: Song,
    private val lookup: SongInfoLookup,
    private val infoRepository: SongInfoRepository,
    private val coverStore: CoverStore,
    private val songKeyResolver: SongKeyResolver,
    private val displayNameProvider: suspend (Long) -> String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FindInfoUiState(
            titleInput = song.title,
            artistInput = song.artist.orEmpty(),
        )
    )
    val uiState: StateFlow<FindInfoUiState> = _uiState.asStateFlow()

    private var existingEntity: SongInfoEntity? = null
    private var searchJob: Job? = null
    private var coverJob: Job? = null

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch(ioDispatcher) {
            val displayName = try {
                displayNameProvider(song.id)
            } catch (_: Exception) {
                null
            }

            val variants = QueryCleaner.clean(
                tagTitle = song.title,
                tagArtist = song.artist,
                displayName = displayName,
                durationMs = song.durationMs,
            ).ifEmpty {
                listOf(LookupQuery(title = song.title, artist = song.artist, durationMs = song.durationMs))
            }

            val key = try {
                songKeyResolver.resolveKey(song.id)
            } catch (_: Exception) {
                null
            } ?: SongInfoKey.buildKey(null, displayName ?: song.title)

            existingEntity = try {
                infoRepository.get(key)
            } catch (_: Exception) {
                null
            }
            val hasExistingApplied = existingEntity?.status == SongInfoStatus.APPLIED

            _uiState.update { current ->
                current.copy(
                    titleInput = variants.firstOrNull()?.title ?: current.titleInput,
                    artistInput = variants.firstOrNull()?.artist ?: current.artistInput,
                    variants = variants,
                    currentVariantIndex = 0,
                    resolvedKey = key,
                    hasExistingApplied = hasExistingApplied,
                )
            }
        }
    }

    fun onTitleChanged(newTitle: String) {
        _uiState.update { it.copy(titleInput = newTitle) }
    }

    fun onArtistChanged(newArtist: String) {
        _uiState.update { it.copy(artistInput = newArtist) }
    }

    fun nextVariant() {
        val variants = _uiState.value.variants
        if (variants.size <= 1) return
        val nextIndex = (_uiState.value.currentVariantIndex + 1) % variants.size
        val nextQuery = variants[nextIndex]
        _uiState.update {
            it.copy(
                currentVariantIndex = nextIndex,
                titleInput = nextQuery.title,
                artistInput = nextQuery.artist.orEmpty(),
            )
        }
    }

    fun search() {
        val title = _uiState.value.titleInput.trim()
        if (title.isBlank()) return
        val artist = _uiState.value.artistInput.trim().ifEmpty { null }
        val query = LookupQuery(title = title, artist = artist, durationMs = song.durationMs)

        cancelWork()

        searchJob = viewModelScope.launch(ioDispatcher) {
            _uiState.update {
                it.copy(
                    searchState = SearchState.Searching,
                    candidates = emptyList(),
                    selectedCandidate = null,
                    coverState = CoverState.Idle,
                )
            }
            try {
                val results = lookup.search(query)
                val ranked = CandidateRanker.rank(results, song.durationMs)
                _uiState.update {
                    if (ranked.isEmpty()) {
                        it.copy(searchState = SearchState.Empty)
                    } else {
                        it.copy(searchState = SearchState.Success, candidates = ranked)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LookupBusyException) {
                _uiState.update { it.copy(searchState = SearchState.Error.Busy) }
            } catch (e: LookupHttpException) {
                _uiState.update { it.copy(searchState = SearchState.Error.Http(e.code)) }
            } catch (e: IOException) {
                _uiState.update { it.copy(searchState = SearchState.Error.Network) }
            } catch (e: Exception) {
                _uiState.update { it.copy(searchState = SearchState.Error.Generic(e.message)) }
            }
        }
    }

    fun selectCandidate(candidate: Candidate) {
        coverJob?.cancel()
        _uiState.update {
            it.copy(
                selectedCandidate = candidate,
                coverState = CoverState.Loading,
            )
        }
        coverJob = viewModelScope.launch(ioDispatcher) {
            try {
                val bytes = lookup.fetchCover(candidate)
                _uiState.update {
                    if (bytes != null && bytes.isNotEmpty()) {
                        it.copy(coverState = CoverState.Success(bytes))
                    } else {
                        it.copy(coverState = CoverState.NotFound)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _uiState.update { it.copy(coverState = CoverState.NotFound) }
            }
        }
    }

    fun apply(onSuccess: () -> Unit) {
        val candidate = _uiState.value.selectedCandidate ?: return
        val key = _uiState.value.resolvedKey ?: return

        cancelWork()

        viewModelScope.launch(ioDispatcher) {
            _uiState.update { it.copy(isApplying = true) }
            try {
                val existing = existingEntity ?: try {
                    infoRepository.get(key)
                } catch (_: Exception) {
                    null
                }
                val oldCoverFile = existing?.coverFile
                val coverBytes = (_uiState.value.coverState as? CoverState.Success)?.bytes
                val newCoverFile = if (coverBytes != null) {
                    try {
                        coverStore.save(coverBytes)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                if (!oldCoverFile.isNullOrBlank() && oldCoverFile != newCoverFile) {
                    try {
                        coverStore.delete(oldCoverFile)
                    } catch (_: Exception) {
                        // ignore delete error
                    }
                }

                infoRepository.apply(
                    key = key,
                    title = candidate.title,
                    artist = candidate.artist,
                    album = candidate.releaseTitle,
                    coverFile = newCoverFile,
                    mbRecordingId = candidate.recordingId,
                )

                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } finally {
                _uiState.update { it.copy(isApplying = false) }
            }
        }
    }

    fun skip(onSuccess: () -> Unit) {
        val key = _uiState.value.resolvedKey ?: return
        cancelWork()
        viewModelScope.launch(ioDispatcher) {
            try {
                infoRepository.skip(key)
            } catch (_: Exception) {
                // ignore
            }
            withContext(Dispatchers.Main) {
                onSuccess()
            }
        }
    }

    fun removeFoundInfo(onSuccess: () -> Unit) {
        val key = _uiState.value.resolvedKey ?: return
        cancelWork()
        viewModelScope.launch(ioDispatcher) {
            val existing = existingEntity ?: try {
                infoRepository.get(key)
            } catch (_: Exception) {
                null
            }
            if (!existing?.coverFile.isNullOrBlank()) {
                try {
                    coverStore.delete(existing.coverFile)
                } catch (_: Exception) {
                    // ignore
                }
            }
            try {
                infoRepository.reset(key)
            } catch (_: Exception) {
                // ignore
            }
            withContext(Dispatchers.Main) {
                onSuccess()
            }
        }
    }

    fun cancelWork() {
        searchJob?.cancel()
        coverJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        cancelWork()
    }

    companion object {
        fun factory(song: Song, context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = context.applicationContext as SurApplication
                    val container = app.container
                    val contentResolver = context.applicationContext.contentResolver
                    return FindInfoViewModel(
                        song = song,
                        lookup = container.songInfoLookup,
                        infoRepository = container.songInfoRepository,
                        coverStore = container.coverStore,
                        songKeyResolver = container.songKeyResolver,
                        displayNameProvider = { id ->
                            MediaStoreSongFiles.query(contentResolver, setOf(id))[id]?.displayName
                        },
                    ) as T
                }
            }
    }
}
