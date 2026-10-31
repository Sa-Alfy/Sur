package io.github.saalfy.sur.debug

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import io.github.saalfy.sur.ui.theme.SurTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FIELD = "SUR_ORIGINAL"

class TagSpikeActivity : ComponentActivity() {
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val report = withContext(Dispatchers.IO) { runFiles(uris) }
            setReport(report)
        }
    }

    private var reportState = mutableStateOf("No files selected.")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SurTheme {
                var report by remember { reportState }
                val copyReport = {
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Tag spike report", report))
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { pickAudio.launch(arrayOf("audio/*")) },
                    ) { Text("Pick audio files") }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = copyReport,
                    ) { Text("Copy report") }
                    Text(
                        text = report,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    private fun setReport(report: String) {
        reportState.value = report
    }

    private fun runFiles(uris: List<Uri>): String = buildString {
        uris.forEachIndexed { index, uri ->
            if (index > 0) appendLine()
            appendLine("FILE ${index + 1}: $uri")
            try {
                runFile(uri, this)
            } catch (error: Throwable) {
                appendFailure("file", error)
            }
        }
    }

    private fun runFile(uri: Uri, report: StringBuilder) {
        val name = queryName(uri) ?: "selected-${System.currentTimeMillis()}.audio"
        val copy = File(cacheDir, "tag-spike-${System.nanoTime()}-${name.substringAfterLast('/')}")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open $uri" }
            FileOutputStream(copy).use { output -> input.copyTo(output) }
        }
        try {
            fun step(name: String, action: () -> String) {
                try {
                    report.appendLine("PASS $name: ${action()}")
                } catch (error: Throwable) {
                    report.appendFailure(name, error)
                }
            }

            val original = readState(copy)
            step("a read") {
                "format=${copy.extension.ifBlank { "(none)" }} size=${copy.length()} " +
                    "title=${original.title} artist=${original.artist} album=${original.album} " +
                    "coverExists=${original.cover != null} coverBytes=${original.cover?.size ?: 0}"
            }
            val originalDuration = readDuration(copy)
            val writtenCover = createCover()
            val beforeSize = copy.length()
            val writeStart = System.nanoTime()
            step("b write") {
                updateTags(copy, original.properties, "SpikeTitle", "SpikeArtist", "SpikeAlbum", writtenCover)
                val writeMs = (System.nanoTime() - writeStart) / 1_000_000
                "${writeMs}ms size=$beforeSize->${copy.length()} coverBytes=${writtenCover.size}"
            }

            step("c re-read") {
                val afterWrite = readState(copy)
                check(afterWrite.title == "SpikeTitle") { "title=${afterWrite.title}" }
                check(afterWrite.artist == "SpikeArtist") { "artist=${afterWrite.artist}" }
                check(afterWrite.album == "SpikeAlbum") { "album=${afterWrite.album}" }
                check(afterWrite.cover?.contentEquals(writtenCover) == true) { "cover bytes differ" }
                check(afterWrite.custom == "test") { "custom=${afterWrite.custom}" }
                "values, cover bytes, and $FIELD equal"
            }

            step("d MediaMetadataRetriever") {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(copy.absolutePath)
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) == "SpikeTitle") {
                        "title cross-check mismatch"
                    }
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) == "SpikeArtist") {
                        "artist cross-check mismatch"
                    }
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) == "SpikeAlbum") {
                        "album cross-check mismatch"
                    }
                    check(retriever.embeddedPicture?.contentEquals(writtenCover) == true) {
                        "embedded picture mismatch"
                    }
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    check(duration != null && kotlin.math.abs(duration - originalDuration) <= 50) {
                        "duration original=$originalDuration copy=$duration"
                    }
                    "text, cover bytes, duration=${originalDuration}ms"
                } finally {
                    retriever.release()
                }
            }

            step("e restore") {
                updateTags(copy, original.properties, original.title, original.artist, original.album, original.cover)
                val restored = readState(copy)
                check(restored.title == original.title) { "title=${restored.title}" }
                check(restored.artist == original.artist) { "artist=${restored.artist}" }
                check(restored.album == original.album) { "album=${restored.album}" }
                check(original.cover?.let { restored.cover?.contentEquals(it) == true } ?: (restored.cover == null)) {
                    "cover bytes differ after restore"
                }
                check(restored.custom == null) { "$FIELD remains ${restored.custom}" }
                "original text/cover restored and $FIELD removed"
            }
        } catch (error: Throwable) {
            report.appendFailure("a-e", error)
        } finally {
            copy.delete()
        }
    }

    private fun updateTags(
        file: File,
        originalProperties: Map<String, Array<String>>,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
    ) {
        val properties = HashMap<String, Array<String>>(originalProperties.size)
        originalProperties.forEach { (key, values) -> properties[key] = values.copyOf() }
        properties["TITLE"] = arrayOf(title.orEmpty())
        properties["ARTIST"] = arrayOf(artist.orEmpty())
        properties["ALBUM"] = arrayOf(album.orEmpty())
        if (title == "SpikeTitle") properties[FIELD] = arrayOf("test") else properties.remove(FIELD)
        withFd(file) { fd -> check(TagLib.savePropertyMap(fd, properties)) { "savePropertyMap returned false" } }
        val pictures = cover?.let {
            arrayOf(Picture(it, "Front Cover", "Front Cover", "image/jpeg"))
        } ?: emptyArray()
        withFd(file) { fd -> check(TagLib.savePictures(fd, pictures)) { "savePictures returned false" } }
    }

    private fun readState(file: File): ReadState = withFd(file) { fd ->
        val metadata = requireNotNull(TagLib.getMetadata(fd, readPictures = true)) { "metadata is null" }
        ReadState(
            title = metadata.propertyMap["TITLE"]?.firstOrNull(),
            artist = metadata.propertyMap["ARTIST"]?.firstOrNull(),
            album = metadata.propertyMap["ALBUM"]?.firstOrNull(),
            cover = metadata.pictures.firstOrNull()?.data?.copyOf(),
            custom = metadata.propertyMap[FIELD]?.firstOrNull(),
            properties = metadata.propertyMap.mapValues { (_, values) -> values.copyOf() },
        )
    }

    private fun <T> withFd(file: File, block: (Int) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            block(descriptor.dup().detachFd())
        }

    private fun readDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("duration unavailable")
        } finally {
            retriever.release()
        }
    }

    private fun createCover(): ByteArray {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff2f6f68.toInt())
        return java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)) { "JPEG compression failed" }
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun queryName(uri: Uri): String? = contentResolver.query(uri, arrayOf("_display_name"), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun appendFailure(step: String, error: Throwable) {
        reportState.value += "FAIL $step: ${error::class.java.name}: ${error.message}\n"
    }

    private fun StringBuilder.appendFailure(step: String, error: Throwable) {
        appendLine("FAIL $step: ${error::class.java.name}: ${error.message}")
    }

    private data class ReadState(
        val title: String?,
        val artist: String?,
        val album: String?,
        val cover: ByteArray?,
        val custom: String?,
        val properties: Map<String, Array<String>>,
    )
}