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

    private val buryAFriendSong = Song(
        id = 200L,
        title = "Billie Eilish - bury a friend",
        artist = "Bille Eilish",
        album = null,
        albumId = 0L,
        durationMs = 193_000L,
    )

    @Test
    fun search_strictEmpty_fallsBackToTitleOnly_andRanksMatchingArtistFirst() = runBlocking {
        val coverVersion = Candidate("rec-cover", "bury a friend", "Some Cover Band", null, null, 193_000L, 100, emptyList())
        val original = Candidate("rec-real", "bury a friend", "Billie Eilish", null, null, 193_000L, 95, emptyList())
        val fakeLookup = FakeSongInfoLookup(
            searchResult = emptyList(),
            fallbackResult = listOf(coverVersion, original),
        )
        val vm = createViewModel(
            song = buryAFriendSong,
            lookup = fakeLookup,
            displayName = "Bille Eilish - Billie Eilish - bury a friend.m4a",
        )
        assertEquals("bury a friend", vm.uiState.value.titleInput)
        assertEquals("Billie Eilish", vm.uiState.value.artistInput)

        vm.search()

        assertEquals(2, fakeLookup.searchCalls.size)
        assertEquals(LookupQuery("bury a friend", "Billie Eilish", 193_000L) to 5, fakeLookup.searchCalls[0])
        assertEquals(LookupQuery("bury a friend", null, 193_000L) to 25, fakeLookup.searchCalls[1])
        val state = vm.uiState.value
        assertEquals(SearchState.Success, state.searchState)
        assertEquals("rec-real", state.candidates[0].recordingId)
    }

    @Test
    fun search_strictFindsResults_noFallback() = runBlocking {
        val original = Candidate("rec-real", "bury a friend", "Billie Eilish", null, null, 193_000L, 100, emptyList())
        val fakeLookup = FakeSongInfoLookup(
            searchResult = listOf(original),
            fallbackResult = emptyList(),
        )
        val vm = createViewModel(song = buryAFriendSong, lookup = fakeLookup)

        vm.search()

        assertEquals(1, fakeLookup.searchCalls.size)
        assertEquals("Billie Eilish", fakeLookup.searchCalls[0].first.artist)
        assertEquals(SearchState.Success, vm.uiState.value.searchState)
    }

    @Test
    fun search_bothEmpty_reportsBothSearchesTried() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchResult = emptyList())
        val vm = createViewModel(song = buryAFriendSong, lookup = fakeLookup)

        vm.search()

        assertEquals(2, fakeLookup.searchCalls.size)
        assertEquals(SearchState.Empty, vm.uiState.value.searchState)
        assertTrue(vm.uiState.value.searchTriedArtist)
    }

    // --- 9-4A: auto search, preselect, cover only, free text ---

    private val imagine = Candidate("rec-1", "Imagine", "John Lennon", "Imagine", "1971", 183_000L, 100, listOf("rel-1"))
    private val imagineLive = Candidate("rec-2", "Imagine (live)", "John Lennon", "Live", "1986", 190_000L, 90, listOf("rel-2"))

    @Test
    fun autoSearch_runsOnceWithFirstVariant_andNotBeforeItIsStarted() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchResult = listOf(imagine))
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)
        assertTrue(fakeLookup.searchCalls.isEmpty())

        vm.startAutoSearch()
        vm.startAutoSearch()

        assertEquals(1, fakeLookup.searchCalls.size)
        assertEquals(LookupQuery("Imagine", "John Lennon", 183_000L), fakeLookup.searchCalls[0].first)
    }

    @Test
    fun autoSearch_usesTitleOnlyFallback() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchResult = emptyList(), fallbackResult = listOf(imagine))
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.startAutoSearch()

        assertEquals(2, fakeLookup.searchCalls.size)
        assertNull(fakeLookup.searchCalls[1].first.artist)
        assertEquals(SearchState.Success, vm.uiState.value.searchState)
    }

    @Test
    fun findInfoStep_sheetOnlyAfterConsent() {
        assertEquals(FindInfoStep.None, findInfoStep(requested = false, consentGiven = false))
        assertEquals(FindInfoStep.None, findInfoStep(requested = false, consentGiven = true))
        assertEquals(FindInfoStep.Consent, findInfoStep(requested = true, consentGiven = false))
        assertEquals(FindInfoStep.Sheet, findInfoStep(requested = true, consentGiven = true))
    }

    @Test
    fun search_preselectsBestCandidate_andRequestsItsCover() = runBlocking {
        val coverBytes = byteArrayOf(1, 2, 3)
        val fakeLookup = FakeSongInfoLookup(searchResult = listOf(imagineLive, imagine), coverBytes = coverBytes)
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.startAutoSearch()

        val state = vm.uiState.value
        assertEquals(imagine, state.selectedCandidate)
        assertEquals(listOf(imagine), fakeLookup.coverRequests)
        assertEquals(CoverState.Success(coverBytes), state.coverState)
    }

    @Test
    fun coverOnly_keepsEarlierTitleArtistAlbum_withCoverFile() = runBlocking {
        val fakeRepo = FakeSongInfoRepository()
        fakeRepo.put(
            "Music/Sur/imagine.mp3",
            SongInfoEntity(
                key = "Music/Sur/imagine.mp3",
                title = "Earlier Title",
                artist = "Earlier Artist",
                album = "Earlier Album",
                coverFile = "old.jpg",
                mbRecordingId = "old-rec",
                status = SongInfoStatus.APPLIED,
                updatedAt = 1000L,
            )
        )
        val fakeLookup = FakeSongInfoLookup(searchResult = listOf(imagine), coverBytes = byteArrayOf(9, 9))
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup, infoRepo = fakeRepo)
        vm.startAutoSearch()

        var done = false
        vm.applyCoverOnly { done = true }

        assertTrue(done)
        val saved = fakeRepo.get("Music/Sur/imagine.mp3")
        assertNotNull(saved)
        assertEquals("Earlier Title", saved!!.title)
        assertEquals("Earlier Artist", saved.artist)
        assertEquals("Earlier Album", saved.album)
        assertEquals("saved_0.jpg", saved.coverFile)
        assertEquals("rec-1", saved.mbRecordingId)
    }

    @Test
    fun coverlessCandidate_keepsOldCover_andDoesNotDelete() = runBlocking {
        val oldCover = "old.jpg"
        val fakeCoverStore = FakeCoverStore().apply { files[oldCover] = File("/fake/$oldCover") }
        val fakeRepo = FakeSongInfoRepository(
            initial = mapOf(
                "Music/Sur/imagine.mp3" to SongInfoEntity(
                    key = "Music/Sur/imagine.mp3",
                    title = "Old Title",
                    artist = "Old Artist",
                    album = "Old Album",
                    coverFile = oldCover,
                    mbRecordingId = "old-rec",
                    status = SongInfoStatus.APPLIED,
                    updatedAt = 1000L,
                )
            )
        )
        val fakeLookup = FakeSongInfoLookup(searchResult = listOf(imagine), coverBytes = null)
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup, infoRepo = fakeRepo, coverStore = fakeCoverStore)
        vm.startAutoSearch()

        var done = false
        vm.apply { done = true }

        assertTrue(done)
        assertEquals(oldCover, fakeRepo.get("Music/Sur/imagine.mp3")?.coverFile)
        assertTrue(fakeCoverStore.deletedFiles.isEmpty())
    }

    @Test
    fun parseFreeText_artistDashTitle_andTitleOnly() {
        val split = FindInfoViewModel.parseFreeText("billie eilish - bury a friend")!!
        assertEquals("bury a friend", split.title)
        assertTrue("Billie Eilish".equals(split.artist, ignoreCase = true))

        val titleOnly = FindInfoViewModel.parseFreeText("bury a friend")!!
        assertEquals("bury a friend", titleOnly.title)
        assertNull(titleOnly.artist)

        assertNull(FindInfoViewModel.parseFreeText("  - _ "))
    }

    @Test
    fun searchFreeText_searchesParsedQuery() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchResult = listOf(imagine))
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)

        vm.onFreeTextChanged("billie eilish - bury a friend")
        vm.searchFreeText()

        val query = fakeLookup.searchCalls.single().first
        assertEquals("bury a friend", query.title)
        assertTrue("Billie Eilish".equals(query.artist, ignoreCase = true))
    }

    @Test
    fun selectingAnotherCandidate_swapsMatch_andRequestsItsCover() = runBlocking {
        val fakeLookup = FakeSongInfoLookup(searchResult = listOf(imagine, imagineLive), coverBytes = byteArrayOf(1))
        val vm = createViewModel(song = sampleSong, lookup = fakeLookup)
        vm.startAutoSearch()
        assertEquals(imagine, vm.uiState.value.selectedCandidate)

        vm.selectCandidate(imagineLive)

        assertEquals(imagineLive, vm.uiState.value.selectedCandidate)
        assertEquals(listOf(imagine, imagineLive), fakeLookup.coverRequests)
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
    fun apply_whenCoverSaveFails_doesNotApplyOrDelete_andShowsError() = runBlocking {
        val oldCover = "old.jpg"
        val fakeCoverStore = FakeCoverStore(saveError = IOException("disk full")).apply {
            files[oldCover] = File("/fake/$oldCover")
        }
        val fakeRepo = RecordingSongInfoRepository(
            SongInfoEntity(
                key = "Music/Sur/imagine.mp3",
                title = "Old Title",
                artist = "Old Artist",
                album = "Old Album",
                coverFile = oldCover,
                mbRecordingId = "old-rec",
                status = SongInfoStatus.APPLIED,
                updatedAt = 1000L,
            )
        )
        val vm = createViewModel(
            song = sampleSong,
            lookup = FakeSongInfoLookup(searchResult = listOf(imagine), coverBytes = byteArrayOf(1)),
            infoRepo = fakeRepo,
            coverStore = fakeCoverStore,
        )
        vm.startAutoSearch()

        vm.apply { error("apply should not complete") }

        assertFalse(fakeRepo.applyCalled)
        assertTrue(fakeCoverStore.deletedFiles.isEmpty())
        assertEquals(SearchState.Error.CoverSaveFailed, vm.uiState.value.searchState)
        assertFalse(vm.uiState.value.isApplying)
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

        val coverRequests = mutableListOf<Candidate>()

        override suspend fun fetchCover(candidate: Candidate): ByteArray? {
            coverRequests.add(candidate)
            return coverBytes
        }
    }

    private open class FakeSongInfoRepository(
        initial: Map<String, SongInfoEntity> = emptyMap(),
    ) : SongInfoRepository {
        private val data = initial.toMutableMap()

        fun put(key: String, entity: SongInfoEntity) {
            data[key] = entity
        }

        override suspend fun get(key: String): SongInfoEntity? = data[key]

        override fun observeAll(): Flow<List<SongInfoEntity>> = flowOf(data.values.toList())

        override open suspend fun apply(
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

    private class FakeCoverStore(
        private val saveError: Throwable? = null,
    ) : CoverStore {
        val files = mutableMapOf<String, File>()
        val deletedFiles = mutableListOf<String>()
        private var counter = 0

        override fun save(bytes: ByteArray): String {
            saveError?.let { throw it }
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

    private class RecordingSongInfoRepository(initial: SongInfoEntity) : FakeSongInfoRepository(
        initial = mapOf(initial.key to initial),
    ) {
        var applyCalled = false

        override suspend fun apply(
            key: String,
            title: String?,
            artist: String?,
            album: String?,
            coverFile: String?,
            mbRecordingId: String?,
        ) {
            applyCalled = true
            super.apply(key, title, artist, album, coverFile, mbRecordingId)
        }
    }

    private class FakeSongKeyResolver(private val key: String) : SongKeyResolver {
        override suspend fun resolveKeys(): Map<Long, String> = mapOf(100L to key)
        override suspend fun resolveKey(songId: Long): String? = if (songId == 100L) key else null
    }
}
