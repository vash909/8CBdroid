package net.vash.ft8cbdroid.audio

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.time.*
import net.vash.ft8cbdroid.core.Ft8Engine
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.dsp.NativeFt8Engine

enum class CapturePhase { IDLE, STARTING, CAPTURING, STOPPING }
data class CaptureUiState(
    val inputs: List<AudioInput> = emptyList(),
    val selectedId: Int? = null,
    val phase: CapturePhase = CapturePhase.IDLE,
    val awaitingPermission: Boolean = false,
    val exporting: Boolean = false,
    val metrics: CaptureMetrics? = null,
    val error: String? = null,
    val notice: String? = null,
    val assembleUtc: Boolean = false,
    val slots: UtcSlotProgress? = null,
    val latestSlotUtcMillis: Long? = null,
    val slotSource: String? = null,
    val slotUncertaintyMillis: Double? = null,
    val decodeLive: Boolean = false,
    val liveProfile: Ft8DecodeProfile = Ft8DecodeProfile.STANDARD,
    val live: LiveReceiveState = LiveReceiveState(),
    val receiveRun: Long = 0,
    val receiveStartedUtcMillis: Long? = null,
    val receiveTimeRevision: Long? = null,
    val spectrum: SpectrumState = SpectrumState(),
    val spectrumView: SpectrumView = SpectrumView(),
    val outputBusy: Boolean = false,
    val earlyWindows: Boolean = false,
) {
    val canConfigure get() = phase == CapturePhase.IDLE && !awaitingPermission && !exporting && !outputBusy
    val canStart get() = canConfigure && inputs.any { it.id == selectedId }
    val canExportSlot get() = canConfigure && latestSlotUtcMillis != null
    val canExport get() = canConfigure && (metrics?.retainedSamples ?: 0) > 0
}
fun interface CaptureWriter { suspend fun write(document: String, bytes: ByteArray) }

private data class CapturePublication(val metrics: CaptureMetrics, val progress: UtcSlotProgress?,
    val utcStartMillis: Long?, val source: String?, val uncertaintyMillis: Double?)

