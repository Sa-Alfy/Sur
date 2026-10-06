package io.github.saalfy.sur.debug

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.StatFs
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.saalfy.sur.data.MIN_SONG_DURATION_MS
import io.github.saalfy.sur.download.AudioFileSaver
import io.github.saalfy.sur.downloader.DownloadException
import io.github.saalfy.sur.downloader.audioFileName
import io.github.saalfy.sur.downloader.mp4.Mp4Remux
import io.github.saalfy.sur.downloader.mp4.Mp4TagWriter
import io.github.saalfy.sur.downloader.newpipe.NewPipeAudioDownloader
import io.github.saalfy.sur.ui.theme.SurTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SPIKE_ALBUM = "Sur downloads"

/**
 * Debug-only gate for Phase 5: resolve → download → re-package → tag → save, one link,
 * with every step's result printed so it can be copied back into the report.
 */
class DownloadSpikeActivity : ComponentActivity() {

    private val log = mutableStateListOf<String>()
    private var running by mutableStateOf(false)
    private var link by mutableStateOf("")

    private val writePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        log("WRITE_EXTERNAL_STORAGE granted=$granted")
        if (granted) runSpike()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SurTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Downloader spike (debug only)", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = link,
                            onValueChange = { link = it },
                            label = { Text("Video link") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = ::pasteLink, enabled = !running) { Text("Paste") }
                            Button(onClick = ::start, enabled = !running && link.isNotBlank()) { Text("Run") }
                            OutlinedButton(onClick = ::copyLog, enabled = log.isNotEmpty()) { Text("Copy log") }
                        }
                        SelectionContainer {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(log) { line ->
                                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pasteLink() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        clip?.getItemAt(0)?.coerceToText(this)?.toString()?.let { link = it.trim() }
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Sur spike log", log.joinToString("\n")))
    }

    private fun start() {
        log.clear()
        log("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        val needsWrite = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsWrite) writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else runSpike()
    }

    private fun runSpike() {
        running = true
        lifecycleScope.launch {
            val raw = File(cacheDir, "spike_raw.m4a")
            val remuxed = File(cacheDir, "spike_remux.m4a")
            try {
                val downloader = NewPipeAudioDownloader()

                // 1. Resolve
                mem("before resolve")
                var t = System.currentTimeMillis()
                val info = downloader.resolve(link)
                log("1 RESOLVE ok in ${System.currentTimeMillis() - t} ms")
                log("  title='${info.title}'")
                log("  artist='${info.artist}' duration=${info.durationSeconds}s")
                log("  itag=${info.itag} bitrate=${info.bitrateKbps}kbps size=${info.contentLength}")
                mem("after resolve")

                val freeBytes = StatFs(cacheDir.path).availableBytes
                log("  free cache space=${freeBytes / 1_048_576} MB")

                // 2. Download
                t = System.currentTimeMillis()
                var lastQuarter = -1L
                downloader.download(info, raw) { done, total ->
                    val pct = if (total != null && total > 0) done * 100 / total else -1
                    if (pct / 25 != lastQuarter) {
                        lastQuarter = pct / 25
                        runOnUiThread { log("  download $pct% ($done/$total)") }
                    }
                }
                log("2 DOWNLOAD ok in ${System.currentTimeMillis() - t} ms, ${raw.length()} bytes")
                log("  raw file: ${describe(raw)}")

                // 3. Re-package
                t = System.currentTimeMillis()
                withContext(Dispatchers.IO) { Mp4Remux.remuxAudio(raw, remuxed) }
                log("3 REMUX ok in ${System.currentTimeMillis() - t} ms, ${remuxed.length()} bytes")
                log("  remuxed file: ${describe(remuxed)}")
                mem("after remux")

                // 4. Tag (with fallback)
                val tagged = withContext(Dispatchers.IO) {
                    runCatching { Mp4TagWriter.write(remuxed, info.title, info.artist, SPIKE_ALBUM) }
                }
                tagged.onSuccess { placement ->
                    log("4 TAG ok: path=TAGGED placement=$placement")
                    log("  read back: ${Mp4TagWriter.readTags(remuxed)}")
                }.onFailure { log("4 TAG FAILED (${it.message}) -> path=FALLBACK (untagged, 'Artist - Title.m4a')") }
                log("  retriever: ${describe(remuxed)}")

                // 5. Save
                val fileName = audioFileName(info.title, info.artist)
                val saved = AudioFileSaver(this@DownloadSpikeActivity)
                    .save(remuxed, fileName, info.title, info.artist, SPIKE_ALBUM)
                log("5 SAVE ok: method=${saved.method} id=${saved.songId}")
                log("  location=${saved.location}")

                // 6. What the library sees (poll briefly: indexing can lag on 29+)
                var row = queryRow(saved.songId)
                repeat(10) {
                    if (row?.durationMs?.let { it > 0 } == true) return@repeat
                    delay(500)
                    row = queryRow(saved.songId)
                }
                log("6 MEDIASTORE: $row")
                val visible = row != null && row!!.isMusic && row!!.durationMs >= MIN_SONG_DURATION_MS
                log("  visible in Sur's Songs list: ${if (visible) "YES" else "NO"}")
                log("DONE. Check the Songs tab in Sur, then tap Copy log.")
            } catch (e: DownloadException) {
                log("FAILED: ${e.error}")
                e.cause?.let { log("  cause: ${it::class.java.simpleName}: ${it.message}") }
            } catch (t: Throwable) {
                log("FAILED: ${t::class.java.simpleName}: ${t.message}")
            } finally {
                raw.delete()
                remuxed.delete()
                running = false
            }
        }
    }

    private data class Row(val title: String?, val artist: String?, val album: String?, val durationMs: Long, val isMusic: Boolean)

    private suspend fun queryRow(id: Long): Row? = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_MUSIC,
        )
        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media._ID} = ?",
            arrayOf(id.toString()),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) null else Row(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4) != 0)
        }
    }

    private fun describe(file: File): String {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            "title=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)}, " +
                "artist=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)}, " +
                "album=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)}, " +
                "durationMs=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)}, " +
                "mime=${r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)}"
        } catch (t: Throwable) {
            "retriever error: ${t.message}"
        } finally {
            r.release()
        }
    }

    private fun mem(label: String) {
        val rt = Runtime.getRuntime()
        val javaMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
        val nativeMb = Debug.getNativeHeapAllocatedSize() / 1_048_576
        val pssMb = Debug.getPss() / 1024
        log("  mem $label: java=${javaMb}MB native=${nativeMb}MB pss=${pssMb}MB")
    }

    private fun log(line: String) {
        log.add(line)
    }
}
