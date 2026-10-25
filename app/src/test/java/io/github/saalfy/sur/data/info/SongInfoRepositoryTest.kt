package io.github.saalfy.sur.data.info

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SongInfoRepositoryTest {

    private class FakeSongInfoDao : SongInfoDao {
        val storage = mutableMapOf<String, SongInfoEntity>()

        override suspend fun get(key: String): SongInfoEntity? = storage[key]

        override fun observeAll(): Flow<List<SongInfoEntity>> = flowOf(storage.values.toList())

        override suspend fun upsert(entity: SongInfoEntity) {
            storage[entity.key] = entity
        }

        override suspend fun delete(key: String) {
            storage.remove(key)
        }
    }

    @Test
    fun skip_onAppliedRow_leavesItAppliedWithSameTitleAndCover() = runBlocking {
        val dao = FakeSongInfoDao()
        val repository = RoomSongInfoRepository(dao)

        val applied = SongInfoEntity(
            key = "key-applied",
            title = "Applied Title",
            artist = "Applied Artist",
            album = "Applied Album",
            coverFile = "covers/key-applied.jpg",
            mbRecordingId = "mb-123",
            status = SongInfoStatus.APPLIED,
            updatedAt = 1000L,
        )
        dao.upsert(applied)

        repository.skip("key-applied")

        val result = repository.get("key-applied")
        assertNotNull(result)
        assertEquals(SongInfoStatus.APPLIED, result?.status)
        assertEquals("Applied Title", result?.title)
        assertEquals("Applied Artist", result?.artist)
        assertEquals("covers/key-applied.jpg", result?.coverFile)
        assertEquals(1000L, result?.updatedAt)
    }

    @Test
    fun skip_onNoRow_createsSkippedRow() = runBlocking {
        val dao = FakeSongInfoDao()
        val repository = RoomSongInfoRepository(dao)

        repository.skip("key-new")

        val result = repository.get("key-new")
        assertNotNull(result)
        assertEquals(SongInfoStatus.SKIPPED, result?.status)
        assertEquals("key-new", result?.key)
        assertNull(result?.title)
        assertNull(result?.artist)
        assertNull(result?.coverFile)
    }
}
