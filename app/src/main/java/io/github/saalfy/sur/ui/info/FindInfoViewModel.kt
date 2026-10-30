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
import io.github.saalfy.sur.data.info.cleaner.textSimilarity
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
    /** Results found but none is confident enough to preselect; candidates are available to tap. */
    data object NoConfident : SearchState
    sealed interface Error : SearchState {
        data object Busy : Error
        data class Http(val code: Int) : Error
        data object Network : Error
        data class Generic(val message: String?) : Error
        data object CoverSaveFailed : Error
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
    /** The single "Search yourself" field: "song name" or "artist - song name". */
    val freeTextInput: String = "",
    val variants: List<LookupQuery> = emptyList(),
    val currentVariantIndex: Int = 0,
    val resolvedKey: String? = null,
    val hasExistingApplied: Boolean = false,
    val searchState: SearchState = SearchState.Idle,
    /** True if the last search used title + artist before falling back to title only. */
    val searchTriedArtist: Boolean = false,
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
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
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
    private val initJob: Job
    private var autoSearchStarted = false

    init {
        initJob = loadInitialData()
    }

    private fun loadInitialData(): Job =
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
                    // First variant exactly, so the auto search does not mix in the tag artist
                    artistInput = variants.firstOrNull()?.let { it.artist.orEmpty() } ?: current.artistInput,
                    variants = variants,
                    currentVariantIndex = 0,
                    resolvedKey = key,
                    hasExistingApplied = hasExistingApplied,
                )
            }
        }

    /**
     * Searches once with the cleaner's first variant. Called by the sheet, which the host only
     * shows after consent; later calls do nothing.
     */
    fun startAutoSearch() {
        if (autoSearchStarted) return
        autoSearchStarted = true
        viewModelScope.launch(ioDispatcher) {
            initJob.join()
            search()
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

    fun onFreeTextChanged(text: String) {
        _uiState.update { it.copy(freeTextInput = text) }
    }

    fun search() {
        val title = _uiState.value.titleInput.trim()
        if (title.isBlank()) return
        val artist = _uiState.value.artistInput.trim().ifEmpty { null }
        runSearch(LookupQuery(title = title, artist = artist, durationMs = song.durationMs))
    }

    fun searchFreeText() {
        val query = parseFreeText(_uiState.value.freeTextInput, song.durationMs) ?: return
        runSearch(query)
    }

    private fun runSearch(query: LookupQuery) {
        val artist = query.artist
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
                // Strict title + artist first; a misspelled artist gives nothing, so retry title only
                var results = lookup.search(query)
                if (results.isEmpty() && artist != null) {
                    results = lookup.search(query.copy(artist = null), limit = TITLE_ONLY_LIMIT)
                }
                val ranked = CandidateRanker.rank(results, song.durationMs, wantedArtist = artist)
                // Filter junk: drop candidates whose title similarity to the query title is < 0.5
                val filtered = ranked.filter { textSimilarity(it.title, query.title) >= LOW_SIMILARITY_CUTOFF }
                _uiState.update {
                    if (filtered.isEmpty()) {
                        it.copy(searchState = SearchState.Empty, searchTriedArtist = artist != null)
                    } else {
                        it.copy(searchState = SearchState.Success, candidates = filtered)
                    }
                }
                // Preselect the best match and auto-load its cover ONLY when confident
                val best = filtered.firstOrNull()
                if (best != null && CandidateRanker.isConfident(best, query.title, artist, song.durationMs)) {
                    selectCandidate(best)
                } else if (filtered.isNotEmpty()) {
                    _uiState.update { it.copy(searchState = SearchState.NoConfident) }
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

    /** Applies title, artist, album and cover of the selected match. */
    fun apply(onSuccess: () -> Unit) = applySelected(coverOnly = false, onSuccess)

    /** Applies only the cover; the earlier title, artist and album are kept. */
    fun applyCoverOnly(onSuccess: () -> Unit) {
        if (_uiState.value.coverState !is CoverState.Success) return
        applySelected(coverOnly = true, onSuccess)
    }

    private fun applySelected(coverOnly: Boolean, onSuccess: () -> Unit) {
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
                        _uiState.update { it.copy(searchState = SearchState.Error.CoverSaveFailed) }
                        return@launch
                    }
                } else {
                    existing?.coverFile
                }

                try {
                    infoRepository.apply(
                        key = key,
                        title = if (coverOnly) existing?.title else candidate.title,
                        artist = if (coverOnly) existing?.artist else candidate.artist,
                        album = if (coverOnly) existing?.album else candidate.releaseTitle,
                        coverFile = newCoverFile,
                        mbRecordingId = candidate.recordingId,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (coverBytes != null && !newCoverFile.isNullOrBlank()) {
                        try {
                            coverStore.delete(newCoverFile)
                        } catch (_: Exception) {
                            // ignore cleanup error
                        }
                    }
                    _uiState.update { it.copy(searchState = SearchState.Error.Generic(null)) }
                    return@launch
                }

                if (coverBytes != null && !oldCoverFile.isNullOrBlank() && oldCoverFile != newCoverFile) {
                    try {
                        coverStore.delete(oldCoverFile)
                    } catch (_: Exception) {
                        // ignore delete error
                    }
                }

                withContext(mainDispatcher) {
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
            withContext(mainDispatcher) {
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
            withContext(mainDispatcher) {
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
        private const val TITLE_ONLY_LIMIT = 25
        private const val LOW_SIMILARITY_CUTOFF = 0.5

        /**
         * Parses the "Search yourself" text: "artist - song name" splits on the first " - ",
         * anything else is a title-only query. Returns null if there is nothing to search.
         */
        fun parseFreeText(text: String, durationMs: Long? = null): LookupQuery? {
            val cleaned = QueryCleaner.cleanText(text)
            if (!cleaned.any { it.isLetterOrDigit() }) return null
            val sepIdx = cleaned.indexOf(" - ")
            if (sepIdx >= 0) {
                val artist = cleaned.substring(0, sepIdx).trim()
                val title = cleaned.substring(sepIdx + 3).trim()
                if (title.any { it.isLetterOrDigit() }) {
                    return LookupQuery(
                        title = title,
                        artist = artist.takeIf { a -> a.any { it.isLetterOrDigit() } },
                        durationMs = durationMs,
                    )
                }
            }
            return LookupQuery(title = cleaned, artist = null, durationMs = durationMs)
        }

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
