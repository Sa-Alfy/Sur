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

object DownloadManager {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DownloadEvent> = _events.asSharedFlow()

    fun updateState(newState: DownloadState) {
        _state.value = newState
    }

    fun emitEvent(event: DownloadEvent) {
        _events.tryEmit(event)
    }

    fun reset() {
        _state.value = DownloadState.Idle
    }
}
