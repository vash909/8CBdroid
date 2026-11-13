package net.vash.ft8cbdroid.receive

import android.Manifest
import android.app.Application
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
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.lan.LanViewModel
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
    val lan = ViewModelProvider(this, LanViewModel.factory { gate(ReceiveSource.LAN) })[LanViewModel::class.java]
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
    private var refreshJob: Job? = null

    init {
        time.idleBoundary { capture.uiState.value.let { it.phase == CapturePhase.IDLE && !it.outputBusy } }
        scope.launch {
            combine(capture.controls, lan.uiState, session.uiState) { mic, network, owner -> Triple(mic, network, owner) }
                .collect { (mic, network, owner) ->
                    time.foreground(visible || owner.active)
                    time.operating(mic.phase != CapturePhase.IDLE || mic.outputBusy)
                    if (owner.phase == ReceivePhase.RUNNING && !serviceStarting && stopJob == null) {
                        val ended = when (owner.source) {
                            ReceiveSource.MICROPHONE -> mic.phase == CapturePhase.IDLE && !mic.outputBusy
                            ReceiveSource.LAN -> !network.active && !network.busy
                            null -> false
                        }
                        if (ended) stop(mic.error.takeIf { owner.source == ReceiveSource.MICROPHONE } ?: network.error)
                    }
                }
        }
        scope.launch {
            combine(time.uiState.map { it.refreshPending }.distinctUntilChanged(), visibility) { pending, foreground ->
                pending to foreground
            }.collect { (pending, foreground) ->
                // Background receive-only refresh uses a joined, explicit clock boundary.
                // Foreground output/AutoSeq continues to use its already qualified coordinator.
                if (pending && !foreground && session.uiState.value.source == ReceiveSource.MICROPHONE &&
                    capture.uiState.value.phase == CapturePhase.CAPTURING && !capture.uiState.value.outputBusy) {
                    refreshJob = scope.launch {
                        val before = session.uiState.value
                        serviceStarting = true
                        try {
                            capture.stopAndJoin("Applying refreshed NTP time at a receive boundary.", clear = true)
                            if (session.uiState.value == before) {
                                time.applyPendingAtBoundary()
                                check(runCatching { time.clock.snapshot() }.isSuccess) { "Time synchronization expired. Measure NTP or GPS and start RX again." }
                                capture.requestStart(hasMicrophonePermission())
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { stop(error.message ?: "Time refresh failed. Start RX explicitly.") }
                        finally { serviceStarting = false }
                    }
                    refreshJob?.join(); refreshJob = null
                }
            }
        }
    }
    fun foreground(active: Boolean) { visibility.value = active; time.foreground(active || session.uiState.value.active) }
    fun hasMicrophonePermission() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
        refreshJob?.cancel()
        capture.stop(clear = error != null)
        lan.stop()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runCatching { withTimeout(15_000) { outputCleanup(false) } }
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
