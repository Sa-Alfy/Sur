package io.github.saalfy.sur.debug

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BACKUP_PREFIX = "SUR_ORIGINAL_"
private const val BACKUP_TITLE = "SUR_ORIGINAL_TITLE"
private const val BACKUP_ARTIST = "SUR_ORIGINAL_ARTIST"
private const val BACKUP_ALBUM = "SUR_ORIGINAL_ALBUM"
private const val BACKUP_PICTURES = "SUR_ORIGINAL_PICTURES"
private const val BACKUP_VERSION = "SUR_ORIGINAL_VERSION"
private val BACKUP_KEYS = setOf(BACKUP_TITLE, BACKUP_ARTIST, BACKUP_ALBUM, BACKUP_PICTURES, BACKUP_VERSION)
private const val SPIKE_TITLE = "স্পাইক টাইটেল ✓ Ünï"
private const val SPIKE_ARTIST = "স্পাইক আর্টিস্ট"
private const val SPIKE_ALBUM = "SpikeAlbum"

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
        val gCopy = File(cacheDir, "tag-spike-g-${System.nanoTime()}-${name.substringAfterLast('/')}")
        copy.copyTo(gCopy, overwrite = true)
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
            val originalAudioHash = computeAudioHash(copy)
            reportAudioHash(report, "before b", originalAudioHash)
            val writtenCover = createCover()
            val writtenPictures = listOf(PictureSummary(writtenCover, "image/jpeg", "Front Cover", "Front Cover"))
            val beforeSize = copy.length()
            val writeStart = System.nanoTime()
            step("b write") {
                applyTags(
                    copy,
                    savedOriginal.properties,
                    SPIKE_TITLE,
                    SPIKE_ARTIST,
                    SPIKE_ALBUM,
                    writtenPictures,
                    createBackup(savedOriginal),
                )
                val writeMs = (System.nanoTime() - writeStart) / 1_000_000
                "${writeMs}ms size=$beforeSize->${copy.length()} coverBytes=${writtenCover.size}"
            }

            step("c re-read") {
                val afterWrite = readState(copy)
                check(afterWrite.title == SPIKE_TITLE) { "title=${afterWrite.title}" }
                check(afterWrite.artist == SPIKE_ARTIST) { "artist=${afterWrite.artist}" }
                check(afterWrite.album == SPIKE_ALBUM) { "album=${afterWrite.album}" }
                checkPicturesEqual(afterWrite.pictures, writtenPictures)
                val afterWriteAudioHash = computeAudioHash(copy)
                checkAudioHashEqual(originalAudioHash, afterWriteAudioHash, report, "before b vs after b")
                "Unicode values, all picture bytes and metadata, and backup fields present"
            }

            step("d MediaMetadataRetriever") {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(copy.absolutePath)
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) == SPIKE_TITLE) {
                        "title cross-check mismatch"
                    }
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) == SPIKE_ARTIST) {
                        "artist cross-check mismatch"
                    }
                    check(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) == SPIKE_ALBUM) {
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

            step("e2 restore from file only") {
                val beforeSize = copy.length()
                val current = readState(copy)
                val backup = requireNotNull(readBackup(current.properties)) { "backup fields missing" }
                val backupSize = current.properties[BACKUP_PICTURES]?.firstOrNull()?.toByteArray(Charsets.UTF_8)?.size ?: 0
                restoreFromBackup(copy, current, backup)
                val restored = readState(copy)
                checkStateEqual(restored, savedOriginal)
                "backupFieldBytes=$backupSize size=$beforeSize->${copy.length()}"
            }

            step("e restore") {
                applyTags(copy, savedOriginal.properties, savedOriginal.title, savedOriginal.artist, savedOriginal.album, savedOriginal.pictures, null)
                val restored = readState(copy)
                checkStateEqual(restored, savedOriginal)
                val afterRestoreAudioHash = computeAudioHash(copy)
                checkAudioHashEqual(originalAudioHash, afterRestoreAudioHash, report, "before b vs after e")
                "original text/cover restored from in-memory state"
            }

            step("g second apply") {
                val first = readState(gCopy)
                applyTags(gCopy, first.properties, SPIKE_TITLE, SPIKE_ARTIST, SPIKE_ALBUM,
                    listOf(PictureSummary(createCover(0xff2f6f68.toInt()), "image/jpeg", "Front Cover", "Front Cover")),
                    createBackup(first))
                val second = readState(gCopy)
                applyTags(gCopy, second.properties, "Second Spike", "Second Artist", "SecondAlbum",
                    listOf(PictureSummary(createCover(0xff9b3d58.toInt()), "image/jpeg", "Front Cover", "Front Cover")),
                    createBackup(second))
                val backup = requireNotNull(readBackup(readState(gCopy).properties)) { "backup fields missing after second apply" }
                restoreFromBackup(gCopy, readState(gCopy), backup)
                checkStateEqual(readState(gCopy), savedOriginal)
                "first backup preserved through second apply"
            }
        } catch (error: Throwable) {
            report.appendFailure("a-e", error)
        } finally {
            report.appendLine(
                "SUMMARY $name: " + listOf("a", "b", "c", "d", "e", "e2", "g").joinToString(" ") {
                    "$it=${statuses[it] ?: "FAIL"}"
                },
            )
            copy.delete()
            gCopy.delete()
        }
    }

    private fun applyTags(
        file: File,
        originalProperties: Map<String, Array<String>>,
        title: String?,
        artist: String?,
        album: String?,
        pictures: List<PictureSummary>,
        backup: BackupState?,
    ) {
        val properties = HashMap<String, Array<String>>(originalProperties.size)
        originalProperties.forEach { (key, values) -> properties[key] = values.copyOf() }
        setPropertyOrRemove(properties, "TITLE", title)
        setPropertyOrRemove(properties, "ARTIST", artist)
        setPropertyOrRemove(properties, "ALBUM", album)
        if (backup != null && BACKUP_KEYS.none(properties::containsKey)) {
            backup.title?.let { properties[BACKUP_TITLE] = arrayOf(it) }
            backup.artist?.let { properties[BACKUP_ARTIST] = arrayOf(it) }
            backup.album?.let { properties[BACKUP_ALBUM] = arrayOf(it) }
            properties[BACKUP_PICTURES] = arrayOf(serializePictures(backup.pictures))
            properties[BACKUP_VERSION] = arrayOf("1")
        }
        withFd(file) { fd -> check(TagLib.savePropertyMap(fd, properties)) { "savePropertyMap returned false" } }
        val tagPictures = pictures.map {
            Picture(it.data, it.description, it.pictureType, it.mimeType)
        }.toTypedArray()
        withFd(file) { fd -> check(TagLib.savePictures(fd, tagPictures)) { "savePictures returned false" } }
    }

    private fun setPropertyOrRemove(properties: HashMap<String, Array<String>>, key: String, value: String?) {
        if (value == null) properties.remove(key) else properties[key] = arrayOf(value)
    }

    private fun createBackup(state: ReadState): BackupState =
        BackupState(state.title, state.artist, state.album, state.pictures)

    private fun readBackup(properties: Map<String, Array<String>>): BackupState? {
        if (properties[BACKUP_VERSION]?.firstOrNull() != "1") return null
        val pictures = properties[BACKUP_PICTURES]?.firstOrNull()?.let(::deserializePictures) ?: emptyList()
        return BackupState(
            properties[BACKUP_TITLE]?.firstOrNull(),
            properties[BACKUP_ARTIST]?.firstOrNull(),
            properties[BACKUP_ALBUM]?.firstOrNull(),
            pictures,
        )
    }

    private fun restoreFromBackup(file: File, current: ReadState, backup: BackupState) {
        val properties = HashMap<String, Array<String>>(current.properties.size)
        current.properties.forEach { (key, values) -> properties[key] = values.copyOf() }
        setPropertyOrRemove(properties, "TITLE", backup.title)
        setPropertyOrRemove(properties, "ARTIST", backup.artist)
        setPropertyOrRemove(properties, "ALBUM", backup.album)
        BACKUP_KEYS.forEach(properties::remove)
        withFd(file) { fd -> check(TagLib.savePropertyMap(fd, properties)) { "restore savePropertyMap returned false" } }
        val pictures = backup.pictures.map { Picture(it.data, it.description, it.pictureType, it.mimeType) }.toTypedArray()
        withFd(file) { fd -> check(TagLib.savePictures(fd, pictures)) { "restore savePictures returned false" } }
    }

    private fun serializePictures(pictures: List<PictureSummary>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(pictures.size)
            pictures.forEach { picture ->
                writeString(output, picture.mimeType)
                writeString(output, picture.pictureType)
                writeString(output, picture.description)
                output.writeInt(picture.data.size)
                output.write(picture.data)
            }
        }
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    private fun deserializePictures(encoded: String): List<PictureSummary> {
        val input = DataInputStream(ByteArrayInputStream(Base64.decode(encoded, Base64.NO_WRAP)))
        return List(input.readInt()) {
            val mimeType = readString(input)
            val pictureType = readString(input)
            val description = readString(input)
            PictureSummary(ByteArray(input.readInt()).also(input::readFully), mimeType, pictureType, description)
        }
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String =
        ByteArray(input.readInt()).also(input::readFully).toString(Charsets.UTF_8)

    private fun checkStateEqual(actual: ReadState, expected: ReadState) {
        check(actual.title == expected.title) { "title=${actual.title}" }
        check(actual.artist == expected.artist) { "artist=${actual.artist}" }
        check(actual.album == expected.album) { "album=${actual.album}" }
        checkPicturesEqual(actual.pictures, expected.pictures)
        check(BACKUP_KEYS.none(actual.properties::containsKey)) { "backup fields remain" }
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

    private fun computeAudioHash(file: File): AudioHash {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            for (index in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = index
                    break
                }
            }
            check(audioTrack >= 0) { "no audio track" }
            val format = extractor.getTrackFormat(audioTrack)
            val capacity = maxOf(64 * 1024, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024))
            extractor.selectTrack(audioTrack)
            val buffer = ByteBuffer.allocate(capacity)
            val digest = MessageDigest.getInstance("SHA-256")
            var sampleCount = 0L
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                digest.update(buffer.array(), 0, sampleSize)
                sampleCount++
                extractor.advance()
            }
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sampleCount).array())
            AudioHash(digest.digest().joinToString("") { byte -> "%02x".format(byte) }, sampleCount, null)
        } catch (error: Throwable) {
            AudioHash(null, 0, error)
        } finally {
            extractor.release()
        }
    }

    private fun reportAudioHash(report: StringBuilder, label: String, result: AudioHash) {
        if (result.error != null) {
            report.appendLine("AUDIO HASH $label: unavailable ${result.error::class.java.name}: ${result.error.message}")
        } else {
            report.appendLine("AUDIO HASH $label: ${result.hash} samples=${result.sampleCount}")
        }
    }

    private fun checkAudioHashEqual(
        before: AudioHash,
        after: AudioHash,
        report: StringBuilder,
        label: String,
    ) {
        if (before.error != null || after.error != null) {
            val error = before.error ?: after.error
            report.appendLine("AUDIO HASH $label: unavailable ${error!!::class.java.name}: ${error.message}")
            return
        }
        val equal = before.hash == after.hash && before.sampleCount == after.sampleCount
        report.appendLine("AUDIO HASH $label: ${if (equal) "equal" else "not equal"}")
        check(equal) { "audio hash $label mismatch" }
    }

    private fun createCover(color: Int = 0xff2f6f68.toInt()): ByteArray {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
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
        val properties: Map<String, Array<String>>,
    )

    private data class BackupState(
        val title: String?,
        val artist: String?,
        val album: String?,
        val pictures: List<PictureSummary>,
    )

    private data class AudioProperties(
        val length: Int,
        val bitrate: Int,
        val sampleRate: Int,
        val channels: Int,
    )

    private data class AudioHash(
        val hash: String?,
        val sampleCount: Long,
        val error: Throwable?,
    )

    private data class PictureSummary(
        val data: ByteArray,
        val mimeType: String,
        val pictureType: String,
        val description: String,
    )
}