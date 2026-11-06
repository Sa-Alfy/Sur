package io.github.saalfy.sur.data.info

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SongInfoEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SurInfoDatabase : RoomDatabase() {
    abstract fun songInfoDao(): SongInfoDao

    companion object {
        fun build(context: Context): SurInfoDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SurInfoDatabase::class.java,
                "sur_info.db",
            )
                // No migrations yet (SurInfoMigrations.ALL is empty); wired so the
                // next schema change only has to add a Migration there.
                // Never use fallbackToDestructiveMigration.
                .addMigrations(*SurInfoMigrations.ALL.toTypedArray())
                .build()
    }
}
