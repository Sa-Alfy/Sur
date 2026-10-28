package io.github.saalfy.sur.ui.info

import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.info.CoverStore
import io.github.saalfy.sur.data.info.SongInfoEntity
import io.github.saalfy.sur.data.info.SongInfoRepository
import io.github.saalfy.sur.data.info.SongInfoStatus
import io.github.saalfy.sur.data.info.SongKeyResolver
import io.github.saalfy.sur.data.info.lookup.Candidate
import io.github.saalfy.sur.data.info.lookup.LookupBusyException
import io.github.saalfy.sur.data.info.lookup.LookupHttpException
import io.github.saalfy.sur.data.info.lookup.LookupQuery
import io.github.saalfy.sur.data.info.lookup.SongInfoLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class FindInfoViewModelTest {

    private val testDispatcher = Dispatchers.Unconfined

    private val sampleSong = Song(
        id = 100L,
        title = "Imagine (Official Audio)",
        artist = "John Lennon",
        album = "Imagine",
        albumId = 50L,
        durationMs = 183_000L,
    )

    @Test
    fun initialization_prefillsFromQueryCleaner() = runBlocking {
        val vm = createViewModel(song = sampleSong)
        val state = vm.uiState.value

        assertEquals("Imagine", state.titleInput)
        assertEquals("John Lennon", state.artistInput)
        assertTrue(state.variants.isNotEmpty())
        assertEquals("Music/Sur/imagine.mp3", state.resolvedKey)
    }

    @Test
    fun nextVariant_cyclesThroughCleanVariants() = runBlocking {
        val vm = createViewModel(song = sampleSong)
        assertEquals(0, vm.uiState.value.currentVariantIndex)

        if (vm.uiState.value.variants.size > 1) {
            val second = vm.uiState.value.variants[1]
            vm.nextVariant()
            assertEquals(1, vm.uiState.value.currentVariantIndex)
            assertEquals(second.title, vm.uiState.value.titleInput)
        }
    }

    @Test
    fun manualEditing_updatesInputFields() = runBlocking {
        val vm = createViewModel(song = sampleSong)
        vm.onTitleChanged("Custom Title")
        vm.onArtistChanged("Custom Artist")

        assertEquals("Custom Title", vm.uiState.value.titleInput)
        assertEquals("Custom Artist", vm.uiState.value.artistInput)
    }

    @Test
    fun search_success_ranksCandidatesByScoreAndDuration() = runBlocking {
        val candidate1 = Candidate(
            recordingId = "rec-1",
            title = "Imagine",
            artist = "John Lennon",
            releaseTitle = "Imagine",
            year = "1971",
            durationMs = 183_000L,
            score = 100,
            releaseIds = listOf("rel-1"),
        )
        val candidate2 = Candidate(
            recordingId = "rec-2",
            title = "Imagine",
            artist = "A Tribute Band",
            releaseTitle = "Tribute",
            year = "2000",
            durationMs = 210_000L,
            score = 80,
            releaseIds = listOf("rel-2"),
        )

        val fakeLookup = FakeSongInfoLookup(searchResult = listOf(candidate2, candidate1))
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.search()

        val state = vm.uiState.value
        assertEquals(SearchState.Success, state.searchState)
        assertEquals(2, state.candidates.size)
        // Higher score should rank first
        assertEquals("rec-1", state.candidates[0].recordingId)
        assertEquals("rec-2", state.candidates[1].recordingId)
    }

    @Test
    fun search_empty_setsEmptyState() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchResult = emptyList())
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.search()

        assertEquals(SearchState.Empty, vm.uiState.value.searchState)
        assertTrue(vm.uiState.value.candidates.isEmpty())
    }

    @Test
    fun search_busyException_setsBusyError() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchError = LookupBusyException())
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.search()

        assertEquals(SearchState.Error.Busy, vm.uiState.value.searchState)
    }

    @Test
    fun search_httpException_setsHttpError() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchError = LookupHttpException(500))
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.search()

        assertEquals(SearchState.Error.Http(500), vm.uiState.value.searchState)
    }

    @Test
    fun search_ioException_setsNetworkError() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchError = IOException("No route to host"))
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.search()

        assertEquals(SearchState.Error.Network, vm.uiState.value.searchState)
    }

    @Test
    fun selectCandidate_fetchesCover_success() = runBlocking {
        val coverBytes = byteArrayOf(1, 2, 3, 4)
        val candidate = Candidate(
            recordingId = "rec-1",
            title = "Imagine",
            artist = "John Lennon",
            releaseTitle = "Imagine",
            year = "1971",
            durationMs = 183_000L,
            score = 100,
            releaseIds = listOf("rel-1"),
        )

        val fakeLookup = FakeSongInfoLookup(coverBytes = coverBytes)
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.selectCandidate(candidate)

        val state = vm.uiState.value
        assertEquals(candidate, state.selectedCandidate)
        assertTrue(state.coverState is CoverState.Success)
        assertEquals(coverBytes, (state.coverState as CoverState.Success).bytes)
    }

    @Test
    fun selectCandidate_fetchesCover_notFound() = runBlocking {
        val candidate = Candidate(
            recordingId = "rec-1",
            title = "Imagine",
            artist = "John Lennon",
            releaseTitle = "Imagine",
            year = "1971",
            durationMs = 183_000L,
            score = 100,
            releaseIds = listOf("rel-1"),
        )

        val fakeLookup = FakeSongInfoLookup(coverBytes = null)
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.selectCandidate(candidate)

        val state = vm.uiState.value
        assertEquals(candidate, state.selectedCandidate)
        assertEquals(CoverState.NotFound, state.coverState)
    }

    @Test
    fun apply_savesNewCover_deletesOldCover_andUpsertsInfoRepo() = runBlocking {
        val coverBytes = byteArrayOf(10, 20, 30)
        val fakeCoverStore = FakeCoverStore()
        fakeCoverStore.save(byteArrayOf(99)) // simulates pre-existing file "saved_0.jpg"
        val oldCoverName = "saved_0.jpg"

        val fakeRepo = FakeSongInfoRepository(
            initial = mapOf(
                "Music/Sur/imagine.mp3" to SongInfoEntity(
                    key = "Music/Sur/imagine.mp3",
                    title = "Old Title",
                    artist = "Old Artist",
                    album = "Old Album",
                    coverFile = oldCoverName,
                    mbRecordingId = "old-rec",
                    status = SongInfoStatus.APPLIED,
                    updatedAt = 1000L,
                )
            )
        )

        val fakeLookup = FakeSongInfoLookup(coverBytes = coverBytes)
        val vm = createViewModel(
            song = sampleSong,
            lookup = fakeLookup,
            infoRepo = fakeRepo,
            coverStore = fakeCoverStore,
        )

        assertTrue(vm.uiState.value.hasExistingApplied)

        val candidate = Candidate(
            recordingId = "rec-new",
            title = "New Imagine",
            artist = "John Lennon",
            releaseTitle = "Imagine Remaster",
            year = "2010",
            durationMs = 183_000L,
            score = 100,
            releaseIds = listOf("rel-new"),
        )

        vm.selectCandidate(candidate)

        var completed = false
        vm.apply { completed = true }

        assertTrue(completed)
        assertTrue("Old cover must be deleted", fakeCoverStore.deletedFiles.contains(oldCoverName))

        val applied = fakeRepo.get("Music/Sur/imagine.mp3")
        assertNotNull(applied)
        assertEquals("New Imagine", applied?.title)
        assertEquals("John Lennon", applied?.artist)
        assertEquals("Imagine Remaster", applied?.album)
        assertEquals("saved_1.jpg", applied?.coverFile)
        assertEquals("rec-new", applied?.mbRecordingId)
        assertEquals(SongInfoStatus.APPLIED, applied?.status)
    }

    @Test
    fun skip_callsInfoRepoSkip() = runBlocking {
        val fakeRepo = FakeSongInfoRepository()
        val vm = createViewModel(song = sampleSong, infoRepo = fakeRepo)

        var completed = false
        vm.skip { completed = true }

        assertTrue(completed)
        val entity = fakeRepo.get("Music/Sur/imagine.mp3")
        assertNotNull(entity)
        assertEquals(SongInfoStatus.SKIPPED, entity?.status)
    }

    @Test
    fun removeFoundInfo_deletesCoverAndResetsRepo() = runBlocking {
        val coverFile = "existing_cover.jpg"
        val fakeCoverStore = FakeCoverStore()
        fakeCoverStore.files[coverFile] = File("/fake/$coverFile")

        val fakeRepo = FakeSongInfoRepository(
            initial = mapOf(
                "Music/Sur/imagine.mp3" to SongInfoEntity(
                    key = "Music/Sur/imagine.mp3",
                    title = "Title",
                    artist = "Artist",
                    album = "Album",
                    coverFile = coverFile,
                    mbRecordingId = "rec-1",
                    status = SongInfoStatus.APPLIED,
                    updatedAt = 1000L,
                )
            )
        )

        val vm = createViewModel(
            song = sampleSong,
            infoRepo = fakeRepo,
            coverStore = fakeCoverStore,
        )

        assertTrue(vm.uiState.value.hasExistingApplied)

        var completed = false
        vm.removeFoundInfo { completed = true }

        assertTrue(completed)
        assertTrue(fakeCoverStore.deletedFiles.contains(coverFile))
        assertNull(fakeRepo.get("Music/Sur/imagine.mp3"))
    }

    private fun createViewModel(
        song: Song,
        lookup: SongInfoLookup = FakeSongInfoLookup(),
        infoRepo: SongInfoRepository = FakeSongInfoRepository(),
        coverStore: CoverStore = FakeCoverStore(),
        keyResolver: SongKeyResolver = FakeSongKeyResolver("Music/Sur/imagine.mp3"),
        displayName: String? = "imagine.mp3",
    ): FindInfoViewModel {
        return FindInfoViewModel(
            song = song,
            lookup = lookup,
            infoRepository = infoRepo,
            coverStore = coverStore,
            songKeyResolver = keyResolver,
            displayNameProvider = { displayName },
            ioDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
        )
    }

    private class FakeSongInfoLookup(
        var searchResult: List<Candidate> = emptyList(),
        var searchError: Throwable? = null,
        var coverBytes: ByteArray? = null,
        var fallbackResult: List<Candidate>? = null,
    ) : SongInfoLookup {
        val searchCalls = mutableListOf<Pair<LookupQuery, Int>>()

        override suspend fun search(query: LookupQuery, limit: Int): List<Candidate> {
            searchCalls.add(query to limit)
            searchError?.let { throw it }
            if (query.artist == null) fallbackResult?.let { return it }
            return searchResult
        }

        override suspend fun fetchCover(candidate: Candidate): ByteArray? {
            return coverBytes
        }
    }

    private class FakeSongInfoRepository(
        initial: Map<String, SongInfoEntity> = emptyMap(),
    ) : SongInfoRepository {
        private val data = initial.toMutableMap()

        override suspend fun get(key: String): SongInfoEntity? = data[key]

        override fun observeAll(): Flow<List<SongInfoEntity>> = flowOf(data.values.toList())

        override suspend fun apply(
            key: String,
            title: String?,
            artist: String?,
            album: String?,
            coverFile: String?,
            mbRecordingId: String?,
        ) {
            data[key] = SongInfoEntity(
                key = key,
                title = title,
                artist = artist,
                album = album,
                coverFile = coverFile,
                mbRecordingId = mbRecordingId,
                status = SongInfoStatus.APPLIED,
                updatedAt = System.currentTimeMillis(),
            )
        }

        override suspend fun skip(key: String) {
            val existing = data[key]
            if (existing?.status == SongInfoStatus.APPLIED) return
            data[key] = SongInfoEntity(
                key = key,
                title = null,
                artist = null,
                album = null,
                coverFile = null,
                mbRecordingId = null,
                status = SongInfoStatus.SKIPPED,
                updatedAt = System.currentTimeMillis(),
            )
        }

        override suspend fun reset(key: String) {
            data.remove(key)
        }
    }

    private class FakeCoverStore : CoverStore {
        val files = mutableMapOf<String, File>()
        val deletedFiles = mutableListOf<String>()
        private var counter = 0

        override fun save(bytes: ByteArray): String {
            val name = "saved_${counter++}.jpg"
            files[name] = File("/fake/$name")
            return name
        }

        override fun file(name: String): File? = files[name]

        override fun delete(name: String): Boolean {
            deletedFiles.add(name)
            files.remove(name)
            return true
        }
    }

    private class FakeSongKeyResolver(private val key: String) : SongKeyResolver {
        override suspend fun resolveKeys(): Map<Long, String> = mapOf(100L to key)
        override suspend fun resolveKey(songId: Long): String? = if (songId == 100L) key else null
    }
}
