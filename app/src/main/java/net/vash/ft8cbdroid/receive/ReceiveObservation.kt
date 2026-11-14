package net.vash.ft8cbdroid.receive

import kotlinx.coroutines.flow.*
import net.vash.ft8cbdroid.audio.CaptureUiState
import net.vash.ft8cbdroid.core.audio.PhoneAudioPhase
import net.vash.ft8cbdroid.operate.ManualOperatingState

internal data class ReceiveObservation(val input: CaptureUiState, val owner: ReceiveSessionState,
    val tx: ManualOperatingState, val audio: PhoneAudioPhase)

/** Combine emissions are wakeups: a cached idle input must not stop a receiver already restarting. */
internal fun receiveObservations(input: StateFlow<CaptureUiState>, owner: StateFlow<ReceiveSessionState>,
    tx: StateFlow<ManualOperatingState>, audio: StateFlow<PhoneAudioPhase>,
    inputChanges: Flow<*> = input): Flow<ReceiveObservation> =
    combine(inputChanges, owner, tx.map { Triple(it.busy, it.armed, it.autoSequence.active) }.distinctUntilChanged(),
        audio) { _, _, _, _ -> Unit }
        .map { ReceiveObservation(input.value, owner.value, tx.value, audio.value) }
