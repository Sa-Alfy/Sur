package io.github.saalfy.sur.data.info

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongInfoDao {
    @Query("SELECT * FROM song_info WHERE `key` = :key")
    suspend fun get(key: String): SongInfoEntity?

    @Query("SELECT * FROM song_info ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SongInfoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SongInfoEntity)

    @Query("DELETE FROM song_info WHERE `key` = :key")
    suspend fun delete(key: String)
}
