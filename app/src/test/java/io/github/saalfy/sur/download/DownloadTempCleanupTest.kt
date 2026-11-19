package io.github.saalfy.sur.download

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadTempCleanupTest {

    @Test
    fun removesOnlyLeftoverDownloadTemps() {
        val dir = Files.createTempDirectory("cache").toFile()
        try {
            listOf(
                "download_1700000000000_raw.m4a",
                "download_1700000000000_remux.m4a",
                "download_5_raw.m4a",
                "image_cache.bin",
                "download_notes.txt",
                "download_x_raw.m4a",
            ).forEach { File(dir, it).writeText("x") }
            File(dir, "download_9_raw.m4a").mkdir() // a directory is never touched

            assertEquals(3, deleteStaleDownloadTemps(dir))
            assertEquals(
                setOf("image_cache.bin", "download_notes.txt", "download_x_raw.m4a", "download_9_raw.m4a"),
                dir.list()!!.toSet(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
