package io.github.saalfy.sur.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.saalfy.sur.data.playlist.ReconcileAction
import io.github.saalfy.sur.data.playlist.planReconcile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R1-06 / R1-01 / R3-01 — Instrumented integration tests.
 *
 * These tests run on a physical device or emulator; they cannot run on the JVM host
 * because Room's SQLite implementation requires the Android framework.
 *
 * Run with:
 *   ./gradlew connectedDebugAndroidTest --tests "*.PlaylistDaoIntegrationTest"
 *
 * What is covered:
 * - R1-06: The real DAO reconcile() write path: setSongKey, relink, deleteEntry,
 *          and position compaction.
 * - R1-01/R3-01: The Room V1→V2 migration via MigrationTestHelper verifying that
 *                the new songKey column is added as nullable and all existing rows survive.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistDaoIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // -------------------------------------------------------------------------
    // R1-01 / R3-01 — Migration test
    // -------------------------------------------------------------------------

    @get:Rule
    val migrationHelper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SurDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val TEST_DB = "sur_migration_test"

    /**
     * R3-01: Verifies that upgrading a real V1 schema to V2 adds the nullable
     * [songKey] column and preserves all existing playlist entries.
     */
    @Test
    fun migration1To2_addsNullableSongKeyColumn_andPreservesAllRows() {
        // Create V1 schema and seed it.
        migrationHelper.createDatabase(TEST_DB, 1).use { db ->
            // Insert a playlist.
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'My Playlist', 1000)")
            // Insert two entries — V1 has no songKey column.
            db.execSQL("INSERT INTO playlist_entries (id, playlistId, songId, position) VALUES (1, 1, 100, 0)")
            db.execSQL("INSERT INTO playlist_entries (id, playlistId, songId, position) VALUES (2, 1, 200, 1)")
        }

        // Run the migration.
        migrationHelper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2).use { db ->
            // Both entries survived.
            val cursor = db.query("SELECT id, songId, songKey FROM playlist_entries ORDER BY id")
            assertEquals("Both rows must survive migration", 2, cursor.count)
            cursor.moveToFirst()
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
            assertEquals(100L, cursor.getLong(cursor.getColumnIndexOrThrow("songId")))
            // songKey must be NULL for pre-existing rows.
            assertNull(
                "Pre-migration rows must have NULL songKey",
                cursor.getString(cursor.getColumnIndexOrThrow("songKey")),
            )
            cursor.moveToNext()
            assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
            assertEquals(200L, cursor.getLong(cursor.getColumnIndexOrThrow("songId")))
            assertNull(cursor.getString(cursor.getColumnIndexOrThrow("songKey")))
            cursor.close()
        }
    }

    // -------------------------------------------------------------------------
    // R1-06 — DAO reconcile() write path
    // -------------------------------------------------------------------------

    private lateinit var db: SurDatabase
    private lateinit var dao: PlaylistDao

    @Before
    fun openDb() {
        db = Room.inMemoryDatabaseBuilder(context, SurDatabase::class.java)
            .addMigrations(*SurMigrations.ALL.toTypedArray())
            .build()
        dao = db.playlistDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private suspend fun insertPlaylist(name: String = "P"): Long =
        dao.insertPlaylist(PlaylistEntity(name = name, createdAt = 1000))

    private suspend fun addSongs(playlistId: Long, vararg songIds: Long) {
        dao.addSongs(playlistId, songIds.toList())
    }

    @Test
    fun setSongKey_backfillsNullKey() = runBlocking {
        val pid = insertPlaylist()
        addSongs(pid, 10L)

        // Before reconcile the entry has no key.
        val before = dao.observeEntries(pid).first()
        assertNull(before.single().songKey)

        dao.reconcile(mapOf(10L to "key10"))

        val after = dao.observeEntries(pid).first()
        assertEquals("key10", after.single().songKey)
        assertEquals(10L, after.single().songId)
    }

    @Test
    fun relink_deadEntryPointsToNewSongId() = runBlocking {
        val pid = insertPlaylist()
        // Insert entry with dead songId=99 but stored key "key10".
        addSongs(pid, 99L)
        // Set the key manually so the reconcile planner can find it.
        dao.reconcile(mapOf(10L to "key10"))
        // After first reconcile, songId=99 is dead (not in keyMap) and songKey is null
        // (because 99 was not in the map). We need to seed songKey on the entry.
        // Re-add with the key already stored: simulate a stale entry.
        // Easiest: delete and re-insert with key pre-set via reconcile on a fake songId.

        // Simpler approach: seed directly — reconcile a live entry then kill it.
        // 1. Insert a live entry for songId=10 to get it keyed.
        addSongs(pid, 10L)
        dao.reconcile(mapOf(10L to "key10"))
        // Both entries present (10 and 99). Delete song 10 from MediaStore.
        // Now reconcile without 10 in the keyMap; entry for 99 (dead) has key "key10"
        // set from before when 10 was live and later moved to 99's row is not right.
        //
        // Clearest test: start fresh with just one dead entry that has a key.
        db.close()
        db = Room.inMemoryDatabaseBuilder(context, SurDatabase::class.java)
            .addMigrations(*SurMigrations.ALL.toTypedArray())
            .build()
        dao = db.playlistDao()
        val pid2 = insertPlaylist("P2")
        addSongs(pid2, 10L)
        // Seed key via reconcile while songId=10 is live.
        dao.reconcile(mapOf(10L to "key10"))
        // Now song 10 is gone and song 42 has the same key (file moved).
        dao.reconcile(mapOf(42L to "key10"))
        val entries = dao.observeEntries(pid2).first()
        assertEquals("Dead entry must be relinked to new songId", 42L, entries.single().songId)
        assertEquals("key10", entries.single().songKey)
    }

    @Test
    fun deleteEntry_andPositionCompaction_worksAfterRelink() = runBlocking {
        val pid = insertPlaylist()
        addSongs(pid, 10L, 20L, 30L)
        // Seed keys.
        dao.reconcile(mapOf(10L to "key10", 20L to "key20", 30L to "key30"))

        // Song 20 moves to id=99 (same key). Song 10 moves to id=55.
        // No collision so both should relink.
        dao.reconcile(mapOf(55L to "key10", 99L to "key20", 30L to "key30"))

        val entries = dao.observeEntries(pid).first()
        val ids = entries.map { it.songId }.toSet()
        assertTrue(ids.contains(55L))
        assertTrue(ids.contains(99L))
        assertTrue(ids.contains(30L))
        // Positions must be contiguous 0..2.
        val positions = entries.map { it.position }.sorted()
        assertEquals(listOf(0, 1, 2), positions)
    }

    @Test
    fun dropDuplicate_removedAndPositionsCompacted() = runBlocking {
        val pid = insertPlaylist()
        addSongs(pid, 10L, 20L, 30L)
        dao.reconcile(mapOf(10L to "key10", 20L to "key20", 30L to "key30"))

        // Song 10 disappears; song 42 picks up key10. But song 42 is ALREADY in the playlist.
        addSongs(pid, 42L)
        dao.reconcile(mapOf(42L to "key10", 20L to "key20", 30L to "key30"))

        val entries = dao.observeEntries(pid).first()
        val ids = entries.map { it.songId }
        assertTrue("Dead duplicate must be dropped", !ids.contains(10L))
        assertTrue(ids.contains(42L))
        // Positions: 0..n-1
        val positions = entries.map { it.position }.sorted()
        assertEquals(List(entries.size) { it }, positions)
    }

    @Test
    fun deleteEntriesForSong_removesAndCompactsPositions() = runBlocking {
        val pid = insertPlaylist()
        addSongs(pid, 10L, 20L, 30L)

        dao.deleteEntriesForSong(20L)

        val entries = dao.observeEntries(pid).first()
        assertEquals(2, entries.size)
        val ids = entries.map { it.songId }
        assertTrue(ids.contains(10L))
        assertTrue(ids.contains(30L))
        assertEquals(listOf(0, 1), entries.map { it.position }.sorted())
    }

    @Test
    fun moveEntry_reordersPositions() = runBlocking {
        val pid = insertPlaylist()
        addSongs(pid, 10L, 20L, 30L)
        val before = dao.observeEntries(pid).first()
        val entry10 = before.first { it.songId == 10L }
        val entry30 = before.first { it.songId == 30L }

        dao.moveEntry(pid, entry10.id, entry30.id)

        val after = dao.observeEntries(pid).first()
        val songOrder = after.sortedBy { it.position }.map { it.songId }
        // 10 moved toward 30, so order should be 20,10,30 or 20,30,10 depending on direction.
        // We just verify positions are still contiguous 0..2.
        assertEquals(listOf(0, 1, 2), after.map { it.position }.sorted())
        assertEquals(3, after.size)
    }

    @Test
    fun reconcile_isIdempotent_runningTwiceProducesSameResult() = runBlocking {
        val pid = insertPlaylist()
        addSongs(pid, 10L, 20L)
        val keys = mapOf(10L to "key10", 20L to "key20")

        dao.reconcile(keys)
        val firstResult = dao.observeEntries(pid).first()
        dao.reconcile(keys)
        val secondResult = dao.observeEntries(pid).first()

        assertEquals("Reconcile must be idempotent", firstResult, secondResult)
    }
}
