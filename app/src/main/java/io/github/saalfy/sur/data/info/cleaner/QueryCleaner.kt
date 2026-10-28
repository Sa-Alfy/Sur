package io.github.saalfy.sur.data.info.cleaner

import io.github.saalfy.sur.data.info.lookup.LookupQuery

object QueryCleaner {

    private val NOISE_REGEX = Regex(
        "(?i)\\b(official|video|audio|lyric|lyrical|lyrics|full|hd|4k|movie\\s+song)\\b"
    )

    private val FILE_EXT_REGEX = Regex(
        "(?i)\\.(mp3|m4a|opus|flac|wav|aac|ogg|wma|webm|m4p)$"
    )

    private val BRACKET_REGEX = Regex("[\\[\\(\\{]([^\\]\\)\\}]*)[\\]\\)\\}]")

    private val FEAT_HINT_REGEX = Regex("(?i)\\b(feat\\.?|ft\\.?)\\b")

    private val SEPARATOR_HYPHEN_REGEX = Regex(
        "(?:^|[\\s_]+)-+[\\s_]*|[\\s_]*-+(?:[\\s_]+|$)"
    )

    private fun isNoiseSegment(segment: String): Boolean {
        val withoutNoise = segment.replace(NOISE_REGEX, "")
        return !withoutNoise.any { it.isLetterOrDigit() }
    }

    /**
     * Cleans metadata and produces up to 3 ordered query variants.
     * Returns emptyList() if the cleaned text contains no letters or digits.
     */
    fun clean(
        tagTitle: String? = null,
        tagArtist: String? = null,
        displayName: String? = null,
        durationMs: Long? = null,
    ): List<LookupQuery> {
        val variants = mutableListOf<LookupQuery>()

        // 1. Tag-based queries if tag title is present and valid
        val cleanTagTitle = tagTitle?.let { cleanText(it) }?.takeIf { it.any { c -> c.isLetterOrDigit() } }
        val cleanTagArtist = tagArtist?.let { cleanText(it) }?.takeIf { it.any { c -> c.isLetterOrDigit() } }

        if (cleanTagTitle != null) {
            // Tag title shaped like "Artist - Title": offer the split first
            val sepIdx = cleanTagTitle.indexOf(" - ")
            if (sepIdx >= 0) {
                val splitArtist = cleanTagTitle.substring(0, sepIdx).trim()
                val splitTitle = cleanTagTitle.substring(sepIdx + 3).trim()
                if (splitArtist.any { it.isLetterOrDigit() } && splitTitle.any { it.isLetterOrDigit() }) {
                    variants.add(LookupQuery(title = splitTitle, artist = splitArtist, durationMs = durationMs))
                }
            }
            if (cleanTagArtist != null) {
                variants.add(LookupQuery(title = cleanTagTitle, artist = cleanTagArtist, durationMs = durationMs))
            }
            variants.add(LookupQuery(title = cleanTagTitle, artist = null, durationMs = durationMs))
        }

        // 2. Display-name based queries
        val rawName = displayName.orEmpty()
        val cleanedName = cleanText(rawName)

        if (cleanedName.any { it.isLetterOrDigit() }) {
            val parsedFromDisplay = parseFromCleanedString(cleanedName, durationMs)
            for (query in parsedFromDisplay) {
                if (variants.none { it.title.equals(query.title, ignoreCase = true) && it.artist.equals(query.artist, ignoreCase = true) }) {
                    variants.add(query)
                }
            }
        }

        // Check if overall we have any valid content with letters or digits
        val allRaw = "${tagTitle.orEmpty()} ${tagArtist.orEmpty()} ${displayName.orEmpty()}"
        if (!allRaw.any { it.isLetterOrDigit() }) {
            return emptyList()
        }

        // Deduplicate and limit to 3 variants
        val uniqueVariants = mutableListOf<LookupQuery>()
        for (v in variants) {
            if (v.title.any { it.isLetterOrDigit() }) {
                val exists = uniqueVariants.any {
                    it.title.equals(v.title, ignoreCase = true) &&
                        it.artist.equals(v.artist, ignoreCase = true)
                }
                if (!exists) {
                    uniqueVariants.add(v)
                }
            }
        }

        return uniqueVariants.take(3)
    }

