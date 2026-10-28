package io.github.saalfy.sur.data.info.cleaner

/**
 * Similarity of two names in 0.0..1.0: 1 - editDistance / maxLength, after lowercasing
 * and dropping everything that is not a letter or digit.
 * Returns 0.0 if either name is null or blank.
 */
fun artistSimilarity(a: String?, b: String?): Double {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return 0.0
    val x = normalizeName(a)
    val y = normalizeName(b)
    if (x.isEmpty() || y.isEmpty()) return 0.0
    val maxLen = maxOf(x.length, y.length)
    return 1.0 - editDistance(x, y).toDouble() / maxLen
}

private fun normalizeName(s: String): String =
    s.lowercase().filter { it.isLetterOrDigit() }

private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
}
