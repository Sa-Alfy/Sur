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
                val shareReport = {
                    startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, report)
                        },
                    )
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
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(modifier = Modifier.weight(1f), onClick = copyReport) { Text("Copy report") }
                        Button(modifier = Modifier.weight(1f), onClick = shareReport) { Text("Share report") }
                    }
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
        val statuses = linkedMapOf<String, String>()
        val copy = File(cacheDir, "tag-spike-${System.nanoTime()}-${name.substringAfterLast('/')}")
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open $uri" }
            FileOutputStream(copy).use { output -> input.copyTo(output) }
        }
        try {
            fun step(stepName: String, action: () -> String) {
                try {
                    report.appendLine("PASS $stepName: ${action()}")
                    statuses[stepName.substringBefore(' ')] = "PASS"
                } catch (error: Throwable) {
                    report.appendFailure(stepName, error)
                    statuses[stepName.substringBefore(' ')] = "FAIL"
                }
            }

            var original: ReadState? = null
            step("a read") {
                original = readState(copy)
                val audio = readAudioProperties(copy)
                val pictures = readPictureSummaries(copy)
                val read = requireNotNull(original)
                "format=${copy.extension.ifBlank { "(none)" }} size=${copy.length()} " +
                    "mime=${contentResolver.getType(uri)} title=${read.title} artist=${read.artist} " +
                    "album=${read.album} audioLength=${audio.length} bitrate=${audio.bitrate} " +
                    "sampleRate=${audio.sampleRate} channels=${audio.channels} pictures=${pictures.size}\n" +
                    pictures.mapIndexed { index, picture ->
                        "picture[$index] mime=${picture.mimeType} type=${picture.pictureType} " +
                            "description=${picture.description} size=${picture.data.size}"
                    }.joinToString("\n")
            }
            val savedOriginal = requireNotNull(original) { "step a did not produce original state" }
            val originalDuration = readDuration(copy)
            val writtenCover = createCover()
            val writtenPictures = listOf(PictureSummary(writtenCover, "image/jpeg", "Front Cover", "Front Cover"))
            val beforeSize = copy.length()
            val writeStart = System.nanoTime()
            step("b write") {
                updateTags(copy, savedOriginal.properties, "SpikeTitle", "SpikeArtist", "SpikeAlbum", writtenPictures)
                val writeMs = (System.nanoTime() - writeStart) / 1_000_000
                "${writeMs}ms size=$beforeSize->${copy.length()} coverBytes=${writtenCover.size}"
            }

            step("c re-read") {
                val afterWrite = readState(copy)
                check(afterWrite.title == "SpikeTitle") { "title=${afterWrite.title}" }
                check(afterWrite.artist == "SpikeArtist") { "artist=${afterWrite.artist}" }
                check(afterWrite.album == "SpikeAlbum") { "album=${afterWrite.album}" }
                checkPicturesEqual(afterWrite.pictures, writtenPictures)
                check(afterWrite.custom == "test") { "custom=${afterWrite.custom}" }
                "values, all picture bytes and metadata, and $FIELD equal"
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
                updateTags(copy, savedOriginal.properties, savedOriginal.title, savedOriginal.artist, savedOriginal.album, savedOriginal.pictures)
                val restored = readState(copy)
                check(restored.title == savedOriginal.title) { "title=${restored.title}" }
                check(restored.artist == savedOriginal.artist) { "artist=${restored.artist}" }
                check(restored.album == savedOriginal.album) { "album=${restored.album}" }
                checkPicturesEqual(restored.pictures, savedOriginal.pictures)
                check(restored.custom == null) { "$FIELD remains ${restored.custom}" }
                "original text/cover restored and $FIELD removed"
            }
        } catch (error: Throwable) {
            report.appendFailure("a-e", error)
        } finally {
            report.appendLine(
                "SUMMARY $name: " + listOf("a", "b", "c", "d", "e").joinToString(" ") {
                    "$it=${statuses[it] ?: "FAIL"}"
                },
            )
            copy.delete()
        }
    }

    private fun updateTags(
        file: File,
        originalProperties: Map<String, Array<String>>,
        title: String?,
        artist: String?,
        album: String?,
        pictures: List<PictureSummary>,
    ) {
        val properties = HashMap<String, Array<String>>(originalProperties.size)
        originalProperties.forEach { (key, values) -> properties[key] = values.copyOf() }
        setPropertyOrRemove(properties, "TITLE", title)
        setPropertyOrRemove(properties, "ARTIST", artist)
        setPropertyOrRemove(properties, "ALBUM", album)
        if (title == "SpikeTitle") properties[FIELD] = arrayOf("test") else properties.remove(FIELD)
        withFd(file) { fd -> check(TagLib.savePropertyMap(fd, properties)) { "savePropertyMap returned false" } }
        val tagPictures = pictures.map {
            Picture(it.data, it.description, it.pictureType, it.mimeType)
        }.toTypedArray()
        withFd(file) { fd -> check(TagLib.savePictures(fd, tagPictures)) { "savePictures returned false" } }
    }

    private fun setPropertyOrRemove(properties: HashMap<String, Array<String>>, key: String, value: String?) {
        if (value == null) properties.remove(key) else properties[key] = arrayOf(value)
    }

    private fun readState(file: File): ReadState = withFd(file) { fd ->
        val metadata = requireNotNull(TagLib.getMetadata(fd, readPictures = true)) { "metadata is null" }
        ReadState(
            title = metadata.propertyMap["TITLE"]?.firstOrNull(),
            artist = metadata.propertyMap["ARTIST"]?.firstOrNull(),
            album = metadata.propertyMap["ALBUM"]?.firstOrNull(),
            pictures = metadata.pictures.map { picture ->
                PictureSummary(picture.data.copyOf(), picture.mimeType, picture.pictureType, picture.description)
            },
            custom = metadata.propertyMap[FIELD]?.firstOrNull(),
            properties = metadata.propertyMap.mapValues { (_, values) -> values.copyOf() },
        )
    }

    private fun checkPicturesEqual(actual: List<PictureSummary>, expected: List<PictureSummary>) {
        check(actual.size == expected.size) { "picture count actual=${actual.size} expected=${expected.size}" }
        actual.zip(expected).forEachIndexed { index, (actualPicture, expectedPicture) ->
            check(actualPicture.data.contentEquals(expectedPicture.data)) { "picture[$index] data differs" }
            check(actualPicture.mimeType == expectedPicture.mimeType) { "picture[$index] mime differs" }
            check(actualPicture.pictureType == expectedPicture.pictureType) { "picture[$index] type differs" }
            check(actualPicture.description == expectedPicture.description) { "picture[$index] description differs" }
        }
    }

    private fun readAudioProperties(file: File): AudioProperties = withFd(file) { fd ->
        val properties = requireNotNull(TagLib.getAudioProperties(fd)) { "audio properties are null" }
        AudioProperties(properties.length, properties.bitrate, properties.sampleRate, properties.channels)
    }

    private fun readPictureSummaries(file: File): List<PictureSummary> = withFd(file) { fd ->
        TagLib.getPictures(fd).map { picture ->
            PictureSummary(picture.data, picture.mimeType, picture.pictureType, picture.description)
        }
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

    private fun StringBuilder.appendFailure(step: String, error: Throwable) {
        appendLine("FAIL $step: ${error::class.java.name}: ${error.message}")
    }

    private data class ReadState(
        val title: String?,
        val artist: String?,
        val album: String?,
        val pictures: List<PictureSummary>,
        val custom: String?,
        val properties: Map<String, Array<String>>,
    )

    private data class AudioProperties(
        val length: Int,
        val bitrate: Int,
        val sampleRate: Int,
        val channels: Int,
    )

    private data class PictureSummary(
        val data: ByteArray,
        val mimeType: String,
        val pictureType: String,
        val description: String,
    )
}