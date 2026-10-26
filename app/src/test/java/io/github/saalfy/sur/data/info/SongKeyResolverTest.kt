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
}
