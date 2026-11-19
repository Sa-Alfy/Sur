package io.github.saalfy.sur.download

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DownloadEvent {
    data object Busy : DownloadEvent
}

/** Identifies one download run. Only the current owner may change [DownloadManager.state]. */
class DownloadToken internal constructor()

/**
 * Process-wide download state.
 *
 * R2-11 / R2-05: every state change from a download goes through [update] with that run's
 * [DownloadToken]. Once a run is cancelled, finished, or replaced by a newer run, its token
 * stops working, so a late progress callback or terminal state from an old run cannot
 * overwrite the state of a newer run or revive "Downloading" after a cancel.
 */
object DownloadManager {
    private val lock = Any()
    private var owner: DownloadToken? = null

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    /** Starts a new run (state Resolving). Any older run loses ownership. */
    fun begin(): DownloadToken = synchronized(lock) {
        DownloadToken().also {
            owner = it
            _state.value = DownloadState.Resolving
        }
    }

    fun isOwner(token: DownloadToken): Boolean = synchronized(lock) { owner === token }

    /**
     * Sets [newState] only while [token] owns the state, then runs [whileOwner] under the same
     * lock (used for the progress notification, so none is posted after a cancel).
     * Terminal states (Idle, Done, Error) release ownership.
     * @return false when [token] is stale and nothing was changed.
     */
    fun update(token: DownloadToken, newState: DownloadState, whileOwner: () -> Unit = {}): Boolean =
        synchronized(lock) {
            if (owner !== token) return false
            _state.value = newState
            if (newState.isTerminal) owner = null
            whileOwner()
            true
        }

    /** User cancel: drops the current owner and shows Idle. */
    fun cancelActive() = synchronized(lock) {
        owner = null
        _state.value = DownloadState.Idle
    }

    /** Dismisses a Done/Error card. Ignored while a run owns the state. */
    fun reset() = synchronized(lock) {
        if (owner == null) _state.value = DownloadState.Idle
    }

    /** Sets state directly when no run owns it (tests and previews). */
    internal fun updateState(newState: DownloadState) = synchronized(lock) {
        if (owner == null) _state.value = newState
    }

    internal fun clearForTest() = synchronized(lock) {
        owner = null
        _state.value = DownloadState.Idle
    }

    fun emitEvent(event: DownloadEvent) {
        _events.tryEmit(event)
    }
}

internal val DownloadState.isTerminal: Boolean
    get() = this is DownloadState.Idle || this is DownloadState.Done || this is DownloadState.Error
