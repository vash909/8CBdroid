package net.vash.ft8cbdroid.core.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

enum class PhoneAudioPhase { IDLE, RELEASING_INPUT, OUTPUT, RECOVERING }

/** The receiver supplies a one-use recovery attempt, bound to its current configuration. */
interface PhoneAudioReceiver {
    fun blockStarts(blocked: Boolean)
    suspend fun pause(): (() -> Boolean)?
}

/** Audio owner shared by explicit operating sessions and foreground-only bench output.
 * Call on the same dispatcher as receiver controls. Output must return only after device cleanup.
 */
class PhoneAudioCoordinator(private val receiver: PhoneAudioReceiver) {
    private val state = MutableStateFlow(PhoneAudioPhase.IDLE)
    val phase = state.asStateFlow()

    suspend fun output(resumeReceive: Boolean, play: suspend () -> Unit): Boolean {
        check(state.compareAndSet(PhoneAudioPhase.IDLE, PhoneAudioPhase.RELEASING_INPUT)) {
            "Phone audio is busy. Stop the active operation first."
        }
        try {
            receiver.blockStarts(true)
            val recovery = withTimeout(15_000) { receiver.pause() }
            currentCoroutineContext().ensureActive()
            state.value = PhoneAudioPhase.OUTPUT
            play()
            currentCoroutineContext().ensureActive()
            if (!resumeReceive || recovery == null) return false
            state.value = PhoneAudioPhase.RECOVERING
            // Exclude the output tail/room echo. UTC assembly then discards its initial partial slot.
            delay(RECOVERY_MILLIS)
            receiver.blockStarts(false)
            return recovery()
        } finally {
            receiver.blockStarts(false)
            state.value = PhoneAudioPhase.IDLE
        }
    }

    companion object { const val RECOVERY_MILLIS = 500L }
}
