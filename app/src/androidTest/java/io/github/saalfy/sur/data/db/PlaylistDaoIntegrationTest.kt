package io.github.saalfy.sur.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.saalfy.sur.data.info.SongIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R1-06: the real PlaylistDao write paths (SQL, transactions, position compaction) on Room.
 *
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PlaylistDaoIntegrationTest {

    private lateinit var db: SurDatabase
    private lateinit var dao: PlaylistDao

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            SurDatabase::class.java,
        ).build()
        dao = db.playlistDao()
    }

    @After
    fun closeDb() = db.close()

    private fun id(key: String, volume: String? = "external_primary", duration: Long? = 200_000L) =
        SongIdentity(key, volume, duration)

    private suspend fun playlist(vararg songIds: Long): Long {
        val pid = dao.insertPlaylist(PlaylistEntity(name = "P", createdAt = 1000))
        dao.addSongs(pid, songIds.toList())
        return pid
    }

    private suspend fun entries(pid: Long) = dao.observeEntries(pid).first()

    @Test
    fun reconcile_backfillsFullIdentity() = runBlocking {
        val pid = playlist(10)
        assertNull(entries(pid).single().songKey)
        dao.reconcile(mapOf(10L to id("k10")))
        val e = entries(pid).single()
        assertEquals("k10", e.songKey)
        assertEquals("external_primary", e.songVolume)
        assertEquals(200_000L, e.songDurationMs)
        assertNull(e.relinkedFromSongId)
    }

    @Test
    fun reconcile_relinksMovedSong_keepsPositionAndOriginalId() = runBlocking {
        val pid = playlist(10, 20, 30)
        dao.reconcile(mapOf(10L to id("k10"), 20L to id("k20"), 30L to id("k30")))
        // MediaStore re-indexed song 20 as 99.
        dao.reconcile(mapOf(10L to id("k10"), 99L to id("k20", duration = 200_300), 30L to id("k30")))
        val list = entries(pid)
        assertEquals(listOf(10L, 99L, 30L), list.map { it.songId })
        assertEquals(listOf(0, 1, 2), list.map { it.position })
        assertEquals(20L, list[1].relinkedFromSongId)
        // A second re-index keeps the first original id (COALESCE).
        dao.reconcile(mapOf(10L to id("k10"), 77L to id("k20"), 30L to id("k30")))
        assertEquals(77L, entries(pid)[1].songId)
        assertEquals(20L, entries(pid)[1].relinkedFromSongId)
    }

    @Test
    fun reconcile_reusedPathWithOtherLength_staysUnresolved() = runBlocking {
        val pid = playlist(10)
        dao.reconcile(mapOf(10L to id("Music/a.mp3", duration = 200_000)))
        dao.reconcile(mapOf(55L to id("Music/a.mp3", duration = 150_000)))
        assertEquals(10L, entries(pid).single().songId)
    }

    @Test
    fun reconcile_otherVolume_staysUnresolved() = runBlocking {
        val pid = playlist(10)
        dao.reconcile(mapOf(10L to id("Music/a.mp3", "1234-abcd")))
        dao.reconcile(mapOf(55L to id("Music/a.mp3", "external_primary")))
        assertEquals(10L, entries(pid).single().songId)
    }

    @Test
    fun reconcile_dropsDuplicate_andCompactsPositions() = runBlocking {
        val pid = playlist(10, 20, 30)
        dao.reconcile(mapOf(10L to id("k10"), 20L to id("k20"), 30L to id("k30")))
        dao.addSongs(pid, listOf(42L))
        // Song 10 is gone; 42 (already in the playlist) now carries its identity.
        dao.reconcile(mapOf(42L to id("k10"), 20L to id("k20"), 30L to id("k30")))
        val list = entries(pid)
        assertEquals(listOf(20L, 30L, 42L), list.map { it.songId })
        assertEquals(listOf(0, 1, 2), list.map { it.position })
    }

    @Test
    fun reconcile_isIdempotent() = runBlocking {
        val pid = playlist(10, 20)
        val ids = mapOf(10L to id("k10"), 20L to id("k20"))
        dao.reconcile(ids)
        val first = entries(pid)
        dao.reconcile(ids)
        assertEquals(first, entries(pid))
    }

    @Test
    fun deleteEntriesForSong_removesFromEveryPlaylist_andCompacts() = runBlocking {
        val a = playlist(10, 20, 30)
        val b = playlist(20, 40)
        dao.deleteEntriesForSong(20)
        assertEquals(listOf(10L, 30L), entries(a).map { it.songId })
        assertEquals(listOf(0, 1), entries(a).map { it.position })
        assertEquals(listOf(40L), entries(b).map { it.songId })
        assertEquals(listOf(0), entries(b).map { it.position })
    }

    @Test
    fun moveEntry_movesFirstToLast() = runBlocking {
        val pid = playlist(10, 20, 30)
        val before = entries(pid)
        dao.moveEntry(pid, before[0].id, before[2].id)
        assertEquals(listOf(20L, 30L, 10L), entries(pid).map { it.songId })
        assertEquals(listOf(0, 1, 2), entries(pid).map { it.position })
    }

    @Test
    fun addSongs_skipsDuplicates() = runBlocking {
        val pid = playlist(10, 20)
        dao.addSongs(pid, listOf(20, 30))
        assertEquals(listOf(10L, 20L, 30L), entries(pid).map { it.songId })
        assertEquals(listOf(0, 1, 2), entries(pid).map { it.position })
    }
}
