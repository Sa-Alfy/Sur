package io.github.saalfy.sur.data.db

import org.junit.Assert.assertEquals
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
}
