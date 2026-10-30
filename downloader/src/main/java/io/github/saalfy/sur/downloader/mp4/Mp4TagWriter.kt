package io.github.saalfy.sur.downloader.mp4

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

enum class TagPlacement {
    /** moov was the last box: rewritten in place, file grew. */
    MOOV_AT_END,

    /** moov was followed by a free box: tags took space from it, no other bytes moved. */
    MOOV_INTO_FREE_BOX,
}

class TagWriteException(message: String) : IOException(message)

/**
 * Adds iTunes-style title/artist/album tags (moov/udta/meta/ilst) to an MP4 file.
 *
 * Only edits that never move sample data are attempted, so chunk offsets (stco/co64) never need
 * patching. Anything else throws [TagWriteException] and the caller falls back to an untagged file.
 */
object Mp4TagWriter {

    private const val HEADER = 8

    fun write(file: File, title: String, artist: String?, album: String?, coverJpeg: ByteArray? = null): TagPlacement {
        // If cover is present, try writing with cover first; on TagWriteException retry without cover.
        if (coverJpeg != null) {
            try {
                return writeImpl(file, title, artist, album, coverJpeg)
            } catch (_: TagWriteException) {
                // Not enough room for cover; fall back to text-only tags
            }
        }
        return writeImpl(file, title, artist, album, null)
    }

    fun applyInfo(
        file: File,
        title: String,
        artist: String?,
        album: String?,
        coverJpeg: ByteArray?,
    ): TagPlacement {
        RandomAccessFile(file, "rw").use { raf ->
            val boxes = readTopLevelBoxes(raf)
            val moovIndex = boxes.indexOfFirst { it.type == "moov" }
            if (moovIndex < 0) throw TagWriteException("No moov box")
            val moov = boxes[moovIndex]
            if (moov.headerSize != HEADER) throw TagWriteException("64-bit moov size not supported")
            val moovBytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(moovBytes)

            val ilst = findIlst(moovBytes)
            val oldItems = ilst?.let { children(moovBytes, it.start + HEADER, it.end) }
                ?: emptyList()
            val hasBackup = oldItems.any { isOriginalBackup(moovBytes, it) }
            val newItems = buildUpdatedItems(moovBytes, oldItems, title, artist, album, coverJpeg, hasBackup)
            val newMoov = replaceIlst(moovBytes, newItems)
            val placement = placementFor(boxes, moovIndex, moov, newMoov)
            writePlaced(raf, moov, newMoov, boxes.getOrNull(moovIndex + 1), placement)
            return placement
        }
    }

