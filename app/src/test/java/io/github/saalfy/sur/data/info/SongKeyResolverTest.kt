package io.github.saalfy.sur.data.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SongKeyResolverTest {

    @Test
    fun relativeFromAbsolute_stripsStorageEmulatedPrefix() {
        assertEquals(
            "Music/Sur/a.m4a",
            relativeFromAbsolute("/storage/emulated/0/Music/Sur/a.m4a"),
        )
    }

    @Test
    fun relativeFromAbsolute_stripsVolumeIdPrefix() {
        assertEquals(
            "Music/b.mp3",
            relativeFromAbsolute("/storage/1234-ABCD/Music/b.mp3"),
        )
    }

    @Test
    fun relativeFromAbsolute_stripsSdcardPrefix() {
        assertEquals(
            "Music/c.mp3",
            relativeFromAbsolute("/sdcard/Music/c.mp3"),
        )
    }

    @Test
    fun relativeFromAbsolute_stripsMntSdcardPrefix() {
        assertEquals(
            "Music/c.mp3",
            relativeFromAbsolute("/mnt/sdcard/Music/c.mp3"),
        )
    }

    @Test
    fun relativeFromAbsolute_leavesUnmatchedPathUnchanged() {
        assertEquals(
            "/data/x/y.mp3",
            relativeFromAbsolute("/data/x/y.mp3"),
        )
    }

    @Test
    fun sameDisplayNameInDifferentFolders_produceDifferentKeys() {
        val rel1 = relativeFromAbsolute("/storage/emulated/0/Music/FolderA/song.mp3")
        val rel2 = relativeFromAbsolute("/storage/emulated/0/Music/FolderB/song.mp3")
        val key1 = SongInfoKey.buildKey(rel1, "song.mp3")
        val key2 = SongInfoKey.buildKey(rel2, "song.mp3")

        assertEquals("Music/FolderA/song.mp3", key1)
        assertEquals("Music/FolderB/song.mp3", key2)
        assertNotEquals(key1, key2)
    }

    @Test
    fun resolveKey_mapsSingleIdFromResolver() = kotlinx.coroutines.runBlocking {
        val resolver = object : SongKeyResolver {
            override suspend fun resolveKeys(): Map<Long, String> = mapOf(
                42L to "Music/SongA.mp3",
                99L to "Music/SongB.m4a",
            )
        }

        assertEquals("Music/SongA.mp3", resolver.resolveKey(42L))
        assertEquals("Music/SongB.m4a", resolver.resolveKey(99L))
        org.junit.Assert.assertNull(resolver.resolveKey(100L))
    }

    @Test
    fun volumeFromAbsolute_matchesAndroid10VolumeNames() {
        assertEquals(PRIMARY_VOLUME, volumeFromAbsolute("/storage/emulated/0/Music/a.mp3"))
        assertEquals(PRIMARY_VOLUME, volumeFromAbsolute("/sdcard/Music/a.mp3"))
        assertEquals(PRIMARY_VOLUME, volumeFromAbsolute("/mnt/sdcard/Music/a.mp3"))
        assertEquals("1234-abcd", volumeFromAbsolute("/storage/1234-ABCD/Music/a.mp3"))
        org.junit.Assert.assertNull(volumeFromAbsolute("/storage/self/primary/Music/a.mp3"))
        org.junit.Assert.assertNull(volumeFromAbsolute("/data/local/a.mp3"))
    }

    @Test
    fun sameRelativePathOnTwoVolumes_sameKeyButDifferentVolume() {
        val a = "/storage/emulated/0/Music/a.mp3"
        val b = "/storage/1234-ABCD/Music/a.mp3"
        assertEquals(relativeFromAbsolute(a), relativeFromAbsolute(b))
        assertNotEquals(volumeFromAbsolute(a), volumeFromAbsolute(b))
    }
}
