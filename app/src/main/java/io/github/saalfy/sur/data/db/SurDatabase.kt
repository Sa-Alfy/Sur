package io.github.saalfy.sur.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistEntryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SurDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        fun build(context: Context): SurDatabase =
            Room.databaseBuilder(context.applicationContext, SurDatabase::class.java, "sur.db").build()
    }
}
