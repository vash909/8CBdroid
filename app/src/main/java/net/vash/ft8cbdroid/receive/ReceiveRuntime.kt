package net.vash.ft8cbdroid.receive

import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.vash.ft8cbdroid.profile.*
import net.vash.ft8cbdroid.core.profile.OperatingProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import net.vash.ft8cbdroid.core.audio.PhoneAudioPhase
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.lan.LanViewModel
import net.vash.ft8cbdroid.audio.AndroidAudioPreview
import net.vash.ft8cbdroid.core.audio.AudioInputKind
import net.vash.ft8cbdroid.core.profile.ProfileAudioRoute
import net.vash.ft8cbdroid.dsp.NativeFt8Engine
import net.vash.ft8cbdroid.logbook.*
import net.vash.ft8cbdroid.operate.*
import net.vash.ft8cbdroid.settings.*
import net.vash.ft8cbdroid.ui.audioOffset
import net.vash.ft8cbdroid.time.TimeViewModel

class EightCbApplication : Application() {
    val receiver by lazy { ReceiveRuntime(this) }
}

/** One process-local graph, independent of Activity stores. Only an explicit foreground service command starts RX. */
class ReceiveRuntime(private val app: Application) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val session = ReceiveSession()
    val time = ViewModelProvider(this, TimeViewModel.factory(app))[TimeViewModel::class.java]
    val capture = ViewModelProvider(this, CaptureViewModel.factory(app, app.contentResolver, time.clock) {
        gate(ReceiveSource.MICROPHONE)
    })[CaptureViewModel::class.java]
    val audioCoordinator = capture.audioCoordinator(::hasMicrophonePermission)
    val lan = ViewModelProvider(this, LanViewModel.factory { gate(ReceiveSource.LAN) })[LanViewModel::class.java]
    val station by lazy { ViewModelProvider(this, StationSettingsViewModel.factory(
        DataStoreStationPreferencesRepository(app.stationPreferences)))[StationSettingsViewModel::class.java] }
    val logbook by lazy { ViewModelProvider(this, LogbookViewModel.factory(SqliteQsoLogRepository.get(app)))[LogbookViewModel::class.java] }
    val manual: ManualOperatingViewModel by lazy { ViewModelProvider(this, viewModelFactory { initializer {
        ManualOperatingViewModel(NativeFt8Engine(), AndroidAudioPreview(app), audioCoordinator, time.clock,
            receiveReady = {
                val input = capture.uiState.value
                hasMicrophonePermission() && session.uiState.value.let {
                    it.phase == ReceivePhase.RUNNING && it.source == ReceiveSource.MICROPHONE
                } && input.phase == CapturePhase.CAPTURING && input.decodeLive &&
                    input.inputs.any { it.id == input.selectedId && it.kind == AudioInputKind.PHONE }
            }, prepareOutput = { outputCleanup(true); lan.stopForProfile() }, reception = capture.controls,
            prepareAutomaticReceive = { prepareAutomaticReceive() },
            awaitAutomaticRecovery = { capture.awaitAutomaticRecovery() }, completedQso = logbook::completed,
            applyPendingTime = {
                check(capture.uiState.value.phase == CapturePhase.IDLE && audioCoordinator.phase.value == PhoneAudioPhase.OUTPUT)
                check(manual.uiState.value.queuedUtcMillis == null)
                time.applyPendingAtBoundary(audioPaused = true)
            }, authorizeOutput = ::authorizeOperation)
    } })[ManualOperatingViewModel::class.java] }
    private val operating = OperatingSession()
    private var serviceOwner: Any? = null
    private var promoteOutput: (() -> Unit)? = null
    fun attachService(owner: Any, generation: Long, promote: () -> Unit) {
        check(session.uiState.value.generation == generation)
        serviceOwner = owner; promoteOutput = promote
    }
    fun detachService(owner: Any) {
        if (serviceOwner === owner) { serviceOwner = null; promoteOutput = null; operating.clear() }
    }
    private fun authorizeOperation(phase: ManualPhase) {
        val owner = session.uiState.value
        if (!owner.active) {
            check(visible && phase in setOf(ManualPhase.CALIBRATING, ManualPhase.TONE)) {
                "Start microphone RX and enable TX explicitly."
            }
            return // Standalone calibration/tone stays foreground-only, as do bench previews.
        }
        check(hasMicrophonePermission() && serviceOwner != null && stopJob == null) {
            "The microphone service is unavailable. Start RX and retry explicitly."
        }
        if (phase == ManualPhase.TIME_SYNC) {
            check(owner.source == ReceiveSource.MICROPHONE && owner.phase == ReceivePhase.RUNNING)
        } else operating.authorize(owner, visible) { requireNotNull(promoteOutput).invoke() }
    }
    private var outputOwner: Any? = null
    private var outputCleanup: suspend (Boolean) -> Unit = {}
    fun attachOutput(owner: Any, cleanup: suspend (Boolean) -> Unit) { outputOwner = owner; outputCleanup = cleanup }
    fun detachOutput(owner: Any) { if (outputOwner === owner) { outputOwner = null; outputCleanup = {} } }
    val profiles by lazy {
        ViewModelProvider(this, viewModelFactory { initializer {
            ProfilesViewModel(DataStoreProfileRepository(app.operatingProfiles), object : ProfileRuntime {
                override suspend fun stop() {
                    withTimeout(15_000) {
                        this@ReceiveRuntime.stop()
                        session.uiState.first { !it.active }
                        outputCleanup(true)
                        manual.stopForProfile()
                        capture.stopForProfile(); lan.stopForProfile()
                        time.resetForProfile()
                    }
                }
                override suspend fun configure(profile: OperatingProfile) {
                    capture.configureProfile(profile); lan.configureProfile(profile.lan)
                }
            }, net.vash.ft8cbdroid.hamlib.NativeHamlibCatalog())
        } })[ProfilesViewModel::class.java]
    }
    private var serviceStarting = false
    private val visibility = MutableStateFlow(false)
    private val visible get() = visibility.value
    private var stopJob: Job? = null
    private val stopped = MutableStateFlow(0L)
    val stopRequests = stopped.asStateFlow()

    init {
        time.idleBoundary {
            val input = capture.uiState.value; val tx = manual.uiState.value
            input.phase == CapturePhase.IDLE && !input.outputBusy && !serviceStarting &&
                audioCoordinator.phase.value == PhoneAudioPhase.IDLE && !tx.busy && !tx.armed && !tx.autoSequence.active
        }
        scope.launch {
            combine(station.uiState, profiles.uiState, capture.controls) { saved, profile, input -> Triple(saved, profile, input) }
                .collect { (saved, profile, input) ->
                    if (!saved.isLoading && saved.saved != null) {
                        capture.changeStation(saved.saved.callsign.takeIf { it.isNotBlank() })
                        val selected = input.inputs.firstOrNull { it.id == input.selectedId }
                        // Catalog/draft work does not replace the applied setup. Saved station changes still invalidate TX immediately.
                        manual.context(saved.saved, profile.applied != null && !profile.receiveLan &&
                            selected?.kind == AudioInputKind.PHONE && profile.applied?.audioInput == ProfileAudioRoute.PHONE &&
                            profile.applied.audioOutput == ProfileAudioRoute.PHONE,
                            profile.applied?.referenceFrequencyHz ?: 27_265_000L, profile.revision to input.selectedId)
                    }
                }
        }
        scope.launch {
            manual.uiState.map { it.frequency }.distinctUntilChanged().collect { value ->
                audioOffset(value)?.let { hz ->
                    val view = capture.uiState.value.spectrumView
                    if (view.txHz != hz) capture.spectrumView(view.copy(txHz = hz))
                }
            }
        }
        scope.launch {
            receiveObservations(capture.uiState, session.uiState, manual.uiState, audioCoordinator.phase,
                capture.controls).collect { (mic, owner, tx, audio) ->
                    time.foreground(visible || owner.active)
                    time.operating(mic.phase != CapturePhase.IDLE || mic.outputBusy || serviceStarting ||
                        tx.busy || tx.armed || tx.autoSequence.active || audio != PhoneAudioPhase.IDLE)
                    if (!tx.busy && !tx.armed && !tx.autoSequence.active) operating.clear()
                    if (owner.phase == ReceivePhase.RUNNING && !serviceStarting && stopJob == null) {
                        val ended = when (owner.source) {
                            ReceiveSource.MICROPHONE -> microphoneSessionEnded(mic.phase == CapturePhase.IDLE, mic.outputBusy, audio)
                            ReceiveSource.LAN -> !lan.receivingOrStarting
                            null -> false
                        }
                        if (ended) stop(if (owner.source == ReceiveSource.MICROPHONE) mic.error else lan.failureReason)
                    }
                }
        }
        scope.launch {
            combine(time.uiState.map { it.refreshPending }.distinctUntilChanged(),
                manual.uiState.map { Triple(it.busy, it.armed, it.autoSequence.active) }.distinctUntilChanged(),
                capture.controls, session.uiState) { pending, tx, input, owner ->
                pending && !tx.first && !tx.second && !tx.third && owner.phase == ReceivePhase.RUNNING &&
                    owner.source == ReceiveSource.MICROPHONE && input.phase == CapturePhase.CAPTURING && !input.outputBusy
            }.distinctUntilChanged().collect { ready ->
                // The same coordinator applies refreshed time at a joined RX boundary with or without a screen.
                // Active AutoSeq applies pending time inside its next output instead.
                if (ready && audioCoordinator.phase.value == PhoneAudioPhase.IDLE) manual.refreshTime()
            }
        }
    }
    /** AutoSeq replaces its normal RX run with early windows without releasing the service lease. */
    suspend fun prepareAutomaticReceive() {
        val owner = session.uiState.value
        check(owner.source == ReceiveSource.MICROPHONE && owner.phase == ReceivePhase.RUNNING) {
            "Start microphone RX before AutoSeq."
        }
        serviceStarting = true
        try { capture.prepareAutomaticReceive(hasMicrophonePermission()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (session.uiState.value.generation == owner.generation) stop(error.message ?: "Could not prepare AutoSeq reception.")
            throw error
        }
        finally {
            serviceStarting = false
            if (session.uiState.value.generation == owner.generation && session.uiState.value.phase == ReceivePhase.RUNNING &&
                microphoneSessionEnded(capture.uiState.value.phase == CapturePhase.IDLE,
                    capture.uiState.value.outputBusy, audioCoordinator.phase.value)) stop()
        }
    }
    fun foreground(active: Boolean) {
        visibility.value = active
        time.foreground(active || session.uiState.value.active)
        if (!active && !operating.authorized(session.uiState.value) && manual.uiState.value.phase != ManualPhase.TIME_SYNC)
            manual.stop("Foreground-only output stopped. Start RX and enable TX explicitly to continue across screens.")
    }
    @Suppress("DEPRECATION") // Check our own app-op on every supported API, including pre-29 phones.
    fun hasMicrophonePermission(): Boolean {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val mode = app.getSystemService(AppOpsManager::class.java).checkOpNoThrow(
            AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), app.packageName)
        return mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
    }
    private fun gate(source: ReceiveSource): Boolean {
        val current = session.uiState.value
        if (current.source == source && (serviceStarting || current.phase == ReceivePhase.RUNNING)) return true
        if (!visible) { session.error("Open the app and start reception explicitly."); return false }
        val generation = session.request(source) ?: return false
        try {
            app.startForegroundService(Intent(app, ReceiveService::class.java)
                .setAction(ReceiveService.START).putExtra("source", source.name).putExtra("generation", generation))
        } catch (_: RuntimeException) {
            session.stopped(generation, "Android could not start the receive service. Open the app, check permissions and retry.")
        }
        return false
    }
    /** Called only after Android has promoted the matching service into the foreground. */
    fun start(generation: Long, source: ReceiveSource) {
        if (!session.accepts(generation, source)) return
        serviceStarting = true
        try {
            if (source == ReceiveSource.MICROPHONE) {
                check(hasMicrophonePermission()) { "Microphone permission is required. Allow access and start RX again." }
                capture.requestStart(true)
                check(capture.uiState.value.phase != CapturePhase.IDLE) { "The selected input is unavailable. Select it and retry." }
            } else {
                check(lan.uiState.value.canStart) { "Check the LAN address and port, then retry." }
                lan.start()
            }
            session.running(generation)
        } catch (error: Exception) { stop(error.message ?: "Could not start reception.") }
        finally { serviceStarting = false }
    }
    /** Stop invalidates pending starts/recovery before waiting for actual recorder/socket cleanup. */
    fun stop(error: String? = null) {
        if (stopJob != null) return
        val generation = session.stopping()
        stopped.value++
        operating.clear()
        manual.stop(error ?: "Session stopped. TX disabled.")
        capture.stop(clear = error != null)
        lan.stop()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runCatching { withTimeout(15_000) { outputCleanup(false) } }
                manual.stopAndJoin()
                capture.stopAndJoin(clear = error != null)
                lan.stopForProfile()
            } finally {
                session.stopped(generation, error)
                app.stopService(Intent(app, ReceiveService::class.java))
                stopJob = null
            }
        }
        stopJob = next
        next.start()
    }
}
