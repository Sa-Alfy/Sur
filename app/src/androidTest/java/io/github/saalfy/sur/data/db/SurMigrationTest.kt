package io.github.saalfy.sur.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.saalfy.sur.data.info.SongIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R1-01: every supported upgrade path of sur.db keeps the user's playlists.
 * Uses the exported schemas in app/schemas (validated by Room on each step).
 *
 *   ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SurMigrationTest {

    private val dbName = "sur_migration_test"
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, SurDatabase::class.java)

    private fun seedV1() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'Road trip', 1000)")
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (2, 'Empty', 2000)")
            db.execSQL("INSERT INTO playlist_entries (id, playlistId, songId, position) VALUES (1, 1, 100, 0)")
            db.execSQL("INSERT INTO playlist_entries (id, playlistId, songId, position) VALUES (2, 1, 200, 1)")
            db.execSQL("INSERT INTO playlist_entries (id, playlistId, songId, position) VALUES (3, 1, 300, 2)")
        }
    }

    @Test
    fun v1To2_keepsRows_newKeyIsNull() {
        seedV1()
        helper.runMigrationsAndValidate(dbName, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT songId, position, songKey FROM playlist_entries ORDER BY id").use { c ->
                assertEquals(3, c.count)
                c.moveToFirst()
                assertEquals(100L, c.getLong(0))
                assertEquals(0, c.getInt(1))
                assertNull(c.getString(2))
            }
        }
    }

    @Test
    fun v2To3_keepsKeys_newColumnsAreNull() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, createdAt) VALUES (1, 'P', 1000)")
            db.execSQL("INSERT INTO playlist_entries (id, playlistId, songId, position, songKey) VALUES (1, 1, 100, 0, 'Music/a.mp3')")
        }
        helper.runMigrationsAndValidate(dbName, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT songKey, songVolume, songDurationMs, relinkedFromSongId FROM playlist_entries").use { c ->
                c.moveToFirst()
                assertEquals("Music/a.mp3", c.getString(0))
                assertNull(c.getString(1))
                assertEquals(true, c.isNull(2))
                assertEquals(true, c.isNull(3))
            }
        }
    }

    /** The released v0.3.5 database (v1) opened by the current app through the real migration list. */
    @Test
    fun v1ToCurrent_throughRoomAndDao_preservesPlaylistsAndOrder() = runBlocking {
        seedV1()
        helper.runMigrationsAndValidate(dbName, SUR_DB_VERSION, true, *SurMigrations.ALL.toTypedArray()).close()

        val db = Room.databaseBuilder(instrumentation.targetContext, SurDatabase::class.java, dbName)
            .addMigrations(*SurMigrations.ALL.toTypedArray())
            .build()
        try {
            val dao = db.playlistDao()
            assertEquals(listOf("Empty", "Road trip"), dao.observePlaylists().first().map { it.name })
            assertEquals(listOf(100L, 200L, 300L), dao.observeEntries(1).first().map { it.songId })

            // First reconcile after upgrade fills identities without moving anything.
            dao.reconcile(
                mapOf(
                    100L to SongIdentity("Music/a.mp3", "external_primary", 1000),
                    200L to SongIdentity("Music/b.mp3", "external_primary", 2000),
                    300L to SongIdentity("Music/c.mp3", "external_primary", 3000),
                ),
            )
            val entries = dao.observeEntries(1).first()
            assertEquals(listOf(100L, 200L, 300L), entries.map { it.songId })
            assertEquals(listOf(0, 1, 2), entries.map { it.position })
            assertEquals("Music/b.mp3", entries[1].songKey)
            assertEquals(2000L, entries[1].songDurationMs)
        } finally {
            db.close()
        }
    }
}
