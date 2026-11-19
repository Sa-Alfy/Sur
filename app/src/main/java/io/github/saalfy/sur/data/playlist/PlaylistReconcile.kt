package io.github.saalfy.sur.data.playlist

import io.github.saalfy.sur.data.db.PlaylistEntryEntity
import io.github.saalfy.sur.data.info.SongIdentity
import kotlin.math.abs

sealed interface ReconcileAction {
    /** Set or refresh the stored identity (key, volume, duration) of a live entry. */
    data class SetIdentity(val entryId: Long, val identity: SongIdentity) : ReconcileAction

    /** Point a dead entry at the one song on the device that matches its stored identity. Keeps the position. */
    data class Relink(
        val entryId: Long,
        val newSongId: Long,
        val identity: SongIdentity,
        val fromSongId: Long,
    ) : ReconcileAction

    /** Drop a dead entry whose re-link target is already in the same playlist. */
    data class DropDuplicate(val entryId: Long) : ReconcileAction
}

/** Two MediaStore scans of the same file may report slightly different durations. */
const val DURATION_TOLERANCE_MS = 1_000L

/**
 * Plans how to reconcile playlist entries against the songs currently on the device
 * ([identities] maps a live MediaStore song id to its [SongIdentity]).
 *
 * Rule A: an entry whose song still exists gets its stored identity set or refreshed.
 * Rule B: an entry whose song is gone is re-linked only to a song that is certainly the same
 * file: same key, same storage volume (when both are known, R1-02) and a duration within
 * [DURATION_TOLERANCE_MS] (R1-03: a different song saved later under a reused path is not
 * picked up). Exactly one such song must exist. An entry with no stored key or no stored
 * duration, or with zero or several candidates, stays unresolved: an uncertain match is
 * never linked. If the target is already in the same playlist, the dead entry is dropped.
 *
 * Deterministic (input order only) and idempotent: applying the plan and planning again
 * yields no actions.
 */
fun planReconcile(
    entries: List<PlaylistEntryEntity>,
    identities: Map<Long, SongIdentity>,
): List<ReconcileAction> {
    if (identities.isEmpty()) return emptyList()
    val songsByKey: Map<String, List<Map.Entry<Long, SongIdentity>>> =
        identities.entries.groupBy { it.value.key }
    // Song ids already in each playlist. Pre-seeded with every live entry, even one that
    // comes after a dead entry in the list, so a re-link can never collide with it.
    val usedByPlaylist: MutableMap<Long, MutableSet<Long>> = mutableMapOf()
    for (entry in entries) {
        if (entry.songId in identities) {
            usedByPlaylist.getOrPut(entry.playlistId) { mutableSetOf() } += entry.songId
        }
    }
    val actions = mutableListOf<ReconcileAction>()
    for (entry in entries) {
        val live = identities[entry.songId]
        if (live != null) {
            // Rule A: the song is still on the device.
            if (entry.storedIdentity() != live) actions += ReconcileAction.SetIdentity(entry.id, live)
            continue
        }
        // Rule B: the song is gone; only a full stored fingerprint can re-link it.
        val storedKey = entry.songKey ?: continue
        val storedDuration = entry.songDurationMs ?: continue
        val candidates = songsByKey[storedKey].orEmpty().filter { (_, song) ->
            volumesCompatible(entry.songVolume, song.volume) &&
                song.durationMs != null &&
                abs(song.durationMs - storedDuration) <= DURATION_TOLERANCE_MS
        }
        if (candidates.size != 1) continue
        val (target, identity) = candidates.single()
        val used = usedByPlaylist.getOrPut(entry.playlistId) { mutableSetOf() }
        if (target in used) {
            actions += ReconcileAction.DropDuplicate(entry.id)
        } else {
            actions += ReconcileAction.Relink(entry.id, target, identity, entry.songId)
            used += target
        }
    }
    return actions
}

private fun PlaylistEntryEntity.storedIdentity(): SongIdentity? =
    songKey?.let { SongIdentity(it, songVolume, songDurationMs) }

private fun volumesCompatible(stored: String?, current: String?): Boolean =
    stored == null || current == null || stored.equals(current, ignoreCase = true)
