package net.vash.ft8cbdroid.operate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.time.ReceiveClock
import net.vash.ft8cbdroid.core.qso.*
import net.vash.ft8cbdroid.core.logbook.*
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureUiState

enum class ManualPhase { IDLE, CALIBRATING, GENERATING, WAITING, PLAYING, TONE, TIME_SYNC }
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
    val queuedTimeRevision: Long? = null,
    val playback: PreviewReport? = null, val sent: List<SentAudio> = emptyList(),
    val error: String? = null, val notice: String? = null,
    val autoSequence: CbAutoSequenceState = CbAutoSequenceState(),
    val qso: CbQsoSnapshot? = null, val suggestion: CbQsoSuggestion? = null, val qsoNotice: String? = null,
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

/** Foreground manual output and explicitly started, bounded acoustic AutoSeq. Nothing armed is restored. */
class ManualOperatingViewModel(
    private val engine: Ft8Engine, private val player: ScheduledAudioPreview,
    private val coordinator: PhoneAudioCoordinator, private val clock: ReceiveClock,
    private val receiveReady: () -> Boolean,
    private val prepareOutput: suspend () -> Unit = {},
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val reception: Flow<CaptureUiState> = emptyFlow(),
    private val prepareAutomaticReceive: suspend () -> Unit = { throw IllegalStateException("Automatic reception is unavailable.") },
    private val applyPendingTime: () -> Boolean = { false },
    private val awaitAutomaticRecovery: suspend () -> Unit = {},
    private val completedQso: (QsoCompletion) -> Unit = {},
) : ViewModel() {
    private val state = MutableStateFlow(ManualOperatingState())
    val uiState = state.asStateFlow()
    private var job: Job? = null
    private var guard: Job? = null
    @Volatile private var revision = 0L
    private var contextKey: Any? = null
    private var advisor: CbQsoAdvisor? = null
    private var receivingRun: Long? = null
    private var qsoClockRevision: Long? = null
    private var lastInput: CaptureUiState? = null
    private var autoJob: Job? = null
    private var autoPolicy: CbAutoSequence? = null
    private var autoAction: CbAutoSend? = null
    private var autoGeneration = 0L
    private val logTracker = QsoCompletionTracker()
    private var lastTrackedAudioHz: Int? = null
    private var lastTrackedDialHz: Long? = null
    private fun resetLogTracking() { logTracker.reset(); lastTrackedAudioHz = null; lastTrackedDialHz = null }
    private fun captureCompletion(slotUtcMillis: Long) {
        val station = state.value.station ?: return
        val snapshot = advisor?.snapshot ?: return
        val audio = lastTrackedAudioHz ?: state.value.frequency.toIntOrNull() ?: return
        val dial = lastTrackedDialHz ?: state.value.referenceDialHz
        logTracker.observe(station, snapshot, slotUtcMillis, dial, audio)?.let(completedQso)
    }
    private fun stopAutomatic(reason: String) {
        if (autoJob == null && !state.value.autoSequence.active) return
        autoGeneration++
        autoPolicy?.stop(reason); autoJob?.cancel(); autoJob = null; autoAction = null
        state.value = state.value.copy(autoSequence = (autoPolicy?.state ?: state.value.autoSequence.copy(active = false, reason = reason)))
        cancelOutput(reason)
    }
    fun startAutomatic(calling: Boolean) {
        if (state.value.busy || state.value.autoSequence.active) return
        try {
            require(state.value.canArm) { state.value.validation.orEmpty() }
            readyClock(); require(receiveReady()) { "Start live RX before AutoSeq." }
            require(calling || CbCallsign.isValid(state.value.peer)) { "Select a decoded peer before answering." }
        } catch (error: Exception) { state.value = state.value.copy(error = error.message); return }
        val selected = state.value.peer
        autoPolicy = null
        val generation = ++autoGeneration
        state.value = state.value.copy(autoSequence = CbAutoSequenceState(active = true, calling = calling, period = state.value.period, reason = "Preparing AutoSeq"), armed = false, error = null)
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                prepareAutomaticReceive()
                advisor?.reset(); resetLogTracking(); receivingRun = null; qsoClockRevision = null
                state.value = state.value.copy(peer = if (calling) "" else selected, choice = if (calling) ManualMessage.CQ else ManualMessage.REPLY,
                    freeText = false, acceptChanges = false, qsoNotice = null, notice = "AutoSeq started explicitly.")
                if (!calling) selectQsoPeer() else { lastInput?.let(::receive); refreshTemplate(); publishQso() }
                val value = state.value
                val initial = planSlot(clock, requireNotNull(value.latency), requireNotNull(value.outputId), value.period)
                val now = clock.snapshot().at(clock.source.read().monotonicNanos) / 1_000_000
                val policy = CbAutoSequence(now, initial.utcMillis, calling, value.period); autoPolicy = policy
                var recoveringSince: Long? = null
                while (isActive && policy.state.active) {
                    readyClock()
                    if (!state.value.busy) {
                        if (lastInput?.phase == CapturePhase.STARTING && policy.state.transmissions > 0) {
                            val boot = clock.source.read().monotonicNanos
                            if (recoveringSince == null) recoveringSince = boot
                            require(boot - requireNotNull(recoveringSince) < 5_000_000_000L) { "Microphone recovery timed out. AutoSeq off." }
                            delay(20); continue
                        }
                        recoveringSince = null
                        require(receiveReady()) { "Reception stopped. AutoSeq off." }
                        require(lastInput?.earlyWindows == true) { "Early receive windows are unavailable." }
                        require(lastInput?.live?.error == null) { lastInput?.live?.error.orEmpty() }
                        val utc = clock.snapshot().at(clock.source.read().monotonicNanos) / 1_000_000
                        val action = policy.tick(utc, requireNotNull(advisor).snapshot, advisor?.suggestion,
                            lastInput?.live?.lastSlotUtcMillis, false)
                        state.value = state.value.copy(autoSequence = policy.state)
                        if (!policy.state.active) {
                            if (!policy.state.completed) stop(policy.state.reason)
                            break
                        }
                        if (action != null) {
                            if (action.kind == ManualMessage.CQ) {
                                state.value = state.value.copy(choice = ManualMessage.CQ, peer = ""); refreshTemplate()
                            } else applySuggestion(requireNotNull(advisor?.suggestion))
                            state.value = state.value.copy(armed = true)
                            autoAction = action
                            sendInternal(action.utcMillis)
                        }
                    }
                    delay(20)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { stop(error.message ?: "AutoSeq failed. Restart explicitly.") }
            finally {
                if (generation == autoGeneration) {
                    autoJob = null
                    state.value = state.value.copy(autoSequence = autoPolicy?.state ?: state.value.autoSequence.copy(active = false), armed = false)
                }
            }
        }
        autoJob = next; next.start()
    }
    init { viewModelScope.launch {
        combine(reception, uiState.map { it.busy to it.available }.distinctUntilChanged()) { input, _ -> input }.collect(::receive)
    } }
    private fun publishQso() { state.value = state.value.copy(qso = advisor?.snapshot, suggestion = advisor?.suggestion) }
    private fun nextFreshSlot(): Long = runCatching {
        val now = clock.snapshot().at(clock.source.read().monotonicNanos) / 1_000_000
        Math.floorDiv(now + 14_999, 15_000) * 15_000
    }.getOrDefault(Long.MIN_VALUE)
    /** Consumes only the live local receiver's current run, never LAN or imported recordings. */
    private fun receive(input: CaptureUiState) {
        lastInput = input
        val qso = advisor ?: return
        val mapping = runCatching { clock.snapshot() }.getOrNull()
        if (qsoClockRevision != null && qsoClockRevision != mapping?.revision) {
            qso.abort(); resetLogTracking(); qsoClockRevision = null
            state.value = state.value.copy(qsoNotice = "Time correction changed or expired. Start a new QSO explicitly.")
        }
        val active = state.value.available && !state.value.busy && !input.outputBusy && input.phase == CapturePhase.CAPTURING && input.decodeLive && input.receiveRun > 0 &&
            input.receiveTimeRevision == mapping?.revision && mapping != null &&
            input.inputs.any { it.id == input.selectedId && it.kind == AudioInputKind.PHONE }
        if (!active) {
            if (receivingRun != null && !state.value.busy) {
                qso.abort(); resetLogTracking(); state.value = state.value.copy(qsoNotice = "Local reception ended. Start a new QSO explicitly.")
            } else qso.suspendReception()
            receivingRun = null; publishQso(); return
        }
        val start = input.receiveStartedUtcMillis ?: return
        val first = Math.floorDiv(start + 14_999, 15_000) * 15_000
        receivingRun = input.receiveRun; qsoClockRevision = requireNotNull(mapping).revision
        qso.bindReceiver(input.receiveRun, first)
        val now = requireNotNull(mapping).at(clock.source.read().monotonicNanos) / 1_000_000
        val txHz = state.value.frequency.toIntOrNull() ?: 0
        input.live.rows.filter { row -> !state.value.autoSequence.active ||
            Math.floorMod(row.decode.slotStartUtc.toEpochMilli() / 15_000, 2L) != if (state.value.autoSequence.period == SlotPeriod.EVEN) 0L else 1L
        }.sortedWith(compareBy<LiveDecodeRow> { it.decode.slotStartUtc }
            .thenBy { kotlin.math.abs(it.decode.frame.audioFrequencyHz - txHz) }).forEach { row ->
            val decision = qso.receive(row, input.receiveRun, now, txHz)
            if (decision.admission == CbQsoAdmission.STOP) {
                resetLogTracking()
                stopAutomatic(decision.reason); cancelOutput(decision.reason)
                state.value = state.value.copy(qsoNotice = decision.reason)
            } else if (decision.admission == CbQsoAdmission.ACCEPTED) {
                state.value = state.value.copy(qsoNotice = decision.reason)
                captureCompletion(row.decode.slotStartUtc.toEpochMilli())
            }
        }
        publishQso()
    }
    fun resetQso() {
        stopAutomatic("QSO reset. AutoSeq off.")
        cancelOutput("QSO reset. TX disabled.")
        advisor?.reset(); resetLogTracking(); advisor?.suspendReception(); receivingRun = null; qsoClockRevision = null
        state.value = state.value.copy(qsoNotice = "QSO reset. Select a peer or send CQ explicitly.")
        lastInput?.let(::receive); publishQso()
    }
    fun useSuggestion() {
        val suggestion = state.value.suggestion ?: return
        if (state.value.busy) return
        stopAutomatic("Manual suggestion selected. AutoSeq off.")
        cancelOutput("Suggested message selected. Enable TX explicitly.")
        applySuggestion(suggestion)
    }
    private fun applySuggestion(suggestion: CbQsoSuggestion) {
        state.value = state.value.copy(peer = suggestion.peer, report = java.lang.String.format(java.util.Locale.ROOT, "%+03d", suggestion.report),
            choice = suggestion.kind, text = suggestion.text, frequency = suggestion.frequencyHz.toString(), period = suggestion.period,
            freeText = false, acceptChanges = false, error = null)
    }
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
        advisor = normalized?.takeIf { it.isValid && available }?.let { CbQsoAdvisor(it, state.value.report.toIntOrNull()?.coerceIn(-50, 99) ?: -7) }
        resetLogTracking()
        receivingRun = null; qsoClockRevision = null
        state.value = state.value.copy(qsoNotice = null)
        refreshTemplate(); publishQso()
    }
    private fun edit(change: (ManualOperatingState) -> ManualOperatingState) {
        stopAutomatic("Operator changed the controls. AutoSeq off.")
        cancelOutput("Settings changed. Enable TX again explicitly.")
        state.value = change(state.value).copy(acceptChanges = false, error = null)
    }
    fun peer(value: String) {
        edit { it.copy(peer = CbCallsign.normalize(value)) }
        selectQsoPeer(); refreshTemplate()
    }
    private fun selectQsoPeer() {
        advisor?.reset(); resetLogTracking(); qsoClockRevision = null
        advisor?.selectPeer(state.value.peer, state.value.frequency.toIntOrNull() ?: 0, state.value.period, nextFreshSlot())
        state.value = state.value.copy(qsoNotice = null); lastInput?.let(::receive); publishQso()
    }
    fun selectPeer(peer: ManualPeer) {
        edit { it.copy(peer = peer.callsign, report = peer.report?.let { n -> java.lang.String.format(java.util.Locale.ROOT, "%+03d", n) } ?: it.report,
            frequency = peer.frequencyHz.toString(), period = peer.period, choice = ManualMessage.REPLY, freeText = false) }
        selectQsoPeer()
        state.value.report.toIntOrNull()?.takeIf { it in -50..99 }?.let { advisor?.report(it) }
        refreshTemplate(); publishQso()
    }
    fun report(value: String) {
        edit { it.copy(report = value.take(4)) }
        value.toIntOrNull()?.takeIf { it in -50..99 }?.let { advisor?.report(it) }
        refreshTemplate(); publishQso()
    }
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
        if (state.value.autoSequence.active) { stop("AutoSeq disabled by operator."); return }
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
        stopAutomatic("Calibration selected. AutoSeq off.")
        if (state.value.busy || !state.value.available) return
        val id = state.value.outputId ?: return
        state.value = state.value.copy(latency = null)
        runOutput(ManualPhase.CALIBRATING) {
            val measured = player.measureLatency(id)
            state.value = state.value.copy(latency = measured, notice = "Latency measured. Start RX and enable one message explicitly.")
        }
    }
    fun refreshTime() {
        if (state.value.busy || state.value.autoSequence.active) return
        runOutput(ManualPhase.TIME_SYNC) {
            state.value = state.value.copy(notice = "Time refresh processed at an idle audio boundary. TX remains disabled.")
        }
    }
    fun testTone() {
        stopAutomatic("Test tone selected. AutoSeq off.")
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
    fun send() { stopAutomatic("Manual send selected. AutoSeq off."); sendInternal() }
    private fun sendInternal(automaticUtc: Long? = null) {
        val value = state.value
        if (!value.armed || value.busy) return
        try { require(value.validation == null) { value.validation.orEmpty() }; readyClock(); require(receiveReady()) { "Start RX before sending." } }
        catch (error: Exception) { stop(error.message ?: "TX interlock changed."); return }
        val encoded = value.packed as PackResult.Encoded
        val settings = Ft8GenerationSettings(value.frequency.toFloat(), value.level.toFloat() / 100)
        val id = requireNotNull(value.outputId)
        runOutput(ManualPhase.GENERATING) { ticket ->
            val clockRevision = readyClock()
            val qsoOwner = advisor
            val qsoTicket = if (value.freeText || value.choice == ManualMessage.CUSTOM || encoded.contentChanged) null else
                qsoOwner?.prepare(value.choice, value.report.toIntOrNull().takeIf { value.choice in setOf(ManualMessage.REPORT, ManualMessage.ROGER) }, encoded.transmittedText, encoded.payload)
            if (automaticUtc != null) requireNotNull(qsoTicket) { "The automatic message is outside the qualified QSO. AutoSeq off." }
            if (qsoTicket == null) {
                qsoOwner?.abort(); resetLogTracking()
                state.value = state.value.copy(qsoNotice = "This manual output is outside the tracked QSO. Reset QSO to start a new exchange.")
            }
            publishQso()
            val pcm = withContext(worker) { engine.encode(encoded.payload, settings.audioFrequencyHz, settings.peakAmplitude) }
            currentCoroutineContext().ensureActive()
            require(clock.revision == clockRevision) { "Time correction changed during generation. Enable TX again." }
            val plan = if (automaticUtc == null) planSlot(clock, requireNotNull(value.latency), id, value.period)
                else planResponseSlot(clock, requireNotNull(value.latency), id, automaticUtc)
            state.value = state.value.copy(queuedUtcMillis = plan.utcMillis, queuedTimeRevision = plan.mapping.revision, phase = ManualPhase.WAITING)
            var final: PreviewReport? = null
            val observe: (PreviewReport) -> Unit = { report ->
                final = report
                state.update { if (ticket == revision) it.copy(playback = report, phase = if (report.playedFrames > 0) ManualPhase.PLAYING else it.phase) else it }
            }
            if (automaticUtc == null) player.playScheduled(pcm, id, PreviewSettings(), plan, clock, observe)
            else player.playWaveform(pcm, id, plan, clock, observe)
            currentCoroutineContext().ensureActive()
            val report = requireNotNull(final)
            val expectedFrames = if (automaticUtc == null) Ft8PcmSlot.sampleCount else 161280
            require(report.writtenFrames == expectedFrames && report.playedFrames >= expectedFrames) { "The complete FT8 waveform was not played. Nothing added to Sent." }
            if (ticket == revision && qsoTicket != null && advisor === qsoOwner) {
                if (qsoOwner?.sent(qsoTicket, plan.utcMillis) == true) {
                    lastTrackedAudioHz = settings.audioFrequencyHz.toInt(); lastTrackedDialHz = value.referenceDialHz
                    captureCompletion(plan.utcMillis)
                }
                publishQso()
                if (automaticUtc != null) {
                    autoPolicy?.sent(requireNotNull(autoAction), encoded.payload); autoAction = null
                    state.value = state.value.copy(autoSequence = requireNotNull(autoPolicy).state)
                }
            }
            if (ticket == revision) state.value = state.value.copy(sent = (listOf(SentAudio(plan.utcMillis, encoded.transmittedText,
                encoded.payload, settings.audioFrequencyHz, value.referenceDialHz, value.peer, report.playedFrames)) + state.value.sent).take(30),
                notice = if (automaticUtc == null) "Speaker slot completed. TX is disabled; choose and enable the next message manually."
                    else "FT8 waveform completed. AutoSeq is waiting for the peer.")
        }
    }
    private fun runOutput(phase: ManualPhase, action: suspend (Long) -> Unit) {
        cancelOutput()
        val ticket = revision
        state.value = state.value.copy(busy = true, phase = phase, error = null, notice = null, playback = null)
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val resumed = coordinator.output(true) {
                    withTimeout(15_000) { prepareOutput() }
                    if (applyPendingTime()) qsoClockRevision = clock.revision
                    action(ticket)
                }
                // Keep ownership busy until the receiver actually recovers, before the
                // policy can mistake asynchronous STARTING/IDLE notifications for Stop.
                if (resumed && state.value.autoSequence.active) awaitAutomaticRecovery()
                if (!resumed) state.value = state.value.copy(notice = "${state.value.notice.orEmpty()} Start RX explicitly to receive.")
            } catch (_: TimeoutCancellationException) {
                state.value = state.value.copy(error = "Audio preparation timed out. Start RX and retry explicitly.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: UnsatisfiedLinkError) { state.value = state.value.copy(error = "The FT8 backend is unavailable on this device.") }
            catch (error: Exception) { state.value = state.value.copy(error = error.message ?: "Audio operation failed. Check the setup and retry explicitly.") }
        }
        job = next
        next.invokeOnCompletion { viewModelScope.launch(Dispatchers.Main.immediate) {
            if (job === next) { advisor?.cancelPending(); publishQso(); job = null; state.value = state.value.copy(busy = false, phase = ManualPhase.IDLE, queuedUtcMillis = null, queuedTimeRevision = null, armed = false) }
        } }
        next.start()
    }
    fun stop(reason: String = "TX stopped. Start RX explicitly if needed.") {
        stopAutomatic(reason)
        cancelOutput(reason); advisor?.abort(); resetLogTracking(); receivingRun = null; qsoClockRevision = null; publishQso()
    }
    private fun cancelOutput(reason: String = "TX stopped. Start RX explicitly if needed.") {
        advisor?.cancelPending(); publishQso()
        revision++; guard?.cancel(); guard = null
        state.value = state.value.copy(armed = false, notice = reason)
        job?.cancel()
    }
    suspend fun stopAndJoin(reason: String = "Receive session stopped. TX disabled.") {
        val automatic = autoJob
        stop(reason); automatic?.join(); job?.join()
    }
    suspend fun stopForProfile() {
        stop("Profile changed. TX disabled."); job?.join()
        state.value = state.value.copy(outputId = null, latency = null, available = false)
    }
    override fun onCleared() { autoJob?.cancel(); job?.cancel(); guard?.cancel(); player.close() }
}
