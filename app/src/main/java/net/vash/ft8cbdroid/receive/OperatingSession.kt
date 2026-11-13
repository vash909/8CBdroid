package net.vash.ft8cbdroid.receive

import net.vash.ft8cbdroid.operate.ManualOperatingState
import net.vash.ft8cbdroid.operate.ManualPhase

fun operatingSummary(tx: ManualOperatingState, rx: ReceiveSessionState): String = when {
    tx.busy -> "${if (tx.autoSequence.active) "AutoSeq · " else ""}${when (tx.phase) {
        ManualPhase.PLAYING -> "TX audio playing"
        ManualPhase.WAITING -> "TX waiting for UTC slot"
        ManualPhase.GENERATING -> "Preparing FT8 audio"
        ManualPhase.CALIBRATING -> "Measuring speaker latency"
        ManualPhase.TONE -> "Playing test tone"
        ManualPhase.TIME_SYNC -> "Refreshing time at an RX boundary"
        ManualPhase.IDLE -> "Finishing audio operation"
    }}"
    tx.autoSequence.active -> "AutoSeq active · waiting for peer"
    tx.armed -> "TX armed · one slot only"
    else -> "${rx.source?.label ?: "RX"} · ${rx.phase.name.lowercase(java.util.Locale.ROOT)}"
}

/** A foreground user action authorizes output only for the current microphone service generation. */
class OperatingSession {
    private var generation: Long? = null
    fun authorized(owner: ReceiveSessionState): Boolean = generation != null &&
        generation == owner.generation && owner.source == ReceiveSource.MICROPHONE && owner.phase == ReceivePhase.RUNNING

    fun authorize(owner: ReceiveSessionState, visible: Boolean, promote: () -> Unit) {
        check(owner.source == ReceiveSource.MICROPHONE && owner.phase == ReceivePhase.RUNNING) {
            "Start microphone RX before enabling continuous TX."
        }
        if (authorized(owner)) return
        check(visible) { "Open the app and enable TX or AutoSeq explicitly." }
        promote()
        generation = owner.generation
    }
    fun clear() { generation = null }
}
