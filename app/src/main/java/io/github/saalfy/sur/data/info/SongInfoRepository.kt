package io.github.saalfy.sur.data.info

import kotlinx.coroutines.flow.Flow

interface SongInfoRepository {
    suspend fun get(key: String): SongInfoEntity?
    fun observeAll(): Flow<List<SongInfoEntity>>
    suspend fun apply(
        key: String,
        title: String?,
        artist: String?,
        album: String?,
        coverFile: String?,
        mbRecordingId: String?,
    )
    suspend fun skip(key: String)
    suspend fun reset(key: String)
}

class RoomSongInfoRepository(
    private val dao: SongInfoDao,
) : SongInfoRepository {
    override suspend fun get(key: String): SongInfoEntity? = dao.get(key)

    override fun observeAll(): Flow<List<SongInfoEntity>> = dao.observeAll()

    override suspend fun apply(
        key: String,
        title: String?,
        artist: String?,
        album: String?,
        coverFile: String?,
        mbRecordingId: String?,
    ) {
        val entity = SongInfoEntity(
            key = key,
            title = title,
            artist = artist,
            album = album,
            coverFile = coverFile,
            mbRecordingId = mbRecordingId,
            status = SongInfoStatus.APPLIED,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
    }

    override suspend fun skip(key: String) {
        val existing = dao.get(key)
        val entity = existing?.copy(
            status = SongInfoStatus.SKIPPED,
            updatedAt = System.currentTimeMillis(),
        ) ?: SongInfoEntity(
            key = key,
            title = null,
            artist = null,
            album = null,
            coverFile = null,
            mbRecordingId = null,
            status = SongInfoStatus.SKIPPED,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
    }

    override suspend fun reset(key: String) {
        dao.delete(key)
    }
}