    fun hasOriginalBackup(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val moov = readTopLevelBoxes(raf).firstOrNull { it.type == "moov" } ?: return false
            val bytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(bytes)
            val ilst = findIlst(bytes) ?: return false
            return children(bytes, ilst.start + HEADER, ilst.end).any { isOriginalBackup(bytes, it) }
        }
    }

    fun restoreOriginal(file: File): Boolean {
        RandomAccessFile(file, "rw").use { raf ->
            val boxes = readTopLevelBoxes(raf)
            val moovIndex = boxes.indexOfFirst { it.type == "moov" }
            if (moovIndex < 0) return false
            val moov = boxes[moovIndex]
            val moovBytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(moovBytes)
            val ilst = findIlst(moovBytes) ?: return false
            val items = children(moovBytes, ilst.start + HEADER, ilst.end)
            val backup = items.firstOrNull { isOriginalBackup(moovBytes, it) } ?: return false
            val restored = restoreItems(moovBytes, items, backup)
            val newMoov = replaceIlst(moovBytes, restored)
            val placement = placementFor(boxes, moovIndex, moov, newMoov)
            writePlaced(raf, moov, newMoov, boxes.getOrNull(moovIndex + 1), placement)
            return true
        }
    }

    private fun writeImpl(file: File, title: String, artist: String?, album: String?, coverJpeg: ByteArray?): TagPlacement {
        RandomAccessFile(file, "rw").use { raf ->
            val boxes = readTopLevelBoxes(raf)
            val moovIndex = boxes.indexOfFirst { it.type == "moov" }
            if (moovIndex < 0) throw TagWriteException("No moov box")
            val moov = boxes[moovIndex]
            if (moov.headerSize != HEADER) throw TagWriteException("64-bit moov size not supported")

            val moovBytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(moovBytes)
            val newMoov = withTags(moovBytes, buildMeta(title, artist, album, coverJpeg))
            val growth = (newMoov.size - moovBytes.size).toLong()
            val next = boxes.getOrNull(moovIndex + 1)

            return when {
                next == null -> {
                    raf.seek(moov.offset)
                    raf.write(newMoov)
                    raf.setLength(moov.offset + newMoov.size)
                    TagPlacement.MOOV_AT_END
                }
                next.type == "free" && (next.size == growth || next.size - growth >= HEADER) -> {
                    raf.seek(moov.offset)
                    raf.write(newMoov)
                    val remaining = next.size - growth
                    if (remaining > 0) raf.write(boxHeader(remaining.toInt(), "free"))
                    TagPlacement.MOOV_INTO_FREE_BOX
                }
                else -> throw TagWriteException("No room after moov (next box: ${next.type})")
            }
        }
    }

    /** Reads back ilst text tags, keyed by atom name (e.g. "©nam"). Used by tests and the spike. */
    fun readTags(file: File): Map<String, String> {
        RandomAccessFile(file, "r").use { raf ->
            val moov = readTopLevelBoxes(raf).firstOrNull { it.type == "moov" } ?: return emptyMap()
            val bytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(bytes)
            val udta = children(bytes, HEADER, bytes.size).firstOrNull { it.type == "udta" } ?: return emptyMap()
            val meta = children(bytes, udta.start + HEADER, udta.end).firstOrNull { it.type == "meta" } ?: return emptyMap()
            val ilst = children(bytes, meta.start + HEADER + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return emptyMap()
            return children(bytes, ilst.start + HEADER, ilst.end).mapNotNull { item ->
                if (item.type == "covr" || item.type == "----") return@mapNotNull null
                val data = children(bytes, item.start + HEADER, item.end).firstOrNull { it.type == "data" }
                    ?: return@mapNotNull null
                val valueStart = data.start + HEADER + 8
                item.type to String(bytes, valueStart, data.end - valueStart, Charsets.UTF_8)
            }.toMap()
        }
    }

    /**
     * Reads the cover art bytes from ilst's "covr" item, if present.
     * Returns the raw payload bytes (after the 8-byte type+locale header in the data box).
     */
    fun readCover(file: File): ByteArray? {
        RandomAccessFile(file, "r").use { raf ->
            val moov = readTopLevelBoxes(raf).firstOrNull { it.type == "moov" } ?: return null
            val bytes = ByteArray(moov.size.toInt())
            raf.seek(moov.offset)
            raf.readFully(bytes)
            val udta = children(bytes, HEADER, bytes.size).firstOrNull { it.type == "udta" } ?: return null
            val meta = children(bytes, udta.start + HEADER, udta.end).firstOrNull { it.type == "meta" } ?: return null
            val ilst = children(bytes, meta.start + HEADER + 4, meta.end).firstOrNull { it.type == "ilst" } ?: return null
            val covr = children(bytes, ilst.start + HEADER, ilst.end).firstOrNull { it.type == "covr" } ?: return null
            val data = children(bytes, covr.start + HEADER, covr.end).firstOrNull { it.type == "data" } ?: return null
            val payloadStart = data.start + HEADER + 8 // skip 4-byte type flag + 4-byte locale
            return bytes.copyOfRange(payloadStart, data.end)
        }
    }

    // --- building ---

    private val managedTypes = setOf("©nam", "©ART", "©alb", "covr")

    private fun buildUpdatedItems(
        bytes: ByteArray,
        oldItems: List<Child>,
        title: String,
        artist: String?,
        album: String?,
        coverJpeg: ByteArray?,
        hasBackup: Boolean,
    ): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        var titleAdded = false
        var artistAdded = false
        var albumAdded = false
        var coverAdded = false
        oldItems.forEach { item ->
            when (item.type) {
                "©nam" -> if (!titleAdded) {
                    result += textItem("©nam", title)
                    titleAdded = true
                }
                "©ART" -> if (artist == null) {
                    result += bytes.copyOfRange(item.start, item.end)
                } else if (!artistAdded) {
                    result += textItem("©ART", artist)
                    artistAdded = true
                }
                "©alb" -> if (album == null) {
                    result += bytes.copyOfRange(item.start, item.end)
                } else if (!albumAdded) {
                    result += textItem("©alb", album)
                    albumAdded = true
                }
                "covr" -> if (coverJpeg == null) {
                    result += bytes.copyOfRange(item.start, item.end)
                } else if (!coverAdded) {
                    result += coverItem(coverJpeg)
                    coverAdded = true
                }
                else -> result += bytes.copyOfRange(item.start, item.end)
            }
        }
        if (!titleAdded) result += textItem("©nam", title)
        if (artist != null && !artistAdded) result += textItem("©ART", artist)
        if (album != null && !albumAdded) result += textItem("©alb", album)
        if (coverJpeg != null && !coverAdded) result += coverItem(coverJpeg)
        if (!hasBackup) {
            val original = ByteArrayOutputStream().apply {
                oldItems.filter { it.type in managedTypes }
                    .forEach { write(bytes, it.start, it.end - it.start) }
            }.toByteArray()
            result += originalBackup(original)
        }
        return result
    }

    private fun originalBackup(rawItems: ByteArray): ByteArray = box(
        "----",
        fullBox("mean", "com.sur".toByteArray()) +
            fullBox("name", "original".toByteArray()) +
            box("data", int(0) + rawItems),
    )

    private fun restoreItems(bytes: ByteArray, items: List<Child>, backup: Child): List<ByteArray> {
        val backupData = children(bytes, backup.start + HEADER, backup.end)
            .firstOrNull { it.type == "data" } ?: throw TagWriteException("Invalid original backup")
        val rawStart = backupData.start + HEADER + 4
        val original = children(bytes, rawStart, backupData.end)
            .map { bytes.copyOfRange(it.start, it.end) }
        val result = mutableListOf<ByteArray>()
        var restored = false
        items.forEach { item ->
            if (item === backup) {
                if (!restored) {
                    result += original
                    restored = true
                }
            } else if (item.type !in managedTypes) {
                result += bytes.copyOfRange(item.start, item.end)
            }
        }
        return result
    }

    private fun isOriginalBackup(bytes: ByteArray, item: Child): Boolean {
        if (item.type != "----") return false
        val children = children(bytes, item.start + HEADER, item.end)
        fun text(type: String): String? {
            val child = children.firstOrNull { it.type == type } ?: return null
            return String(bytes, child.start + HEADER + 4, child.end - child.start - HEADER - 4, Charsets.UTF_8)
        }
        return text("mean") == "com.sur" && text("name") == "original" && children.any { it.type == "data" }
    }

    private fun findIlst(moov: ByteArray): Child? {
        val udta = children(moov, HEADER, moov.size).firstOrNull { it.type == "udta" } ?: return null
        val meta = children(moov, udta.start + HEADER, udta.end).firstOrNull { it.type == "meta" } ?: return null
        return children(moov, meta.start + HEADER + 4, meta.end).firstOrNull { it.type == "ilst" }
    }

    private fun replaceIlst(moov: ByteArray, items: List<ByteArray>): ByteArray {
        val newIlst = box("ilst", items.fold(ByteArrayOutputStream()) { out, item -> out.apply { write(item) } }.toByteArray())
        val udta = children(moov, HEADER, moov.size).firstOrNull { it.type == "udta" }
        if (udta == null) return withTags(moov, fullBox("meta", buildMetaChildren(newIlst)))
        val udtaBytes = moov.copyOfRange(udta.start, udta.end)
        val udtaChildren = children(udtaBytes, HEADER, udtaBytes.size)
        val meta = udtaChildren.firstOrNull { it.type == "meta" }
        val newUdta = if (meta == null) {
            appendChild(udtaBytes, fullBox("meta", buildMetaChildren(newIlst)))
        } else {
            val metaBytes = udtaBytes.copyOfRange(meta.start, meta.end)
            val metaChildren = children(metaBytes, HEADER + 4, metaBytes.size)
            val ilst = metaChildren.firstOrNull { it.type == "ilst" }
            val newMeta = if (ilst != null) {
                replaceChild(metaBytes, ilst, newIlst)
            } else {
                appendChild(metaBytes, newIlst)
            }
            replaceChild(udtaBytes, meta, newMeta)
        }
        return replaceChild(moov, udta, newUdta)
    }

    private fun buildMetaChildren(ilst: ByteArray): ByteArray = buildMeta("", null, null).let {
        val hdlr = children(it, HEADER + 4, it.size).first { it.type == "hdlr" }
        it.copyOfRange(HEADER + 4, hdlr.end) + ilst
    }

    private fun appendChild(parent: ByteArray, child: ByteArray): ByteArray =
        boxHeader(parent.size + child.size, String(parent, 4, 4, Charsets.ISO_8859_1)) +
            parent.copyOfRange(HEADER, parent.size) + child

    private fun replaceChild(parent: ByteArray, child: Child, replacement: ByteArray): ByteArray =
        boxHeader(parent.size - (child.end - child.start) + replacement.size, String(parent, 4, 4, Charsets.ISO_8859_1)) +
            parent.copyOfRange(HEADER, child.start) + replacement + parent.copyOfRange(child.end, parent.size)

    private fun placementFor(boxes: List<TopBox>, moovIndex: Int, moov: TopBox, newMoov: ByteArray): TagPlacement {
        val growth = (newMoov.size - moov.size).toLong()
        val next = boxes.getOrNull(moovIndex + 1)
        return when {
            next == null -> TagPlacement.MOOV_AT_END
            next.type == "free" && (next.size == growth || next.size - growth >= HEADER) -> TagPlacement.MOOV_INTO_FREE_BOX
            else -> throw TagWriteException("No room after moov (next box: ${next.type})")
        }
    }

    private fun writePlaced(
        raf: RandomAccessFile,
        moov: TopBox,
        newMoov: ByteArray,
        next: TopBox?,
        placement: TagPlacement,
    ) {
        raf.seek(moov.offset)
        raf.write(newMoov)
        if (placement == TagPlacement.MOOV_AT_END) {
            raf.setLength(moov.offset + newMoov.size)
        } else {
            val remaining = next!!.size - (newMoov.size - moov.size)
            if (remaining > 0) raf.write(boxHeader(remaining.toInt(), "free"))
        }
    }

    private fun buildMeta(title: String, artist: String?, album: String?, coverJpeg: ByteArray? = null): ByteArray {
        val hdlr = fullBox(
            "hdlr",
            int(0) + latin1("mdir") + latin1("appl") + int(0) + int(0) + byteArrayOf(0),
        )
        val items = ByteArrayOutputStream().apply {
            write(textItem("©nam", title))
            artist?.let { write(textItem("©ART", it)) }
            album?.let { write(textItem("©alb", it)) }
            coverJpeg?.let { write(coverItem(it)) }
        }.toByteArray()
        return fullBox("meta", hdlr + box("ilst", items))
    }

    private fun textItem(type: String, value: String): ByteArray =
        box(type, box("data", int(1) + int(0) + value.toByteArray(Charsets.UTF_8)))

    /**
     * Builds a "covr" ilst item with a "data" box.
     * Type flag 13 = JPEG (per iTunes Metadata Format Specification).
     */
    private fun coverItem(jpegBytes: ByteArray): ByteArray =
        box("covr", box("data", int(13) + int(0) + jpegBytes))

    /** Inserts [meta] into moov's udta (creating udta if needed) and fixes the box sizes. */
    private fun withTags(moov: ByteArray, meta: ByteArray): ByteArray {
        val udta = children(moov, HEADER, moov.size).firstOrNull { it.type == "udta" }
        val out = ByteArrayOutputStream()
        if (udta == null) {
            val newUdta = box("udta", meta)
            out.write(boxHeader(moov.size + newUdta.size, "moov"))
            out.write(moov, HEADER, moov.size - HEADER)
            out.write(newUdta)
        } else {
            if (children(moov, udta.start + HEADER, udta.end).any { it.type == "meta" }) {
                throw TagWriteException("udta already has a meta box")
            }
            out.write(boxHeader(moov.size + meta.size, "moov"))
            out.write(moov, HEADER, udta.start - HEADER)
            out.write(boxHeader(udta.end - udta.start + meta.size, "udta"))
            out.write(moov, udta.start + HEADER, udta.end - udta.start - HEADER)
            out.write(meta)
            out.write(moov, udta.end, moov.size - udta.end)
        }
        return out.toByteArray()
    }

    // --- parsing ---

    private class TopBox(val type: String, val offset: Long, val size: Long, val headerSize: Int)

    private class Child(val type: String, val start: Int, val end: Int)

    private fun readTopLevelBoxes(raf: RandomAccessFile): List<TopBox> {
        val boxes = mutableListOf<TopBox>()
        val length = raf.length()
        var offset = 0L
        while (offset + HEADER <= length) {
            raf.seek(offset)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = readType(raf)
            var headerSize = HEADER
            when (size) {
                1L -> {
                    size = raf.readLong()
                    headerSize = 16
                }
                0L -> size = length - offset
            }
            if (size < headerSize || offset + size > length) throw TagWriteException("Corrupt box '$type' at $offset")
            boxes += TopBox(type, offset, size, headerSize)
            offset += size
        }
        return boxes
    }

    private fun children(bytes: ByteArray, from: Int, to: Int): List<Child> {
        val result = mutableListOf<Child>()
        var pos = from
        while (pos + HEADER <= to) {
            val size = readInt(bytes, pos)
            if (size < HEADER || pos + size > to) throw TagWriteException("Corrupt child box at $pos")
            result += Child(String(bytes, pos + 4, 4, Charsets.ISO_8859_1), pos, pos + size)
            pos += size
        }
        return result
    }

    private fun readType(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.ISO_8859_1)
    }

    private fun readInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun box(type: String, payload: ByteArray) = boxHeader(HEADER + payload.size, type) + payload

    private fun fullBox(type: String, payload: ByteArray) = box(type, int(0) + payload)

    private fun boxHeader(size: Int, type: String) = int(size) + latin1(type)

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun latin1(s: String) = s.toByteArray(Charsets.ISO_8859_1)
}
