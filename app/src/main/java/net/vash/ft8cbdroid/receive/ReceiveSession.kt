package net.vash.ft8cbdroid.receive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReceiveSource(val label: String) { MICROPHONE("Microphone RX"), LAN("WSJT-CB LAN RX") }
enum class ReceivePhase { IDLE, STARTING, RUNNING, STOPPING }
data class ReceiveSessionState(val generation: Long = 0, val source: ReceiveSource? = null,
    val phase: ReceivePhase = ReceivePhase.IDLE, val error: String? = null) {
    val active get() = phase != ReceivePhase.IDLE
}

/** Main-thread command identity. A late service intent can never undo Stop or select a new source. */
class ReceiveSession {
    private val state = MutableStateFlow(ReceiveSessionState())
    val uiState = state.asStateFlow()
    fun request(source: ReceiveSource): Long? {
        val current = state.value
        if (current.active) return null
        val next = current.generation + 1
        state.value = ReceiveSessionState(next, source, ReceivePhase.STARTING)
        return next
    }
    fun accepts(generation: Long, source: ReceiveSource) = state.value.let {
        it.generation == generation && it.source == source && it.phase == ReceivePhase.STARTING
    }
    fun running(generation: Long) {
        if (state.value.generation == generation && state.value.phase == ReceivePhase.STARTING)
            state.value = state.value.copy(phase = ReceivePhase.RUNNING)
    }
    fun stopping(): Long {
        val next = state.value.generation + 1
        state.value = state.value.copy(generation = next, phase = ReceivePhase.STOPPING)
        return next
    }
    fun stopped(generation: Long, error: String? = null) {
        if (state.value.generation == generation) state.value = ReceiveSessionState(generation, error = error)
    }
    fun error(message: String) { state.value = state.value.copy(error = message) }
}
