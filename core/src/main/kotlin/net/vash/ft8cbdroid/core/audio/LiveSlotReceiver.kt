package net.vash.ft8cbdroid.core.audio

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.message.CbMessageSession
import net.vash.ft8cbdroid.core.recording.*

data class LiveDecodeRow(val decode: RecordedDecode, val timeSource: String, val uncertaintyMillis: Double?, val capturedSamples: Int = Ft8PcmSlot.sampleCount)
data class LiveReceiveState(
    val decodingUtcMillis: Long? = null,
    val lastSlotUtcMillis: Long? = null,
    val decodedSlots: Long = 0,
    val skippedSlots: Long = 0,
    val failedSlots: Long = 0,
    val rows: List<LiveDecodeRow> = emptyList(),
    val decodeMillis: Long? = null,
    val learnedCalls: Int = 0,
    val hashCapacityReached: Boolean = false,
    val error: String? = null,
)

/** One serial decoder and one latest pending slot. Audio submission never waits for DSP or UI. */
class LiveSlotReceiver(
    engine: Ft8Engine,
    scope: CoroutineScope,
    stationCall: String?,
    private val profile: Ft8DecodeProfile,
    worker: CoroutineDispatcher = Dispatchers.Default,
) {
    private val state = MutableStateFlow(LiveReceiveState())
    val uiState = state.asStateFlow()
    private val dropped = AtomicLong()
    private data class Input(val utcStartMillis: Long, val source: String, val uncertaintyMillis: Double?,
        val slot: Ft8PcmSlot? = null, val window: Ft8ReceiveWindow? = null)
    private val pending = Channel<Input>(1, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }
    // Validate before launching a child, so a bad station becomes the capture owner's visible error.
    private val session = CbMessageSession(stationCall)
    private val job = scope.launch(worker) {
        // A run owns its hash session; files, LAN and later runs cannot access it.
        for (slot in pending) {
            state.update { it.copy(decodingUtcMillis = slot.utcStartMillis, skippedSlots = dropped.get(), error = null) }
            try {
                val channel = slot.window?.let { engine.decodeWindow(it, profile) } ?: engine.decode(requireNotNull(slot.slot), profile)
                currentCoroutineContext().ensureActive()
                val result = interpretSlot(channel, RecordedSlotContext(Instant.ofEpochMilli(slot.utcStartMillis)), session)
                session.commitPending()
                val rows = result.decodes.map { LiveDecodeRow(it, slot.source, slot.uncertaintyMillis, if (slot.window != null) Ft8ReceiveWindow.sampleCount else Ft8PcmSlot.sampleCount) }
                state.update { it.copy(decodingUtcMillis = null, lastSlotUtcMillis = slot.utcStartMillis,
                    decodedSlots = it.decodedSlots + 1, skippedSlots = dropped.get(), rows = (rows + it.rows).take(MAX_ROWS),
                    decodeMillis = result.decodeMillis, learnedCalls = session.learnedCount,
                    hashCapacityReached = session.capacityReached, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: UnsatisfiedLinkError) { failed(slot, "The local FT8 decoder is unavailable. Stop and retry reception.") }
            catch (error: Exception) {
                failed(slot, when (error) {
                    is Ft8EngineBusyException, is Ft8EngineTimeoutException -> error.message ?: "Local decode failed."
                    else -> "Could not decode this local slot. Reception continues with the next complete slot."
                })
            }
        }
    }
    init { job.invokeOnCompletion { pending.cancel() } }
    fun offer(slot: CompletedUtcSlot) {
        if (!job.isActive) return
        pending.trySend(Input(slot.utcStartMillis, slot.source, slot.uncertaintyMillis, slot = slot.pcm))
        state.update { it.copy(skippedSlots = dropped.get()) }
    }
    fun offer(slot: CompletedReceiveWindow) {
        if (!job.isActive) return
        pending.trySend(Input(slot.utcStartMillis, slot.source, slot.uncertaintyMillis, window = slot.pcm))
        state.update { it.copy(skippedSlots = dropped.get()) }
    }
    private fun failed(slot: Input, message: String) {
        state.update { it.copy(decodingUtcMillis = null, lastSlotUtcMillis = slot.utcStartMillis,
            failedSlots = it.failedSlots + 1, skippedSlots = dropped.get(), decodeMillis = null, error = message) }
    }
    fun cancel() { pending.cancel(); job.cancel() }
    companion object { const val MAX_ROWS = 60 }
}
