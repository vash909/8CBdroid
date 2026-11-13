package net.vash.ft8cbdroid

import net.vash.ft8cbdroid.profile.*
import net.vash.ft8cbdroid.operate.*
import net.vash.ft8cbdroid.logbook.*
import net.vash.ft8cbdroid.time.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import net.vash.ft8cbdroid.radio.*
import net.vash.ft8cbdroid.receive.*
import android.app.NotificationManager
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import net.vash.ft8cbdroid.ui.*
import net.vash.ft8cbdroid.audio.SpectrumPlot
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.vash.ft8cbdroid.recording.RecordedSlotViewModel
import net.vash.ft8cbdroid.recording.RecordedSlotUiState
import net.vash.ft8cbdroid.recording.RecordedSlotPanel
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.audio.CaptureUiState
import net.vash.ft8cbdroid.audio.CapturePanel
import net.vash.ft8cbdroid.core.audio.SpectrumView
import net.vash.ft8cbdroid.core.audio.PhoneAudioCoordinator
import net.vash.ft8cbdroid.core.audio.PhoneAudioReceiver
import net.vash.ft8cbdroid.core.audio.localSpectrumMarkers
import net.vash.ft8cbdroid.audio.CaptureObservationHost
import net.vash.ft8cbdroid.audio.CaptureObservations
import net.vash.ft8cbdroid.audio.SpectrumHost
import net.vash.ft8cbdroid.audio.SpectrumPanel
import net.vash.ft8cbdroid.audio.LiveReceivePanel
import net.vash.ft8cbdroid.message.MessagePreviewPanel
import net.vash.ft8cbdroid.message.HashSessionViewModel
import net.vash.ft8cbdroid.message.HashSessionUiState
import net.vash.ft8cbdroid.message.WaveformViewModel
import net.vash.ft8cbdroid.message.WaveformUiState
import net.vash.ft8cbdroid.core.message.EncodingMode
import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.usb.UsbPanel
import net.vash.ft8cbdroid.usb.UsbInventoryState
import net.vash.ft8cbdroid.usb.UsbViewModel
import net.vash.ft8cbdroid.lan.LanPanel
import net.vash.ft8cbdroid.lan.LanUiState
import net.vash.ft8cbdroid.lan.LanViewModel
import net.vash.ft8cbdroid.settings.DataStoreStationPreferencesRepository
import net.vash.ft8cbdroid.settings.StationSettingsUiState
import net.vash.ft8cbdroid.settings.StationSettingsViewModel
import net.vash.ft8cbdroid.settings.stationPreferences

