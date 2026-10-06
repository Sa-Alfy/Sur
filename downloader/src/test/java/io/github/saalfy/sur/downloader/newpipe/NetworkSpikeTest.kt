package io.github.saalfy.sur.downloader.newpipe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real network round trip against YouTube. Skipped unless run with -Psur.networkTests=true.
 * Uses Big Buck Bunny (Blender Foundation, CC BY 3.0).
 */
class NetworkSpikeTest {

    @Test
    fun resolveAndDownloadCcVideo(): Unit = runBlocking {
        assumeTrue(System.getProperty("sur.networkTests") == "true")
        val downloader = NewPipeAudioDownloader()

        val info = downloader.resolve("https://youtu.be/aqz-KE-bpKQ?si=test")
        println("RESOLVED title='${info.title}' artist='${info.artist}' duration=${info.durationSeconds}s itag=${info.itag} bitrate=${info.bitrateKbps}kbps size=${info.contentLength}")
        assertTrue(info.durationSeconds in 60..1800)

        val target = File.createTempFile("sur-spike", ".m4a")
        var lastLogged = -1L
        downloader.download(info, target) { done, total ->
            val pct = if (total != null && total > 0) done * 100 / total else -1
            if (pct / 25 != lastLogged / 25) {
                println("PROGRESS $done/$total ($pct%)")
                lastLogged = pct
            }
        }
        println("DOWNLOADED ${target.length()} bytes; header=${String(target.readBytes().copyOfRange(4, 8))}")
        info.contentLength?.let { assertEquals(it, target.length()) }
        target.delete()
    }
}
