package com.precord.app.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

const val SCOPE_POINTS = 160

/** Live snapshot published by the service ~30x/s and consumed by the UI and the tile. */
data class MonitorState(
    val running: Boolean = false,
    val bufferedMs: Long = 0,
    val windowMs: Long = 0,
    val sampleRate: Int = 48_000,
    val rms: Float = 0f,
    val peak: Float = 0f,
    val scope: FloatArray = FloatArray(SCOPE_POINTS),
    val saving: Boolean = false,
    val error: String? = null,
)

sealed interface SaveEvent {
    data class Saved(val clip: Clip, val note: String?) : SaveEvent
    data class Failed(val reason: String) : SaveEvent
}

/** Process-wide bus between the foreground service and the UI (same process, no binding needed). */
object Monitor {
    private val _state = MutableStateFlow(MonitorState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<SaveEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SaveEvent> = _events.asSharedFlow()

    fun update(transform: (MonitorState) -> MonitorState) = _state.update(transform)
    fun emit(e: SaveEvent) = _events.tryEmit(e)
}
