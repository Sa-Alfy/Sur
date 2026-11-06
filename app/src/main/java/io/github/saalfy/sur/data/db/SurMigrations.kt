package io.github.saalfy.sur.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 -> v2: record the stable song key for each playlist entry. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE playlist_entries ADD COLUMN songKey TEXT")
    }
}

object SurMigrations {
    /**
     * Every migration, in version order. The list must be contiguous from 1
     * to [SUR_DB_VERSION] (see SurMigrationsTest). Never add
     * fallbackToDestructiveMigration: it would silently delete the user's playlists.
     */
    val ALL: List<Migration> = listOf(MIGRATION_1_2)
}
