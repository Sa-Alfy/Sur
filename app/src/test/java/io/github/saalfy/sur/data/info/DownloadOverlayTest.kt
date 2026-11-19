package io.github.saalfy.sur.data.info

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R2-01 / cover precedence: exercises the production [applyDownloadOverlay] that
 * DownloadService calls, against the production [RoomSongInfoRepository].
 */
class DownloadOverlayTest {

    private val key = "Music/Sur/song.m4a"
    private val thumb = byteArrayOf(1, 2, 3)

    private class FakeDao : SongInfoDao {
        val rows = mutableMapOf<String, SongInfoEntity>()
        var failGet: Exception? = null
        var failUpsert = false
        override suspend fun get(key: String): SongInfoEntity? {
            failGet?.let { throw it }
            return rows[key]
        }
        override fun observeAll(): Flow<List<SongInfoEntity>> = flowOf(rows.values.toList())
        override suspend fun upsert(entity: SongInfoEntity) {
            if (failUpsert) throw IOException("disk full")
            rows[entity.key] = entity
        }
        override suspend fun delete(key: String) { rows.remove(key) }
    }

    private class FakeCovers : CoverStore {
        val files = mutableSetOf<String>()
        var saves = 0
        override fun save(bytes: ByteArray): String = "thumb-${++saves}.jpg".also { files += it }
        override fun file(name: String): File? = null
        override fun delete(name: String): Boolean = files.remove(name)
    }

    private val dao = FakeDao()
    private val repo = RoomSongInfoRepository(dao)
    private val covers = FakeCovers()

    private fun row(
        status: SongInfoStatus = SongInfoStatus.APPLIED,
        title: String? = "User Title",
        artist: String? = "User Artist",
        album: String? = "User Album",
        cover: String? = "user-cover.jpg",
        mbId: String? = "mb-123",
    ) = SongInfoEntity(key, title, artist, album, cover, mbId, status, 1L).also { dao.rows[key] = it }

    private fun run(thumbnail: ByteArray? = thumb) = runBlocking {
        applyDownloadOverlay(repo, covers, key, "Provider Title", "Provider Artist", thumbnail)
    }

    @Test
    fun noRow_appliesProviderDataAndThumbnail() {
        assertTrue(run())
        val r = dao.rows.getValue(key)
        assertEquals("Provider Title", r.title)
        assertEquals("Provider Artist", r.artist)
        assertNull(r.album)
        assertNull(r.mbRecordingId)
        assertEquals("thumb-1.jpg", r.coverFile)
        assertEquals(SongInfoStatus.APPLIED, r.status)
    }

    @Test
    fun appliedRow_redownloadKeepsAllUserTextAndMbId() {
        row()
        assertTrue(run())
        val r = dao.rows.getValue(key)
        assertEquals("User Title", r.title)
        assertEquals("User Artist", r.artist)
        assertEquals("User Album", r.album)
        assertEquals("mb-123", r.mbRecordingId)
    }

    @Test
    fun appliedRow_userCoverSurvivesAndThumbnailIsNotSaved() {
        row()
        run()
        assertEquals("user-cover.jpg", dao.rows.getValue(key).coverFile)
        assertEquals("no orphan thumbnail written", 0, covers.saves)
    }

    @Test
    fun appliedRowWithoutCover_thumbnailFillsTheGap() {
        row(cover = null)
        run()
        val r = dao.rows.getValue(key)
        assertEquals("thumb-1.jpg", r.coverFile)
        assertEquals("User Title", r.title)
    }

    @Test
    fun appliedRowWithBlankTitle_providerFillsOnlyTheMissingField() {
        row(title = null, artist = " ")
        run()
        val r = dao.rows.getValue(key)
        assertEquals("Provider Title", r.title)
        assertEquals("Provider Artist", r.artist)
        assertEquals("User Album", r.album)
        assertEquals("mb-123", r.mbRecordingId)
    }

    @Test
    fun repeatedDownloads_areStable() {
        row(cover = null)
        run(); run(); run()
        val r = dao.rows.getValue(key)
        assertEquals("thumb-1.jpg", r.coverFile)
        assertEquals(1, covers.saves)
        assertEquals("User Title", r.title)
        assertEquals("mb-123", r.mbRecordingId)
    }

    @Test
    fun skippedRow_isLeftUntouched() {
        val skipped = row(status = SongInfoStatus.SKIPPED, title = null, artist = null, album = null, cover = null, mbId = null)
        assertFalse(run())
        assertEquals(skipped, dao.rows.getValue(key))
        assertEquals(0, covers.saves)
    }

    @Test
    fun unreadableRow_writesNothing() {
        row()
        dao.failGet = IOException("db locked")
        assertFalse(run())
        assertEquals("User Title", dao.rows.getValue(key).title)
        assertEquals(0, covers.saves)
    }

    @Test
    fun failedWrite_removesTheNewThumbnailAndRethrows() {
        dao.failUpsert = true
        try {
            run()
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertTrue("thumbnail cleaned up", covers.files.isEmpty())
    }

    @Test
    fun cancellationDuringRead_propagates() {
        row()
        dao.failGet = CancellationException("cancelled")
        try {
            run()
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertEquals("User Title", dao.rows.getValue(key).title)
    }

    @Test
    fun noThumbnail_keepsNullCover() {
        assertTrue(run(thumbnail = null))
        assertNull(dao.rows.getValue(key).coverFile)
    }
}
