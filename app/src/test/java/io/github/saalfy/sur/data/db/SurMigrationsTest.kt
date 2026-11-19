package io.github.saalfy.sur.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurMigrationsTest {

    @Test
    fun migrationsAreContiguousFromOneToCurrentVersion() {
        assertTrue(SurMigrations.ALL.isNotEmpty())
        assertEquals(1, SurMigrations.ALL.first().startVersion)
        var expectedStart = 1
        for (migration in SurMigrations.ALL) {
            assertEquals(expectedStart, migration.startVersion)
            expectedStart = migration.endVersion
        }
        assertEquals(SUR_DB_VERSION, SurMigrations.ALL.last().endVersion)
    }

    @Test
    fun migration1_2MovesVersion1To2() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
    }

    @Test
    fun migration2_3MovesVersion2To3() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    private fun schema(db: String, version: Int) = File("schemas/io.github.saalfy.sur.data.$db/$version.json")

    private fun columns(json: File): Set<String> =
        Regex(""""columnName": "(\w+)"""").findAll(json.readText()).map { it.groupValues[1] }.toSet()

    @Test
    fun everySchemaVersionIsExported() {
        for (v in 1..SUR_DB_VERSION) assertTrue("missing $v.json", schema("db.SurDatabase", v).isFile)
        assertTrue(schema("info.SurInfoDatabase", 1).isFile)
    }

    @Test
    fun v3AddsExactlyTheMigratedColumns() {
        val added = columns(schema("db.SurDatabase", 3)) - columns(schema("db.SurDatabase", 2))
        assertEquals(setOf("songVolume", "songDurationMs", "relinkedFromSongId"), added)
        assertTrue(columns(schema("db.SurDatabase", 3)).containsAll(columns(schema("db.SurDatabase", 2))))
    }

    /** I5: no build path may silently wipe user data on a failed upgrade. */
    @Test
    fun noDestructiveMigrationFallbackInSources() {
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { Regex("""\.fallbackToDestructiveMigration""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
        assertFalse(File("src/main").walkTopDown().none { it.extension == "kt" })
    }
}
