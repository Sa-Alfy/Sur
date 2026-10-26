package io.github.saalfy.sur.data.info

import io.github.saalfy.sur.data.Song
import io.github.saalfy.sur.data.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class OverlaySongRepositoryTest {

    private val song1 = Song(id = 1L, title = "Original Title 1", artist = "Original Artist 1", album = "Original Album 1", albumId = 10L, durationMs = 120_000L)
    private val song2 = Song(id = 2L, title = "Original Title 2", artist = "Original Artist 2", album = "Original Album 2", albumId = 20L, durationMs = 180_000L)

    // --- C.1 Tests: applyOverride ---

    @Test
    fun applyOverride_nullInfo_returnsOriginalSong() {
        val result = applyOverride(song1, null)
        assertEquals(song1, result)
    }

    @Test
    fun applyOverride_skippedStatus_returnsOriginalSong() {
        val info = SongInfoEntity(
            key = "key1",
            title = "New Title",
            artist = "New Artist",
            album = "New Album",
            coverFile = "cover.jpg",
            mbRecordingId = null,
            status = SongInfoStatus.SKIPPED,
            updatedAt = 1000L,
        )
        val result = applyOverride(song1, info)
        assertEquals(song1, result)
    }

    @Test
    fun applyOverride_appliedStatus_overridesAllNonBlankFields() {
        val info = SongInfoEntity(
            key = "key1",
            title = "New Title",
            artist = "New Artist",
            album = "New Album",
            coverFile = "cover.jpg",
            mbRecordingId = null,
            status = SongInfoStatus.APPLIED,
            updatedAt = 1000L,
        )
        val result = applyOverride(song1, info)
        assertEquals("New Title", result.title)
        assertEquals("New Artist", result.artist)
        assertEquals("New Album", result.album)
        assertEquals(song1.id, result.id)
        assertEquals(song1.albumId, result.albumId)
        assertEquals(song1.durationMs, result.durationMs)
    }

    @Test
    fun applyOverride_blankOrNullFields_preserveOriginalFields() {
        val info = SongInfoEntity(
            key = "key1",
            title = "   ",
            artist = null,
            album = "",
            coverFile = null,
            mbRecordingId = null,
            status = SongInfoStatus.APPLIED,
            updatedAt = 1000L,
        )
        val result = applyOverride(song1, info)
        assertEquals("Original Title 1", result.title)
        assertEquals("Original Artist 1", result.artist)
        assertEquals("Original Album 1", result.album)
    }

    @Test
    fun applyOverride_partialOverride() {
        val info = SongInfoEntity(
            key = "key1",
            title = "Overridden Title",
            artist = null,
            album = "Overridden Album",
            coverFile = null,
            mbRecordingId = null,
            status = SongInfoStatus.APPLIED,
            updatedAt = 1000L,
        )
        val result = applyOverride(song1, info)
        assertEquals("Overridden Title", result.title)
        assertEquals("Original Artist 1", result.artist)
        assertEquals("Overridden Album", result.album)
    }

    // --- C.4 Tests: changedCoverIds ---

    @Test
    fun changedCoverIds_detectsChangesAdditionsDeletions() {
        val old = mapOf(
            1L to "a.jpg",
            2L to "same.jpg",
            3L to "removed.jpg",
            4L to null,
        )
        val new = mapOf(
            1L to "b.jpg", // changed
            2L to "same.jpg", // unchanged
            3L to null, // removed
            4L to "added.jpg", // added
            5L to "new_song.jpg", // added
        )

        val changed = changedCoverIds(old, new)
        assertEquals(setOf(1L, 3L, 4L, 5L), changed)
    }

    @Test
    fun changedCoverIds_bothEmptyOrNull_returnsEmpty() {
        assertEquals(emptySet<Long>(), changedCoverIds(emptyMap(), emptyMap()))
        assertEquals(emptySet<Long>(), changedCoverIds(mapOf(1L to null), emptyMap()))
    }

    // --- C.5 Tests: OverlaySongRepository with fakes ---

    @Test
    fun overlay_emptyInfo_returnsSongsIdenticalToDelegate() = runBlocking {
        val delegate = FakeSongRepository(listOf(song1, song2))
        val keys = FakeSongKeyResolver(mapOf(1L to "key1", 2L to "key2"))
        val info = FakeSongInfoRepository(emptyList())
        val covers = FakeCoverStore()

        val repository = OverlaySongRepository(delegate, keys, info, covers)
        val songs = repository.songs.first()

        assertEquals(listOf(song1, song2), songs)
        assertNull(repository.coverFor(1L))
    }

    @Test
    fun overlay_oneAppliedRow_onlyThatSongChanged() = runBlocking {
        val delegate = FakeSongRepository(listOf(song1, song2))
        val keys = FakeSongKeyResolver(mapOf(1L to "key1", 2L to "key2"))
        val coverFile = File.createTempFile("test_cover", ".jpg")
        try {
            val covers = FakeCoverStore().apply {
                files["custom_cover.jpg"] = coverFile
            }
            val info = FakeSongInfoRepository(
                listOf(
                    SongInfoEntity(
                        key = "key1",
                        title = "Overridden Title 1",
                        artist = "Overridden Artist 1",
                        album = null,
                        coverFile = "custom_cover.jpg",
                        mbRecordingId = null,
                        status = SongInfoStatus.APPLIED,
                        updatedAt = 1000L,
                    ),
                )
            )

            val repository = OverlaySongRepository(delegate, keys, info, covers)
            val songs = repository.songs.first()

            assertEquals(2, songs.size)
            assertEquals("Overridden Title 1", songs[0].title)
            assertEquals("Overridden Artist 1", songs[0].artist)
            assertEquals("Original Album 1", songs[0].album)

            // song2 untouched
            assertEquals(song2, songs[1])

            // coverFor checked from snapshot
            assertEquals(coverFile, repository.coverFor(1L))
            assertNull(repository.coverFor(2L))
        } finally {
            coverFile.delete()
        }
    }

    @Test
    fun overlay_infoFlowThrows_emitsPlainSongs() = runBlocking {
        val delegate = FakeSongRepository(listOf(song1, song2))
        val keys = FakeSongKeyResolver(mapOf(1L to "key1", 2L to "key2"))
        val info = FakeSongInfoRepository(emptyList(), shouldThrow = true)
        val covers = FakeCoverStore()

        val repository = OverlaySongRepository(delegate, keys, info, covers)
        val songs = repository.songs.first()

        assertEquals(listOf(song1, song2), songs)
    }

    @Test
    fun overlay_keyQueryFails_emitsPlainSongs() = runBlocking {
        val delegate = FakeSongRepository(listOf(song1, song2))
        val keys = FakeSongKeyResolver(emptyMap(), shouldThrow = true)
        val info = FakeSongInfoRepository(
            listOf(
                SongInfoEntity(
                    key = "key1",
                    title = "Overridden Title 1",
                    artist = null,
                    album = null,
                    coverFile = null,
                    mbRecordingId = null,
                    status = SongInfoStatus.APPLIED,
                    updatedAt = 1000L,
                )
            )
        )
        val covers = FakeCoverStore()

        val repository = OverlaySongRepository(delegate, keys, info, covers)
        val songs = repository.songs.first()

        assertEquals(listOf(song1, song2), songs)
    }

    private class FakeSongRepository(initialSongs: List<Song>) : SongRepository {
        val flow = MutableStateFlow(initialSongs)
        override val songs: Flow<List<Song>> = flow
        override suspend fun refresh() {}
    }

    private class FakeSongKeyResolver(
        private val keys: Map<Long, String>,
        var shouldThrow: Boolean = false,
    ) : SongKeyResolver {
        override suspend fun resolveKeys(): Map<Long, String> {
            if (shouldThrow) throw RuntimeException("Key query failed")
            return keys
        }
    }

    private class FakeSongInfoRepository(
        initialInfo: List<SongInfoEntity>,
        var shouldThrow: Boolean = false,
    ) : SongInfoRepository {
        val flow = MutableStateFlow(initialInfo)
        override suspend fun get(key: String): SongInfoEntity? = flow.value.find { it.key == key }
        override fun observeAll(): Flow<List<SongInfoEntity>> = flow {
            if (shouldThrow) throw RuntimeException("Info flow failed")
            emitAll(flow)
        }
        override suspend fun apply(
            key: String, title: String?, artist: String?, album: String?, coverFile: String?, mbRecordingId: String?
        ) {}
        override suspend fun skip(key: String) {}
        override suspend fun reset(key: String) {}
    }

    private class FakeCoverStore : CoverStore {
        val files = mutableMapOf<String, File>()
        override fun save(bytes: ByteArray): String = "cover.jpg"
        override fun file(name: String): File? = files[name]
        override fun delete(name: String): Boolean = files.remove(name) != null
    }
}
