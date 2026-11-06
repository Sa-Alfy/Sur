package io.github.saalfy.sur.data.info

import androidx.room.migration.Migration

object SurInfoMigrations {
    /**
     * No migrations yet: SurInfoDatabase is still at version 1. The empty list
     * is wired into the builder so the next schema change only has to add a
     * Migration here. Never use fallbackToDestructiveMigration: it would
     * silently delete the user's song info.
     */
    val ALL: List<Migration> = emptyList()
}