    fun cleanText(raw: String): String {
        var s = raw.replace(FILE_EXT_REGEX, "")
        s = s.replace(SEPARATOR_HYPHEN_REGEX, " - ")
        s = s.replace('_', ' ')
        s = s.replace(Regex("-{2,}"), "-")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.removePrefix("-").removeSuffix("-").trim()

        // Remove bracket noise only if the whole segment inside is noise
        s = BRACKET_REGEX.replace(s) { matchResult ->
            val inner = matchResult.groupValues[1]
            if (isNoiseSegment(inner)) " " else matchResult.value
        }.trim()

        // Remove pipe noise segments
        if (s.contains('|')) {
            val pipeParts = s.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            val cleanParts = pipeParts.filterNot { isNoiseSegment(it) }
            s = cleanParts.firstOrNull() ?: pipeParts.firstOrNull().orEmpty()
        }

        // Remove trailing noise segments after a separator " - "
        while (s.contains(" - ")) {
            val lastHyphenIdx = s.lastIndexOf(" - ")
            val trailingPart = s.substring(lastHyphenIdx + 3).trim()
            if (trailingPart.isNotEmpty() && isNoiseSegment(trailingPart)) {
                s = s.substring(0, lastHyphenIdx).trim()
            } else {
                break
            }
        }

        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.removePrefix("-").removeSuffix("-").trim()
        return s
    }

    private fun parseFromCleanedString(text: String, durationMs: Long?): List<LookupQuery> {
        val list = mutableListOf<LookupQuery>()

        // 1. Check for "Title By Artist"
        val byMatch = Regex("(?i)^(.*?)\\s+by\\s+(.*)$").find(text)
        if (byMatch != null) {
            val titlePart = byMatch.groupValues[1].trim()
            val artistPart = byMatch.groupValues[2].trim()
            if (titlePart.isNotEmpty() && artistPart.isNotEmpty()) {
                list.add(LookupQuery(title = titlePart, artist = artistPart, durationMs = durationMs))
                list.add(LookupQuery(title = titlePart, artist = null, durationMs = durationMs))
                list.add(LookupQuery(title = text, artist = null, durationMs = durationMs))
                return list
            }
        }

        // 2. Check for "Artist - Title" or "Title - Artist"
        if (text.contains(" - ")) {
            var parts = text.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
            // "Channel - Artist - Title": drop the first segment if it repeats the start of the next one
            if (parts.size >= 3) {
                val firstWordCount = parts[0].split(' ').size
                val nextStart = parts[1].split(' ').take(firstWordCount).joinToString(" ")
                if (artistSimilarity(parts[0], nextStart) >= 0.8) {
                    parts = parts.drop(1)
                }
            }
            val joined = parts.joinToString(" - ")
            if (parts.size >= 2) {
                val part1 = parts[0]
                val part2 = parts.subList(1, parts.size).joinToString(" - ")

                val part1HasFeat = FEAT_HINT_REGEX.containsMatchIn(part1)
                val part2HasFeat = FEAT_HINT_REGEX.containsMatchIn(part2)

                if (part2HasFeat && !part1HasFeat) {
                    // Part 2 has feat -> part 2 is artist, part 1 is title
                    list.add(LookupQuery(title = part1, artist = part2, durationMs = durationMs))
                    list.add(LookupQuery(title = part1, artist = null, durationMs = durationMs))
                } else if (part1HasFeat && !part2HasFeat) {
                    // Part 1 has feat -> part 1 is artist, part 2 is title
                    list.add(LookupQuery(title = part2, artist = part1, durationMs = durationMs))
                    list.add(LookupQuery(title = part2, artist = null, durationMs = durationMs))
                } else {
                    // Standard: part1 is Artist, part2 is Title; also offer title = part1, artist = part2
                    list.add(LookupQuery(title = part2, artist = part1, durationMs = durationMs))
                    list.add(LookupQuery(title = part1, artist = part2, durationMs = durationMs))
                }
                list.add(LookupQuery(title = joined, artist = null, durationMs = durationMs))
                return list
            }
        }

        // 3. Plain cleaned string
        list.add(LookupQuery(title = text, artist = null, durationMs = durationMs))
        return list
    }
}
