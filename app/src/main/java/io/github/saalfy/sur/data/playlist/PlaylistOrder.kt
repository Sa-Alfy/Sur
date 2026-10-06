package io.github.saalfy.sur.data.playlist

/** Returns a copy of [items] with the element at [from] moved to index [to]. */
fun <T> moveItem(items: List<T>, from: Int, to: Int): List<T> {
    require(from in items.indices) { "from=$from out of bounds for size ${items.size}" }
    require(to in items.indices) { "to=$to out of bounds for size ${items.size}" }
    if (from == to) return items.toList()
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Moves the entry at [from] to [to] and returns every entry id paired with its new
 * position, numbered contiguously from 0.
 */
fun reorderPositions(entryIds: List<Long>, from: Int, to: Int): List<Pair<Long, Int>> =
    moveItem(entryIds, from, to).mapIndexed { position, id -> id to position }
