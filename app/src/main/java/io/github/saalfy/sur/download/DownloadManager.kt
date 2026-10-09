package io.github.saalfy.sur.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DownloadManager {
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    fun updateState(newState: DownloadState) {
        _state.value = newState
    }

    fun reset() {
        _state.value = DownloadState.Idle
    }
}