/** Each start is explicit. Permission callbacks and late worker updates cannot restart stopped capture. */
class CaptureViewModel(private val capture: MicrophoneCapture, private val writer: CaptureWriter, private val clock: ReceiveClock? = null,
    private val engine: Ft8Engine? = null, private val worker: CoroutineDispatcher = Dispatchers.Default, private val startSession: (() -> Boolean)? = null) : ViewModel() {
    private val state = MutableStateFlow(CaptureUiState())
    val uiState = state.asStateFlow()
    // Fast observations have their own UI subscribers; only controls invalidate the app shell.
    val controls = uiState.distinctUntilChangedBy {
        it.copy(metrics = null, slots = null, spectrum = SpectrumState()) to it.canExport
    }
    private var job: Job? = null
    @Volatile private var generation = 0L
    private var stationCall: String? = null
    private var discard = false
    private var pendingExport: ByteArray? = null
    private var slotExport = false
    val isChoosingDocument get() = pendingExport != null
    private var latestSlot: CompletedUtcSlot? = null
    private var preferredKind: AudioInputKind? = null
    private var recoveryRevision = 0L
    fun blockForOutput(blocked: Boolean) { state.value = state.value.copy(outputBusy = blocked) }
    private var sharedCoordinator: PhoneAudioCoordinator? = null
    /** Retained with the receiver, so operating and bench outputs share ownership across Activity recreation. */
    fun audioCoordinator(permissionGranted: () -> Boolean): PhoneAudioCoordinator = sharedCoordinator ?: PhoneAudioCoordinator(object : PhoneAudioReceiver {
        override fun blockStarts(blocked: Boolean) = blockForOutput(blocked)
        override suspend fun pause() = pauseForOutput(permissionGranted)
    }).also { sharedCoordinator = it }

    /** Freeze a recovery request, discard partial/self-TX slots and join actual input/DSP cleanup. */
    suspend fun pauseForOutput(permissionGranted: () -> Boolean): (() -> Boolean)? {
        val before = state.value
        val eligible = before.phase == CapturePhase.CAPTURING && !before.awaitingPermission &&
            before.inputs.any { it.id == before.selectedId && it.kind == AudioInputKind.PHONE }
        val selected = before.selectedId
        val correction = if (before.assembleUtc) runCatching { clock?.snapshot() }.getOrNull() else null
        val previous = job
        stop("Microphone paused for speaker output. Partial slots and local hash context discarded.", clear = true)
        pendingExport = null
        val revision = recoveryRevision
        previous?.join()
        uiState.first { it.phase == CapturePhase.IDLE }
        if (!eligible) return null
        var used = false
        return recover@{
            if (used) return@recover false
            used = true
            if (revision != recoveryRevision || !permissionGranted() || state.value.selectedId != selected ||
                !state.value.canStart || state.value.inputs.none { it.id == selected && it.kind == AudioInputKind.PHONE } ||
                (before.assembleUtc && (correction == null || clock?.acceptsRecovery(correction.revision) != true))) {
                state.value = state.value.copy(notice = "Reception was not resumed. Check the input, permission and time, then start explicitly.")
                return@recover false
            }
            start()
            state.value = state.value.copy(notice = "Resuming microphone after speaker cleanup. Waiting for a new complete slot.")
            true
        }
    }
    /** Explicit AutoSeq startup restarts a fresh timestamped receiver; no previous partial is reused. */
    suspend fun prepareAutomaticReceive(permissionGranted: Boolean) {
        require(permissionGranted) { "Allow microphone access and start live RX before AutoSeq." }
        require(state.value.inputs.any { it.id == state.value.selectedId && it.kind == AudioInputKind.PHONE })
        if (!state.value.earlyWindows || state.value.phase != CapturePhase.CAPTURING) {
            val previous = job
            stop("Preparing early receive windows for AutoSeq.", clear = true); previous?.join()
            uiState.first { it.phase == CapturePhase.IDLE }
            state.value = state.value.copy(earlyWindows = true, assembleUtc = true, decodeLive = true)
            requestStart(true, early = true)
        }
        withTimeout(5000) { uiState.first { it.phase == CapturePhase.CAPTURING || it.error != null } }
        require(state.value.phase == CapturePhase.CAPTURING && state.value.error == null) { state.value.error ?: "Could not start live RX." }
    }
    /** Wait for the coordinator's existing recovery; never start a stopped receiver here. */
    suspend fun awaitAutomaticRecovery() {
        withTimeout(5000) { uiState.first { it.phase != CapturePhase.STARTING || it.error != null } }
        require(state.value.phase == CapturePhase.CAPTURING && state.value.error == null && state.value.earlyWindows) {
            state.value.error ?: "Microphone recovery failed. AutoSeq off."
        }
    }
    fun assembleUtc(enabled: Boolean) {
        if (!state.value.canConfigure || clock == null) return
        recoveryRevision++
        latestSlot = null
        state.value = state.value.copy(assembleUtc = enabled, decodeLive = state.value.decodeLive && enabled,
            live = LiveReceiveState(), spectrum = SpectrumState(), slots = null, latestSlotUtcMillis = null)
    }
    fun decodeLive(enabled: Boolean) {
        if (!state.value.canConfigure || clock == null || engine == null) return
        recoveryRevision++
        latestSlot = null
        state.value = state.value.copy(decodeLive = enabled, assembleUtc = state.value.assembleUtc || enabled,
            live = LiveReceiveState(), spectrum = SpectrumState(), slots = null, latestSlotUtcMillis = null, error = null, notice = null)
    }
    fun liveProfile(profile: Ft8DecodeProfile) {
        if (!state.value.canConfigure) return
        recoveryRevision++
        state.value = state.value.copy(liveProfile = profile, live = LiveReceiveState(), spectrum = SpectrumState())
    }
    fun spectrumView(value: SpectrumView) { state.value = state.value.copy(spectrumView = value) }
    fun changeStation(call: String?) {
        val normalized = call?.takeIf { it.isNotBlank() }
        if (normalized == stationCall) return
        stationCall = normalized
        stop("Station changed. Restart microphone reception explicitly.", clear = true)
    }
    init {
        viewModelScope.launch {
            capture.inputs.collect { inputs ->
                if (inputs != state.value.inputs) recoveryRevision++
                state.value = state.value.copy(inputs = inputs, selectedId = preferredKind?.let { kind ->
                    inputs.find { it.id == state.value.selectedId && it.kind == kind }?.id ?: inputs.firstOrNull { it.kind == kind }?.id
                } ?: state.value.selectedId.takeIf { preferredKind == null } ?: inputs.firstOrNull().takeIf { preferredKind == null }?.id)
            }
        }
    }
    fun select(id: Int) {
        if (!state.value.canConfigure || state.value.inputs.none { it.id == id } || state.value.selectedId == id) return
        preferredKind = state.value.inputs.first { it.id == id }.kind.takeUnless { it == AudioInputKind.UNKNOWN }
        recoveryRevision++
        capture.clear(); pendingExport = null; latestSlot = null
        state.value = state.value.copy(selectedId = id, metrics = null, slots = null, latestSlotUtcMillis = null,
            live = LiveReceiveState(), spectrum = SpectrumState(), error = null, notice = null)
    }
    /** Return true only when the Activity must launch Android's permission request. */
    fun requestStart(permissionGranted: Boolean): Boolean = requestStart(permissionGranted, early = false)
    private fun requestStart(permissionGranted: Boolean, early: Boolean): Boolean {
        if (!state.value.canStart) return false
        pendingExport = null
        capture.clear()
        state.value = state.value.copy(metrics = null, error = null, notice = null, earlyWindows = early)
        if (!permissionGranted) {
            state.value = state.value.copy(awaitingPermission = true)
            return true
        }
        start(); return false
    }
    fun permissionResult(granted: Boolean) {
        if (!state.value.awaitingPermission) return
        state.value = state.value.copy(awaitingPermission = false)
        if (granted) start() else state.value = state.value.copy(
            error = "Microphone permission denied. Press Start microphone to retry, or allow Microphone in Android app settings.")
    }
    private fun start() {
        val id = state.value.selectedId ?: return
        if (!state.value.canStart) return
        if (startSession?.invoke() == false) return
        val ticket = ++generation
        discard = false; capture.clear(); latestSlot = null
        state.value = state.value.copy(slots = null, latestSlotUtcMillis = null, slotSource = null, slotUncertaintyMillis = null,
            live = LiveReceiveState(), spectrum = SpectrumState())
        val liveEnabled = state.value.decodeLive
        val earlyEnabled = state.value.earlyWindows && liveEnabled
        val profile = state.value.liveProfile
        val call = stationCall
        state.value = state.value.copy(phase = CapturePhase.STARTING, metrics = null)
        val completedSlot = AtomicReference<CompletedUtcSlot?>(null)
        val updates = MutableStateFlow<CapturePublication?>(null)
        var assembler: UtcSlotAssembler? = null
        var earlyAssembler: UtcReceiveWindowAssembler? = null
        val next = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var receiver: LiveSlotReceiver? = null
            var liveRender: Job? = null
            var spectrum: SpectrumMonitor? = null
            var spectrumRender: Job? = null
            val render = launch {
                updates.collect { publication ->
                    if (publication != null && ticket == generation && state.value.phase in listOf(CapturePhase.STARTING, CapturePhase.CAPTURING)) {
                        state.value = state.value.copy(phase = CapturePhase.CAPTURING, metrics = publication.metrics,
                            slots = publication.progress, latestSlotUtcMillis = publication.utcStartMillis,
                            slotSource = publication.source, slotUncertaintyMillis = publication.uncertaintyMillis)
                    }
                }
            }
            try {
                if (liveEnabled) {
                    val mapping = requireNotNull(clock).snapshot()
                    state.value = state.value.copy(receiveRun = ticket,
                        receiveStartedUtcMillis = mapping.at(clock.source.read().monotonicNanos) / 1_000_000,
                        receiveTimeRevision = mapping.revision)
                    val live = LiveSlotReceiver(requireNotNull(engine), this, call, profile, worker)
                    receiver = live
                    liveRender = launch {
                        live.uiState.collect { result ->
                            if (ticket == generation && state.value.phase in listOf(CapturePhase.STARTING, CapturePhase.CAPTURING))
                                state.value = state.value.copy(live = result)
                        }
                    }
                }
                if (state.value.assembleUtc) assembler = UtcSlotAssembler(requireNotNull(clock)) {
                    completedSlot.set(it); if (!earlyEnabled) receiver?.offer(it)
                }
                if (earlyEnabled) earlyAssembler = UtcReceiveWindowAssembler(requireNotNull(clock)) { receiver?.offer(it) }
                // Conflate UI observations without suspending the hardware reader on the main thread.
                val update: suspend (CaptureMetrics) -> Unit = { metrics ->
                    if (ticket == generation && state.value.phase in listOf(CapturePhase.STARTING, CapturePhase.CAPTURING)) {
                        val slot = completedSlot.get()
                        updates.value = CapturePublication(metrics, assembler?.progress(), slot?.utcStartMillis,
                            slot?.source, slot?.uncertaintyMillis)
                    }
                }
                val slotBuilder = assembler
                if (capture is PcmMicrophoneCapture) {
                    val monitor = SpectrumMonitor(this, worker); spectrum = monitor
                    spectrumRender = launch {
                        monitor.uiState.collect { value ->
                            if (ticket == generation && state.value.phase in listOf(CapturePhase.STARTING, CapturePhase.CAPTURING))
                                state.value = state.value.copy(spectrum = value)
                        }
                    }
                    capture.capturePcm(id, update, { monitor.offer(it) }, slotBuilder?.let { builder ->
                        { chunk -> if (ticket == generation && state.value.phase in listOf(CapturePhase.STARTING, CapturePhase.CAPTURING)) { builder.push(chunk); earlyAssembler?.push(chunk) } }
                    })
                } else if (slotBuilder != null) {
                    val timed = capture as? TimestampedMicrophoneCapture ?: throw CaptureException("This audio backend does not provide timestamps.")
                    timed.captureTimed(id, update) {
                        if (ticket == generation && state.value.phase in listOf(CapturePhase.STARTING, CapturePhase.CAPTURING)) { slotBuilder.push(it); earlyAssembler?.push(it) }
                    }
                } else capture.capture(id, update)
                throw CaptureException("Microphone capture ended. Press Start microphone to retry.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                capture.clear(); latestSlot = null; completedSlot.set(null); updates.value = null
                state.value = state.value.copy(metrics = null, slots = null, latestSlotUtcMillis = null, live = LiveReceiveState(), spectrum = SpectrumState(),
                    error = if (error is CaptureException || error is TimeException) error.message
                    else "Could not capture the microphone. Check permission and the input, then retry.")
            } finally { spectrumRender?.cancel(); spectrum?.cancel(); render.cancel(); liveRender?.cancel(); receiver?.cancel(); assembler?.clear(); earlyAssembler?.clear() }
        }
        job = next
        // Completion also runs if Stop cancels before the coroutine body ever starts.
        next.invokeOnCompletion {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                if (job === next) {
                    latestSlot = completedSlot.get()
                    if (discard) { capture.clear(); latestSlot = null }
                    state.value = state.value.copy(phase = CapturePhase.IDLE,
                        metrics = (updates.value?.metrics ?: state.value.metrics).takeUnless { discard },
                        slots = assembler?.progress().takeIf { updates.value != null }.takeUnless { discard }, latestSlotUtcMillis = latestSlot?.utcStartMillis,
                        slotSource = latestSlot?.source, slotUncertaintyMillis = latestSlot?.uncertaintyMillis)
                    job = null
                }
            }
        }
        next.start()
    }
    fun stop(reason: String = "Microphone stopped.", clear: Boolean = false) {
        recoveryRevision++
        discard = discard || clear
        val wasPending = state.value.awaitingPermission
        state.value = state.value.copy(awaitingPermission = false, live = LiveReceiveState(), spectrum = SpectrumState())
        if (job != null) {
            state.value = state.value.copy(phase = CapturePhase.STOPPING, notice = reason)
            job?.cancel()
        } else {
            if (clear) { capture.clear(); latestSlot = null }
            state.value = state.value.copy(metrics = state.value.metrics.takeUnless { clear },
                slots = state.value.slots.takeUnless { clear }, latestSlotUtcMillis = latestSlot?.utcStartMillis, notice = reason.takeIf { wasPending } ?: state.value.notice)
        }
    }
    /** Join recorder/DSP cleanup before releasing session resources or replacing a route. */
    suspend fun stopAndJoin(reason: String = "Microphone stopped.", clear: Boolean = false) {
        val previous = job
        stop(reason, clear)
        if (clear) pendingExport = null
        previous?.join()
        uiState.first { it.phase == CapturePhase.IDLE }
    }
    /** Profile changes discard transient PCM; a normal Stop retains diagnostic export data. */
    suspend fun stopForProfile() = stopAndJoin("Profile changed. Restart reception explicitly.", clear = true)
    fun configureProfile(profile: net.vash.ft8cbdroid.core.profile.OperatingProfile) {
        check(state.value.phase == CapturePhase.IDLE && !state.value.awaitingPermission)
        recoveryRevision++
        preferredKind = when (profile.audioInput) {
            net.vash.ft8cbdroid.core.profile.ProfileAudioRoute.PHONE -> AudioInputKind.PHONE
            net.vash.ft8cbdroid.core.profile.ProfileAudioRoute.USB -> AudioInputKind.USB
            net.vash.ft8cbdroid.core.profile.ProfileAudioRoute.WIRED -> AudioInputKind.WIRED
        }
        state.value = state.value.copy(selectedId = state.value.inputs.firstOrNull { it.kind == preferredKind }?.id,
            liveProfile = profile.decoder, error = null,
            notice = "${profile.audioInput.label} input preferred. Start reception explicitly.")
    }
    fun prepareExport(): Boolean {
        if (!state.value.canExport) return false
        slotExport = false
        pendingExport = CaptureWav.encode(capture.snapshot())
        return true
    }
    fun prepareSlotExport(): Boolean {
        if (!state.value.canExportSlot) return false
        val slot = latestSlot ?: return false
        pendingExport = CaptureWav.encodeSlot(slot.pcm); slotExport = true
        return true
    }
    fun export(document: String?) {
        val bytes = pendingExport ?: return
        pendingExport = null
        val isSlot = slotExport
        if (document == null) { state.value = state.value.copy(notice = "No microphone WAV saved."); return }
        state.value = state.value.copy(exporting = true)
        viewModelScope.launch {
            try { writer.write(document, bytes); state.value = state.value.copy(notice = if (isSlot) "UTC slot WAV saved (15 seconds, 12,000 Hz mono PCM16)." else "Microphone WAV saved (12,000 Hz mono PCM16).", error = null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.value = state.value.copy(error = "Could not save the microphone WAV. Select a destination and retry.") }
            finally { state.value = state.value.copy(exporting = false) }
        }
    }
    override fun onCleared() { job?.cancel(); capture.close() }
    companion object {
        fun factory(context: Context, resolver: ContentResolver, clock: ReceiveClock, startSession: (() -> Boolean)? = null) = viewModelFactory {
            initializer { CaptureViewModel(AndroidMicrophoneCapture(context), CaptureWriter { document, bytes ->
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(Uri.parse(document), "wt") ?: error("No output stream")
                    output.use { it.write(bytes) }
                }
            }, clock, NativeFt8Engine(), startSession = startSession) }
        }
    }
}