class MainActivity : ComponentActivity() {
    val receiveRuntime get() = (application as EightCbApplication).receiver
    private val outputOwner = Any()
    private var pendingReceive: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingReceive; pendingReceive = null
        if (granted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) action?.invoke()
        else receiveRuntime.session.error("Allow receive notifications, then press Start RX again.")
    }
    private fun requestReception(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingReceive = action; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            receiveRuntime.session.error("Allow receive notifications in Android settings, then start RX again.")
        } else action()
    }
    private lateinit var timeViewModel: TimeViewModel
    private val gpsPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        timeViewModel.gpsPermissionResult(it[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    private val utcSlotDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        captureViewModel.export(it?.toString())
    }
    private lateinit var captureViewModel: CaptureViewModel
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        captureViewModel.permissionResult(it)
    }
    private val microphoneDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        captureViewModel.export(it?.toString())
    }
    private lateinit var lanViewModel: LanViewModel
    private lateinit var waveformViewModel: WaveformViewModel
    private lateinit var manualViewModel: ManualOperatingViewModel
    private val wavDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        waveformViewModel.export(it?.toString())
    }
    private lateinit var recordedSlotViewModel: RecordedSlotViewModel
    private val wavSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        recordedSlotViewModel.importDocument(it?.toString())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val stationViewModel = ViewModelProvider(
            this,
            StationSettingsViewModel.factory(
                DataStoreStationPreferencesRepository(applicationContext.stationPreferences),
            ),
        )[StationSettingsViewModel::class.java]
        lanViewModel = receiveRuntime.lan
        val usbViewModel = ViewModelProvider(this, UsbViewModel.factory(applicationContext))[UsbViewModel::class.java]
        val hamlibViewModel = ViewModelProvider(this)[HamlibViewModel::class.java]
        val hashViewModel = ViewModelProvider(this)[HashSessionViewModel::class.java]
        recordedSlotViewModel = ViewModelProvider(this, RecordedSlotViewModel.factory(contentResolver))[RecordedSlotViewModel::class.java]
        timeViewModel = receiveRuntime.time
        captureViewModel = receiveRuntime.capture
        val previewCapture = captureViewModel
        val previewLan = lanViewModel
        val app = applicationContext
        val audioCoordinator = previewCapture.audioCoordinator {
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        waveformViewModel = ViewModelProvider(this, WaveformViewModel.factory(contentResolver, app, timeViewModel.clock, audioCoordinator) {
            previewLan.stopForProfile()
        })[WaveformViewModel::class.java]
        val bench = waveformViewModel
        val logbookViewModel = ViewModelProvider(this, LogbookViewModel.factory(SqliteQsoLogRepository.get(app)))[LogbookViewModel::class.java]
        manualViewModel = ViewModelProvider(this, viewModelFactory {
            initializer { ManualOperatingViewModel(net.vash.ft8cbdroid.dsp.NativeFt8Engine(),
                net.vash.ft8cbdroid.audio.AndroidAudioPreview(app), audioCoordinator, timeViewModel.clock,
                receiveReady = {
                    val input = previewCapture.uiState.value
                    app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                        input.phase == net.vash.ft8cbdroid.audio.CapturePhase.CAPTURING && input.decodeLive &&
                        input.inputs.any { it.id == input.selectedId && it.kind == net.vash.ft8cbdroid.core.audio.AudioInputKind.PHONE }
                }, prepareOutput = { bench.stopForProfile(); previewLan.stopForProfile() }, reception = previewCapture.controls,
                prepareAutomaticReceive = { previewCapture.prepareAutomaticReceive(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) },
                awaitAutomaticRecovery = { previewCapture.awaitAutomaticRecovery() }, completedQso = logbookViewModel::completed,
                applyPendingTime = {
                    check(previewCapture.uiState.value.phase == net.vash.ft8cbdroid.audio.CapturePhase.IDLE)
                    check(audioCoordinator.phase.value == net.vash.ft8cbdroid.core.audio.PhoneAudioPhase.OUTPUT)
                    check(manualViewModel.uiState.value.queuedUtcMillis == null && !bench.uiState.value.isPlaying)
                    timeViewModel.applyPendingAtBoundary(audioPaused = true)
                }) }
        })[ManualOperatingViewModel::class.java]
        lifecycleScope.launch {
            receiveRuntime.stopRequests.drop(1).collect {
                manualViewModel.stop("Receive session stopped. TX disabled.")
                waveformViewModel.stopPreview("Receive session stopped.")
            }
        }
        val outputManual = manualViewModel
        val outputBench = waveformViewModel
        receiveRuntime.attachOutput(outputOwner) { profile ->
            if (profile) { outputManual.stopForProfile(); outputBench.stopForProfile() }
            else { outputManual.stopAndJoin(); outputBench.stopAndJoin() }
        }
        val profilesViewModel = receiveRuntime.profiles
        val spectrumChange: (SpectrumView) -> Unit = { view ->
            val previous = captureViewModel.uiState.value.spectrumView
            captureViewModel.spectrumView(view)
            if (view.txHz != previous.txHz) manualViewModel.frequency(view.txHz.toString())
        }
        setContent {
            val receiveSession by receiveRuntime.session.uiState.collectAsStateWithLifecycle()
            val profiles by profilesViewModel.uiState.collectAsStateWithLifecycle()
            val usb by usbViewModel.uiState.collectAsStateWithLifecycle()
            val hamlib by hamlibViewModel.uiState.collectAsStateWithLifecycle()
            val station by stationViewModel.uiState.collectAsStateWithLifecycle()
            val lan by lanViewModel.uiState.collectAsStateWithLifecycle()
            val hashes by hashViewModel.uiState.collectAsStateWithLifecycle()
            val waveform by waveformViewModel.uiState.collectAsStateWithLifecycle()
            val manual by manualViewModel.uiState.collectAsStateWithLifecycle()
            val logbook by logbookViewModel.uiState.collectAsStateWithLifecycle()
            val recording by recordedSlotViewModel.uiState.collectAsStateWithLifecycle()
            val capture by captureViewModel.controls.collectAsStateWithLifecycle(initialValue = CaptureUiState())
            val time by timeViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(manual.frequency) {
                audioOffset(manual.frequency)?.let { hz ->
                    val view = captureViewModel.uiState.value.spectrumView
                    if (view.txHz != hz) captureViewModel.spectrumView(view.copy(txHz = hz))
                }
            }
            LaunchedEffect(capture.phase, capture.outputBusy, manual.busy, waveform.isPlaying) {
                timeViewModel.operating(capture.phase != net.vash.ft8cbdroid.audio.CapturePhase.IDLE || capture.outputBusy || manual.busy || waveform.isPlaying)
            }
            LaunchedEffect(time.refreshPending, manual.busy, manual.autoSequence.active, capture.phase) {
                if (time.refreshPending && !manual.busy && !manual.autoSequence.active && capture.phase == net.vash.ft8cbdroid.audio.CapturePhase.CAPTURING)
                    manualViewModel.refreshTime()
            }
            LaunchedEffect(station.saved?.callsign, station.isLoading) {
                val call = station.saved?.callsign?.takeIf { it.isNotBlank() }
                hashViewModel.changeStation(call)
                recordedSlotViewModel.changeStation(call)
                if (!station.isLoading && station.saved != null) captureViewModel.changeStation(call)
            }
            LaunchedEffect(station.saved, profiles.ready, profiles.revision, profiles.receiveLan, capture.selectedId) {
                val input = capture.inputs.firstOrNull { it.id == capture.selectedId }
                val profile = profiles.applied
                manualViewModel.context(station.saved, profiles.ready && !profiles.receiveLan &&
                    input?.kind == net.vash.ft8cbdroid.core.audio.AudioInputKind.PHONE &&
                    profile?.audioInput == net.vash.ft8cbdroid.core.profile.ProfileAudioRoute.PHONE &&
                    profile.audioOutput == net.vash.ft8cbdroid.core.profile.ProfileAudioRoute.PHONE,
                    profile?.referenceFrequencyHz ?: 27_265_000L, profiles.revision to capture.selectedId)
            }
            EightCbApp(
                station = station,
                receiveSession = receiveSession,
                onStopSession = { receiveRuntime.stop() },
                logbook = logbook,
                logbookContent = { LogbookPanel(logbook, logbookViewModel, station.saved, profiles.applied?.referenceFrequencyHz ?: 27_265_000L) },
                manual = manual,
                manualActions = ManualActions(manualViewModel::peer, manualViewModel::report,
                    manualViewModel::choose, manualViewModel::custom, manualViewModel::freeText,
                    manualViewModel::acceptChanges, { text -> manualViewModel.frequency(text)
                        audioOffset(text)?.let { hz -> captureViewModel.spectrumView(captureViewModel.uiState.value.spectrumView.copy(txHz = hz)) } }, manualViewModel::level,
                    manualViewModel::period, manualViewModel::output, manualViewModel::calibrate,
                    manualViewModel::testTone, manualViewModel::enableTx, manualViewModel::send, { peer ->
                        manualViewModel.selectPeer(peer)
                        captureViewModel.spectrumView(captureViewModel.uiState.value.spectrumView.copy(rxHz = peer.frequencyHz.coerceIn(200, 2950), txHz = peer.frequencyHz.coerceIn(200, 2950)))
                    }, useSuggestion = { manualViewModel.useSuggestion()
                        audioOffset(manualViewModel.uiState.value.frequency)?.let { hz ->
                            captureViewModel.spectrumView(captureViewModel.uiState.value.spectrumView.copy(txHz = hz)) }
                    }, resetQso = manualViewModel::resetQso, startAutomatic = manualViewModel::startAutomatic,
                    stopAutomatic = { manualViewModel.stop("AutoSeq stopped by operator.") }),
                onLeaveOperate = { manualViewModel.stop("Left Operate. TX disabled.") },
                profiles = profiles,
                onSourceChange = { manualViewModel.stop("Source changed. TX disabled."); profilesViewModel.temporarySource(it == OperatingSource.LAN) },
                profilesContent = { ProfilesPanel(profiles, profilesViewModel::edit, profilesViewModel::select,
                    profilesViewModel::newProfile, profilesViewModel::discard, profilesViewModel::save,
                    profilesViewModel::apply, profilesViewModel::delete, profilesViewModel::load,
                    profilesViewModel::loadModels, profilesViewModel::query, profilesViewModel::chooseModel) },
                usb = usb,
                hamlib = hamlib,
                onLoadHamlib = hamlibViewModel::load,
                onHamlibQuery = hamlibViewModel::query,
                onHamlibSelect = hamlibViewModel::select,
                onHamlibDummy = hamlibViewModel::checkDummy,
                onCancelHamlib = hamlibViewModel::cancel,
                onRefreshUsb = usbViewModel::refresh,
                onUsbPermission = usbViewModel::requestPermission,
                lan = lan,
                onLanPortChange = lanViewModel::changePort,
                onLanAddressChange = lanViewModel::changeAddress,
                onStartLan = { if (profilesViewModel.uiState.value.ready) requestReception { lanViewModel.start() } },
                onStopLan = { receiveRuntime.stop() },
                onReplayLan = lanViewModel::requestReplay,
                onClearLan = lanViewModel::clearDecodes,
                onCallsignChange = stationViewModel::changeCallsign,
                onGridChange = stationViewModel::changeGrid,
                onSaveStation = stationViewModel::save,
                onRetryLoad = stationViewModel::retryLoad,
                hashes = hashes,
                onSimulateReceive = hashViewModel::receive,
                onCommitHashes = hashViewModel::commit,
                onResetHashes = hashViewModel::reset,
                recording = recording,
                capture = capture,
                captureObservations = { CaptureObservationHost(captureViewModel.uiState) },
                spectrumContent = { SpectrumHost(captureViewModel.uiState, spectrumChange) },
                compactSpectrumContent = { SpectrumHost(captureViewModel.uiState, spectrumChange, compact = true) },
                time = time,
                timeActions = TimeActions(timeViewModel::selectPreset, timeViewModel::changeCustom,
                    save = { captureViewModel.stop("Time settings changed. Restart capture explicitly.", clear = true); timeViewModel.save() },
                    retry = timeViewModel::load, ntp = timeViewModel::measureNtp,
                    gps = { if (timeViewModel.requestGps(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED))
                        gpsPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                    apply = { captureViewModel.stop("Time correction changed. Restart capture explicitly.", clear = true); timeViewModel.applyMeasurement() },
                    cancel = timeViewModel::cancel),
                onAssembleUtc = captureViewModel::assembleUtc,
                onDecodeLive = captureViewModel::decodeLive,
                onLiveProfile = captureViewModel::liveProfile,
                onSpectrumView = spectrumChange,
                onSaveUtcSlot = { if (captureViewModel.prepareSlotExport()) {
                    val utc = captureViewModel.uiState.value.latestSlotUtcMillis
                    val stamp = utc?.let { java.time.Instant.ofEpochMilli(it).toString().replace(":", "-") } ?: "slot"
                    utcSlotDestination.launch("8CBdroid-UTC-$stamp.wav")
                } },
                onSelectInput = captureViewModel::select,
                onStartCapture = { requestReception {
                    if (profilesViewModel.uiState.value.ready && captureViewModel.requestStart(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED))
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                } },
                onStopCapture = { manualViewModel.stop(); receiveRuntime.stop() },
                onLeaveAudio = { receiveRuntime.stop() },
                onSaveCapture = {
                    if (captureViewModel.prepareExport()) microphoneDestination.launch("8CBdroid-microphone.wav")
                },
                onRecordedUtc = recordedSlotViewModel::changeUtc,
                onRecordedProfile = recordedSlotViewModel::changeProfile,
                onImportRecording = { if (recordedSlotViewModel.prepareImport()) wavSource.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave")) },
                onCancelRecording = recordedSlotViewModel::cancel,
                onClearRecording = recordedSlotViewModel::clear,
                waveform = waveform,
                onWaveformInput = waveformViewModel::changeInput,
                onWaveformFrequency = waveformViewModel::changeFrequency,
                onWaveformLevel = waveformViewModel::changeLevel,
                onWaveformConsent = waveformViewModel::acceptChanges,
                onGenerateWaveform = waveformViewModel::generate,
                onCancelWaveform = waveformViewModel::cancel,
                onPreviewOutput = waveformViewModel::selectOutput,
                onPreviewGain = waveformViewModel::previewGain,
                onPreviewRamp = waveformViewModel::previewRamp,
                onPlayPreview = waveformViewModel::playPreview,
                onStopPreview = { waveformViewModel.stopPreview() },
                onMeasureLatency = waveformViewModel::measureLatency,
                onSlotPeriod = waveformViewModel::period,
                onQueueSlot = waveformViewModel::queueSlot,
                onResumeReceive = waveformViewModel::resumeReceive,
                onSaveWaveform = { if (waveformViewModel.prepareExport()) wavDestination.launch("8CBdroid-FT8.wav") },
            )
        }
    }

    override fun onDestroy() {
        receiveRuntime.detachOutput(outputOwner)
        super.onDestroy()
    }
    override fun onStop() {
        receiveRuntime.foreground(false)
        pendingReceive = null
        manualViewModel.stop("App left the foreground. TX disabled.")
        waveformViewModel.stopPreview("Preview stopped because the app left the foreground.")
        timeViewModel.cancel()
        if (captureViewModel.uiState.value.awaitingPermission) captureViewModel.stop()
        super.onStop()
    }
    override fun onStart() { super.onStart(); receiveRuntime.foreground(true) }
}

@Composable
fun EightCbApp(
    station: StationSettingsUiState,
    receiveSession: ReceiveSessionState = ReceiveSessionState(),
    onStopSession: () -> Unit = {},
    logbook: LogbookState = LogbookState(),
    logbookContent: @Composable () -> Unit = {},
    manual: ManualOperatingState = ManualOperatingState(),
    manualActions: ManualActions = ManualActions(),
    onLeaveOperate: () -> Unit = {},
    profiles: ProfilesUiState = ProfilesUiState(),
    profilesContent: @Composable () -> Unit = {},
    onSourceChange: (OperatingSource) -> Unit = {},
    lan: LanUiState = LanUiState(),
    usb: UsbInventoryState = UsbInventoryState(),
    hamlib: HamlibUiState = HamlibUiState(),
    onLoadHamlib: () -> Unit = {},
    onHamlibQuery: (String) -> Unit = {},
    onHamlibSelect: (Int) -> Unit = {},
    onHamlibDummy: () -> Unit = {},
    onCancelHamlib: () -> Unit = {},
    onRefreshUsb: () -> Unit = {},
    onUsbPermission: (String) -> Unit = {},
    onLanPortChange: (String) -> Unit = {},
    onLanAddressChange: (String) -> Unit = {},
    onStartLan: () -> Unit = {},
    onStopLan: () -> Unit = {},
    onReplayLan: () -> Unit = {},
    onClearLan: () -> Unit = {},
    onCallsignChange: (String) -> Unit = {},
    onGridChange: (String) -> Unit = {},
    onSaveStation: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    hashes: HashSessionUiState = HashSessionUiState(),
    onSimulateReceive: (Ft8Payload77) -> Unit = {},
    onCommitHashes: () -> Unit = {},
    onResetHashes: () -> Unit = {},
    recording: RecordedSlotUiState = RecordedSlotUiState(),
    capture: CaptureUiState = CaptureUiState(),
    time: TimeUiState = TimeUiState(),
    timeActions: TimeActions = TimeActions(),
    onAssembleUtc: (Boolean) -> Unit = {},
    onDecodeLive: (Boolean) -> Unit = {},
    onLiveProfile: (Ft8DecodeProfile) -> Unit = {},
    onSpectrumView: (SpectrumView) -> Unit = {},
    captureObservations: @Composable () -> Unit = { CaptureObservations(capture.metrics, capture.slots) },
    spectrumContent: @Composable () -> Unit = { SpectrumPanel(capture.spectrum, capture.spectrumView, localSpectrumMarkers(capture.live.rows), onSpectrumView) },
    compactSpectrumContent: @Composable () -> Unit = { SpectrumPlot(capture.spectrum, capture.spectrumView, localSpectrumMarkers(capture.live.rows), onSpectrumView, 96.dp) },
    onSaveUtcSlot: () -> Unit = {},
    onSelectInput: (Int) -> Unit = {},
    onStartCapture: () -> Unit = {},
    onStopCapture: () -> Unit = {},
    onLeaveAudio: () -> Unit = {},
    onSaveCapture: () -> Unit = {},
    onRecordedUtc: (String) -> Unit = {},
    onRecordedProfile: (Ft8DecodeProfile) -> Unit = {},
    onImportRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    onClearRecording: () -> Unit = {},
    waveform: WaveformUiState = WaveformUiState(),
    onWaveformInput: (String, EncodingMode) -> Unit = { _, _ -> },
    onWaveformFrequency: (String) -> Unit = {},
    onWaveformLevel: (String) -> Unit = {},
    onWaveformConsent: (Boolean) -> Unit = {},
    onGenerateWaveform: (String, EncodingMode) -> Unit = { _, _ -> },
    onCancelWaveform: () -> Unit = {},
    onSaveWaveform: () -> Unit = {},
    onPreviewOutput: (Int?) -> Unit = {},
    onPreviewGain: (String) -> Unit = {},
    onPreviewRamp: (String) -> Unit = {},
    onPlayPreview: () -> Unit = {},
    onStopPreview: () -> Unit = {},
    onMeasureLatency: () -> Unit = {},
    onSlotPeriod: (net.vash.ft8cbdroid.core.audio.SlotPeriod) -> Unit = {},
    onQueueSlot: () -> Unit = {},
    onResumeReceive: (Boolean) -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.OPERATE) }
    val source = if (profiles.receiveLan) OperatingSource.LAN else OperatingSource.LOCAL
    val pageStates = rememberSaveableStateHolder()
    fun navigate(next: AppDestination) {
        if (destination == AppDestination.OPERATE && next != destination) onLeaveOperate()
        if (destination == AppDestination.MESSAGES && next != destination) onStopPreview()
        destination = next
    }
    BackHandler(enabled = destination != AppDestination.OPERATE) {
        navigate(if (destination.tab == AppDestination.SETTINGS && destination != AppDestination.SETTINGS)
            AppDestination.SETTINGS else AppDestination.OPERATE)
    }
    RadioTheme {
        Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
                if (receiveSession.active || receiveSession.error != null) Surface {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(receiveSession.error ?: "${receiveSession.source?.label ?: "RX"} · ${receiveSession.phase.name.lowercase()}",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (receiveSession.active) TextButton(onClick = onStopSession) { Text("Stop RX") }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = RadioColors.background, tonalElevation = 0.dp) {
                    listOf(AppDestination.OPERATE, AppDestination.SETTINGS, AppDestination.CHAT).forEach { tab ->
                        NavigationBarItem(selected = destination.tab == tab, onClick = { navigate(tab) },
                            icon = { NavigationGlyph(tab) }, label = { Text(tab.title) })
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                pageStates.SaveableStateProvider(destination.name) {
                when (destination) {
                    AppDestination.OPERATE -> OperatePanel(station, source, capture, lan, time, ::navigate,
                        onReceive = {
                            if (source == OperatingSource.LAN) onStartLan() else {
                                onDecodeLive(true); onAssembleUtc(true); onStartCapture()
                            }
                        }, onMonitor = { onDecodeLive(false); onAssembleUtc(false); onStartCapture() },
                        onStop = { if (source == OperatingSource.LAN) onStopLan() else onStopCapture() },
                        onView = onSpectrumView, spectrum = compactSpectrumContent,
                        profileName = profiles.applied?.name, referenceFrequencyHz = profiles.applied?.referenceFrequencyHz ?: 27_265_000L,
                        profileReady = profiles.ready, manual = manual, manualActions = manualActions,
                        workedCalls = logbook.snapshot.workedCalls(station.saved?.callsign.orEmpty()),
                        workedAvailable = logbook.workedAvailable, pendingQsos = logbook.snapshot.pending.size + logbook.unsavedCompletions)
                    AppDestination.CHAT -> ChatPlaceholder()
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (destination != AppDestination.SETTINGS) TextButton(onClick = { navigate(AppDestination.SETTINGS) },
                            contentPadding = PaddingValues(0.dp)) { Text("← All settings") }
                        Text(destination.title, style = MaterialTheme.typography.headlineLarge)
                        when (destination) {
                            AppDestination.SETTINGS -> SettingsMenu(::navigate)
                            AppDestination.PROFILES -> profilesContent()
                            AppDestination.LOGBOOK -> logbookContent()
                            AppDestination.STATION -> StationSettingsPanel(station, onCallsignChange, onGridChange, onSaveStation, onRetryLoad)
                            AppDestination.TIME -> TimePanel(time, timeActions)
                            AppDestination.AUDIO -> AudioSourceSettings(source, capture,
                                onSource = { next ->
                                    if (next != source) { onLeaveAudio(); onStopLan(); onSourceChange(next) }
                                }, onSelectInput, onLiveProfile, ::navigate)
                            AppDestination.LAN -> LanPanel(lan.copy(busy = lan.busy || !profiles.ready), onLanPortChange, onLanAddressChange,
                                onStartLan, onStopLan, onReplayLan, onClearLan)
                            AppDestination.USB -> {
                                HamlibPanel(hamlib, onLoadHamlib, onHamlibQuery, onHamlibSelect, onHamlibDummy, onCancelHamlib)
                                UsbPanel(usb, onRefreshUsb, onUsbPermission)
                            }
                            AppDestination.CAPTURE -> {
                                CapturePanel(if (profiles.ready) capture else capture.copy(selectedId = null), onSelectInput, onStartCapture, onStopCapture, onSaveCapture,
                                    onAssembleUtc, onSaveUtcSlot, onDecodeLive, onLiveProfile,
                                    visualization = spectrumContent, observations = captureObservations)
                                Text("UTC time: ${time.sourceDescription}")
                                if (capture.decodeLive) LiveReceivePanel(capture.live, capture.liveProfile, capture.phase)
                            }
                            AppDestination.MESSAGES -> {
                                net.vash.ft8cbdroid.audio.PhoneAudioPanel(if (profiles.ready) capture else capture.copy(selectedId = null), onSelectInput,
                                    onReceive = { onDecodeLive(true); onAssembleUtc(true); onStartCapture() },
                                    onMonitor = { onDecodeLive(false); onAssembleUtc(false); onStartCapture() }, onStop = onStopCapture)
                                MessagePreviewPanel(hashes, onSimulateReceive, onCommitHashes, onResetHashes,
                                waveform, onWaveformInput, onWaveformFrequency, onWaveformLevel, onWaveformConsent,
                                onGenerateWaveform, onCancelWaveform, onSaveWaveform,
                                onPreviewOutput, onPreviewGain, onPreviewRamp, onPlayPreview, onStopPreview, onMeasureLatency, onSlotPeriod, onQueueSlot, onResumeReceive)
                            }
                            AppDestination.RECORDINGS -> RecordedSlotPanel(recording, onRecordedUtc, onRecordedProfile,
                                onImportRecording, onCancelRecording, onClearRecording)
                            else -> Unit
                        }
                    }
                }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppPreview() {
    EightCbApp(StationSettingsUiState(saved = StationIdentity(), isLoading = false))
}
