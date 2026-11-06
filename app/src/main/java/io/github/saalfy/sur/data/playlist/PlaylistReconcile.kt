package io.github.saalfy.sur.data.playlist

import io.github.saalfy.sur.data.db.PlaylistEntryEntity

sealed interface ReconcileAction {
    /** Set or refresh the stored stable key of a live entry. */
    data class SetKey(val entryId: Long, val key: String) : ReconcileAction

    /** Point a dead entry at the one song on the device with its stored key. Keeps the position. */
    data class Relink(val entryId: Long, val newSongId: Long, val key: String) : ReconcileAction

    /** Drop a dead entry whose re-link target is already in the same playlist. */
    data class DropDuplicate(val entryId: Long) : ReconcileAction
}

/**
 * Plans how to reconcile playlist entries against the songs currently on the
 * device ([keyBySongId] maps a live MediaStore song id to its stable key).
 *
 * Rule A: an entry whose song still exists gets its stored key set or
 * refreshed to that song's current key.
 * Rule B: an entry whose song is gone but whose stored key belongs to exactly
 * one song on the device is re-linked to that song, keeping its position.
 * If the re-link target is already in the same playlist, the dead entry is
 * dropped instead. A key shared by 2+ songs, a key that matches nothing, or a
 * dead entry with no stored key leaves the entry alone, as does an empty key map.
 */
fun planReconcile(
    entries: List<PlaylistEntryEntity>,
    keyBySongId: Map<Long, String>,
): List<ReconcileAction> {
    if (keyBySongId.isEmpty()) return emptyList()
    val songIdsByKey: Map<String, List<Long>> =
        keyBySongId.entries.groupBy({ it.value }, { it.key })
    // Song ids already in each playlist. Pre-seeded with every live entry,
    // even one that comes after a dead entry in the list, so a re-link can
    // never collide with a song that is already there.
    val usedByPlaylist: MutableMap<Long, MutableSet<Long>> = mutableMapOf()
    for (entry in entries) {
        if (entry.songId in keyBySongId) {
            usedByPlaylist.getOrPut(entry.playlistId) { mutableSetOf() } += entry.songId
        }
    }
    val actions = mutableListOf<ReconcileAction>()
    for (entry in entries) {
        val key = keyBySongId[entry.songId]
        if (key != null) {
            // Rule A: the song is still on the device.
            if (entry.songKey != key) actions += ReconcileAction.SetKey(entry.id, key)
            continue
        }
        // Rule B: the song is gone; only a stored key can re-link it.
        val storedKey = entry.songKey ?: continue
        val candidates = songIdsByKey[storedKey].orEmpty()
        if (candidates.size != 1) continue
        val target = candidates.single()
        val used = usedByPlaylist.getOrPut(entry.playlistId) { mutableSetOf() }
        if (target in used) {
            actions += ReconcileAction.DropDuplicate(entry.id)
        } else {
            actions += ReconcileAction.Relink(entry.id, target, storedKey)
            used += target
        }
    }
    return actions
}
