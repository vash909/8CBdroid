package net.vash.ft8cbdroid.operate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.time.ReceiveClock

enum class ManualPhase { IDLE, CALIBRATING, GENERATING, WAITING, PLAYING, TONE }
data class SentAudio(val utcMillis: Long, val text: String, val payload: Ft8Payload77,
    val frequencyHz: Float, val referenceDialHz: Long, val peer: String, val frames: Long)
data class ManualOperatingState(
    val station: StationIdentity? = null, val available: Boolean = false,
    val referenceDialHz: Long = 27_265_000, val peer: String = "", val report: String = "-07",
    val choice: ManualMessage = ManualMessage.CQ, val text: String = "", val freeText: Boolean = false,
    val acceptChanges: Boolean = false, val frequency: String = "1500", val level: String = "40",
    val period: SlotPeriod = SlotPeriod.EVEN, val outputs: List<PreviewOutput> = emptyList(),
    val outputId: Int? = null, val latency: OutputLatency? = null, val armed: Boolean = false,
    val busy: Boolean = false, val phase: ManualPhase = ManualPhase.IDLE, val queuedUtcMillis: Long? = null,
    val playback: PreviewReport? = null, val sent: List<SentAudio> = emptyList(),
    val error: String? = null, val notice: String? = null,
) {
    val packed get() = CbMessageCodec.pack(text, if (freeText) EncodingMode.FREE_TEXT else EncodingMode.AUTOMATIC)
    val templates get() = runCatching { manualMessages(requireNotNull(station), peer, report.toInt()) }.getOrDefault(emptyMap())
    val validation: String? get() = runCatching {
        require(available) { "Select a Phone input/output profile and the built-in microphone." }
        require(station?.isValid == true) { "Save a complete CB callsign and locator in Station first." }
        require(peer.isEmpty() || CbCallsign.isValid(peer) && peer != station.callsign) { "Enter a complete CB peer different from your own callsign." }
        require(choice == ManualMessage.CQ || choice == ManualMessage.CUSTOM || peer.isNotEmpty()) { "Select or enter a CB peer first." }
        require(report.toIntOrNull()?.let { it in -50..99 } == true) { "Report must be between -50 and +99 dB." }
        Ft8GenerationSettings(frequency.toFloatOrNull() ?: Float.NaN, (level.toFloatOrNull() ?: Float.NaN) / 100)
        val encoded = packed
        require(encoded is PackResult.Encoded) { (encoded as PackResult.Unsupported).reason }
        require(!encoded.contentChanged || acceptChanges) { "Confirm the encoded content changes before enabling TX." }
        require(outputs.any { it.id == outputId }) { "Select the phone speaker explicitly." }
        require(latency != null) { "Measure speaker latency first." }
    }.exceptionOrNull()?.message
    val canArm get() = !busy && validation == null
}

/** Foreground, one message at a time. No AutoSeq, implicit retries or restored armed state. */
class ManualOperatingViewModel(
    private val engine: Ft8Engine, private val player: ScheduledAudioPreview,
    private val coordinator: PhoneAudioCoordinator, private val clock: ReceiveClock,
    private val receiveReady: () -> Boolean,
    private val prepareOutput: suspend () -> Unit = {},
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val state = MutableStateFlow(ManualOperatingState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    private var guard: Job? = null
    @Volatile private var revision = 0L
    private var contextKey: Any? = null
    init { viewModelScope.launch {
        player.outputs.collect { outputs ->
            if (state.value.outputs != outputs) {
                stop("Speaker inventory changed. Select and calibrate again.")
                state.value = state.value.copy(outputs = outputs, outputId = null, latency = null)
            }
        }
    } }
    fun context(station: StationIdentity?, available: Boolean, dialHz: Long, key: Any) {
        val normalized = station?.normalized()
        val next = listOf(normalized, available, dialHz, key)
        if (next == contextKey) return
        val changedStation = state.value.station != normalized
        contextKey = next
        stop("Operating setup changed. Select the speaker, calibrate and enable TX again.")
        state.value = state.value.copy(station = normalized, available = available, referenceDialHz = dialHz,
            outputId = null, latency = null, peer = if (changedStation) "" else state.value.peer,
            sent = if (changedStation) emptyList() else state.value.sent, acceptChanges = false)
        refreshTemplate()
    }
    private fun edit(change: (ManualOperatingState) -> ManualOperatingState) {
        stop("Settings changed. Enable TX again explicitly.")
        state.value = change(state.value).copy(acceptChanges = false, error = null)
    }
    fun peer(value: String) { edit { it.copy(peer = CbCallsign.normalize(value)) }; refreshTemplate() }
    fun selectPeer(peer: ManualPeer) {
        edit { it.copy(peer = peer.callsign, report = peer.report?.let { n -> java.lang.String.format(java.util.Locale.ROOT, "%+03d", n) } ?: it.report,
            frequency = peer.frequencyHz.toString(), period = peer.period, choice = ManualMessage.REPLY, freeText = false) }
        refreshTemplate()
    }
    fun report(value: String) { edit { it.copy(report = value.take(4)) }; refreshTemplate() }
    fun choose(value: ManualMessage) { edit { it.copy(choice = value, freeText = false) }; refreshTemplate() }
    fun custom(value: String) { edit { it.copy(text = value.take(37), choice = ManualMessage.CUSTOM) } }
    fun freeText(value: Boolean) { edit { it.copy(freeText = value, choice = ManualMessage.CUSTOM) } }
    fun acceptChanges(value: Boolean) { stop("Content confirmation changed. Enable TX explicitly."); state.value = state.value.copy(acceptChanges = value) }
    fun frequency(value: String) { if (value != state.value.frequency) edit { it.copy(frequency = value.take(7)) } }
    fun level(value: String) { edit { it.copy(level = value.take(4)) } }
    fun period(value: SlotPeriod) { if (value != state.value.period) edit { it.copy(period = value) } }
    fun output(id: Int?) {
        if (id != null && state.value.outputs.none { it.id == id }) return
        if (id != state.value.outputId) edit { it.copy(outputId = id, latency = null) }
    }
    private fun refreshTemplate() {
        if (state.value.choice != ManualMessage.CUSTOM)
            state.value = state.value.copy(text = state.value.templates[state.value.choice].orEmpty(), freeText = false, acceptChanges = false)
    }
    private fun readyClock(): Long {
        val value = state.value
        clock.snapshot()
        requireNotNull(value.latency).validate(clock.source.read().monotonicNanos, requireNotNull(value.outputId))
        return clock.revision
    }
    fun enableTx() {
        if (state.value.armed) { stop("TX disabled."); return }
        if (!state.value.canArm) { state.value = state.value.copy(error = state.value.validation); return }
        try {
            require(receiveReady()) { "Start live FT8 RX on the built-in microphone before enabling TX." }
            val clockRevision = readyClock()
            state.value = state.value.copy(armed = true, error = null, notice = "One message armed. Press Send one slot; nothing repeats automatically.")
            guard?.cancel()
            guard = viewModelScope.launch {
                while (state.value.armed) {
                    delay(100)
                    try {
                        require(readyClock() == clockRevision) { "Time correction changed. Enable TX again." }
                        if (!state.value.busy) require(receiveReady()) { "Reception stopped. Enable TX again after starting RX." }
                    } catch (error: Exception) { stop(error.message ?: "TX interlock changed.") }
                }
            }
        } catch (error: Exception) { state.value = state.value.copy(error = error.message ?: "Synchronize NTP/GPS and calibrate the speaker first.") }
    }
    fun calibrate() {
        if (state.value.busy || !state.value.available) return
        val id = state.value.outputId ?: return
        state.value = state.value.copy(latency = null)
        runOutput(ManualPhase.CALIBRATING) {
            val measured = player.measureLatency(id)
            state.value = state.value.copy(latency = measured, notice = "Latency measured. Start RX and enable one message explicitly.")
        }
    }
    fun testTone() {
        val value = state.value
        if (value.busy || !value.available) return
        val id = value.outputId ?: return
        val settings = try { Ft8GenerationSettings(value.frequency.toFloat(), value.level.toFloat() / 100) }
        catch (_: Exception) { state.value = value.copy(error = "Enter an audio frequency from 200 to 2900 Hz and level from 1 to 100%."); return }
        runOutput(ManualPhase.TONE) {
            player.testTone(id, settings.audioFrequencyHz, settings.peakAmplitude)
            state.value = state.value.copy(notice = "Two-second test tone finished. It is not a sent FT8 message.")
        }
    }
    fun send() {
        val value = state.value
        if (!value.armed || value.busy) return
        val clockRevision: Long
        try { require(value.validation == null) { value.validation.orEmpty() }; clockRevision = readyClock(); require(receiveReady()) { "Start RX before sending." } }
        catch (error: Exception) { stop(error.message ?: "TX interlock changed."); return }
        val encoded = value.packed as PackResult.Encoded
        val settings = Ft8GenerationSettings(value.frequency.toFloat(), value.level.toFloat() / 100)
        val id = requireNotNull(value.outputId)
        runOutput(ManualPhase.GENERATING) { ticket ->
            val pcm = withContext(worker) { engine.encode(encoded.payload, settings.audioFrequencyHz, settings.peakAmplitude) }
            currentCoroutineContext().ensureActive()
            require(clock.revision == clockRevision) { "Time correction changed during generation. Enable TX again." }
            val plan = planSlot(clock, requireNotNull(value.latency), id, value.period)
            state.value = state.value.copy(queuedUtcMillis = plan.utcMillis, phase = ManualPhase.WAITING)
            var final: PreviewReport? = null
            player.playScheduled(pcm, id, PreviewSettings(), plan, clock) { report ->
                final = report
                state.update { if (ticket == revision) it.copy(playback = report, phase = if (report.playedFrames > 0) ManualPhase.PLAYING else it.phase) else it }
            }
            currentCoroutineContext().ensureActive()
            val report = requireNotNull(final)
            require(report.writtenFrames == Ft8PcmSlot.sampleCount && report.playedFrames >= Ft8PcmSlot.sampleCount) { "The complete FT8 slot was not played. Nothing added to Sent." }
            if (ticket == revision) state.value = state.value.copy(sent = (listOf(SentAudio(plan.utcMillis, encoded.transmittedText,
                encoded.payload, settings.audioFrequencyHz, value.referenceDialHz, value.peer, report.playedFrames)) + state.value.sent).take(30),
                notice = "Speaker slot completed. TX is disabled; choose and enable the next message manually.")
        }
    }
    private fun runOutput(phase: ManualPhase, action: suspend (Long) -> Unit) {
        stop()
        val ticket = revision
        state.value = state.value.copy(busy = true, phase = phase, error = null, notice = null, playback = null)
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val resumed = coordinator.output(true) {
                    withTimeout(15_000) { prepareOutput() }
                    action(ticket)
                }
                if (!resumed) state.value = state.value.copy(notice = "${state.value.notice.orEmpty()} Start RX explicitly to receive.")
            } catch (_: TimeoutCancellationException) {
                state.value = state.value.copy(error = "Audio preparation timed out. Start RX and retry explicitly.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: UnsatisfiedLinkError) { state.value = state.value.copy(error = "The FT8 backend is unavailable on this device.") }
            catch (error: Exception) { state.value = state.value.copy(error = error.message ?: "Audio operation failed. Check the setup and retry explicitly.") }
        }
        job = next
        next.invokeOnCompletion { viewModelScope.launch(Dispatchers.Main.immediate) {
            if (job === next) { job = null; state.value = state.value.copy(busy = false, phase = ManualPhase.IDLE, queuedUtcMillis = null, armed = false) }
        } }
        next.start()
    }
    fun stop(reason: String = "TX stopped. Start RX explicitly if needed.") {
        revision++; guard?.cancel(); guard = null
        state.value = state.value.copy(armed = false, notice = reason)
        job?.cancel()
    }
    suspend fun stopForProfile() {
        stop("Profile changed. TX disabled."); job?.join()
        state.value = state.value.copy(outputId = null, latency = null, available = false)
    }
    override fun onCleared() { job?.cancel(); guard?.cancel(); player.close() }
}
