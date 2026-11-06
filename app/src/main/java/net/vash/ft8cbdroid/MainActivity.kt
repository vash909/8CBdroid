package net.vash.ft8cbdroid

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import net.vash.ft8cbdroid.message.MessagePreviewPanel
import net.vash.ft8cbdroid.message.HashSessionViewModel
import net.vash.ft8cbdroid.message.HashSessionUiState
import net.vash.ft8cbdroid.message.WaveformViewModel
import net.vash.ft8cbdroid.message.WaveformUiState
import net.vash.ft8cbdroid.core.message.EncodingMode
import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.MaidenheadLocator
import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.usb.UsbPanel
import net.vash.ft8cbdroid.usb.UsbInventoryState
import net.vash.ft8cbdroid.usb.UsbViewModel
import net.vash.ft8cbdroid.lan.LanPanel
import net.vash.ft8cbdroid.lan.DecodesPanel
import net.vash.ft8cbdroid.lan.LanUiState
import net.vash.ft8cbdroid.lan.LanViewModel
import net.vash.ft8cbdroid.settings.DataStoreStationPreferencesRepository
import net.vash.ft8cbdroid.settings.SettingsFailure
import net.vash.ft8cbdroid.settings.StationSettingsUiState
import net.vash.ft8cbdroid.settings.StationSettingsViewModel
import net.vash.ft8cbdroid.settings.stationPreferences

class MainActivity : ComponentActivity() {
    private lateinit var captureViewModel: CaptureViewModel
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        captureViewModel.permissionResult(it)
    }
    private val microphoneDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        captureViewModel.export(it?.toString())
    }
    private lateinit var lanViewModel: LanViewModel
    private lateinit var waveformViewModel: WaveformViewModel
    private val wavDestination = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) {
        waveformViewModel.export(it?.toString())
    }
    private lateinit var recordedSlotViewModel: RecordedSlotViewModel
    private val wavSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        recordedSlotViewModel.importDocument(it?.toString())
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val stationViewModel = ViewModelProvider(
            this,
            StationSettingsViewModel.factory(
                DataStoreStationPreferencesRepository(applicationContext.stationPreferences),
            ),
        )[StationSettingsViewModel::class.java]
        lanViewModel = ViewModelProvider(this, LanViewModel.factory())[LanViewModel::class.java]
        val usbViewModel = ViewModelProvider(this, UsbViewModel.factory(applicationContext))[UsbViewModel::class.java]
        val hashViewModel = ViewModelProvider(this)[HashSessionViewModel::class.java]
        waveformViewModel = ViewModelProvider(this, WaveformViewModel.factory(contentResolver))[WaveformViewModel::class.java]
        recordedSlotViewModel = ViewModelProvider(this, RecordedSlotViewModel.factory(contentResolver))[RecordedSlotViewModel::class.java]
        captureViewModel = ViewModelProvider(this, CaptureViewModel.factory(applicationContext, contentResolver))[CaptureViewModel::class.java]
        setContent {
            val usb by usbViewModel.uiState.collectAsStateWithLifecycle()
            val station by stationViewModel.uiState.collectAsStateWithLifecycle()
            val lan by lanViewModel.uiState.collectAsStateWithLifecycle()
            val hashes by hashViewModel.uiState.collectAsStateWithLifecycle()
            val waveform by waveformViewModel.uiState.collectAsStateWithLifecycle()
            val recording by recordedSlotViewModel.uiState.collectAsStateWithLifecycle()
            val capture by captureViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(station.saved?.callsign) {
                hashViewModel.changeStation(station.saved?.callsign)
                recordedSlotViewModel.changeStation(station.saved?.callsign)
            }
            EightCbApp(
                station = station,
                usb = usb,
                onRefreshUsb = usbViewModel::refresh,
                onUsbPermission = usbViewModel::requestPermission,
                lan = lan,
                onLanPortChange = lanViewModel::changePort,
                onLanAddressChange = lanViewModel::changeAddress,
                onStartLan = lanViewModel::start,
                onStopLan = lanViewModel::stop,
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
                onSelectInput = captureViewModel::select,
                onStartCapture = {
                    if (captureViewModel.requestStart(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED))
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStopCapture = { captureViewModel.stop() },
                onLeaveAudio = { captureViewModel.stop("Microphone stopped after leaving Audio.", clear = true) },
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
                onSaveWaveform = { if (waveformViewModel.prepareExport()) wavDestination.launch("8CBdroid-FT8.wav") },
            )
        }
    }

    override fun onStop() {
        captureViewModel.stop("Microphone stopped because the app left the foreground. Press Start microphone to resume.", clear = true)
        if (!isChangingConfigurations) lanViewModel.stop()
        super.onStop()
    }
}

private val palette = darkColorScheme(
    primary = Color(0xFF67E8C4),
    secondary = Color(0xFFAAC7FF),
    background = Color(0xFF101820),
    surface = Color(0xFF17232D),
)

@Composable
fun EightCbApp(
    station: StationSettingsUiState,
    lan: LanUiState = LanUiState(),
    usb: UsbInventoryState = UsbInventoryState(),
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
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var connection by rememberSaveable { mutableStateOf("Phone VOX") }
    val sections = listOf("Station", "Radio", "Audio", "LAN", "Messages")

    MaterialTheme(colorScheme = palette) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    sections.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = section == index,
                            onClick = { if (section == 2 && index != 2) onLeaveAudio(); section = index },
                            icon = { Text(listOf("FT8", "USB", "VOX", "UDP", "TXT")[index]) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding)
                    .verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("8CBdroid", style = MaterialTheme.typography.headlineLarge)
                Text("FT8 • 11 metres • Android", color = MaterialTheme.colorScheme.primary)
                Panel("Development preview") {
                    Text("Station settings, LAN monitoring, offline FT8 tools and microphone capture are available. Live local FT8 decoding, radio control and audio output are pending.")
                }
                when (section) {
                    0 -> {
                        Text("Your station", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = station.draft.callsign,
                            onValueChange = onCallsignChange,
                            label = { Text("CB callsign") },
                            placeholder = { Text("1AT106") },
                            singleLine = true,
                            enabled = station.canEdit,
                            isError = station.draft.callsign.isNotEmpty() && !CbCallsign.isValid(station.draft.callsign),
                            supportingText = { Text("Complete CB callsign, for example 1AT106") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = station.draft.grid, onValueChange = onGridChange,
                            label = { Text("Maidenhead locator") }, placeholder = { Text("JN45") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            enabled = station.canEdit,
                            isError = station.draft.grid.isNotEmpty() && !MaidenheadLocator.isValid(station.draft.grid),
                            supportingText = { Text("4 or 6 characters, for example JN45 or JN45AB") },
                        )
                        Button(
                            onClick = onSaveStation,
                            enabled = station.canSave,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (station.isSaving) "Saving…" else "Save settings")
                        }
                        when {
                            station.isLoading -> Text("Loading settings…")
                            station.failure == SettingsFailure.READ -> {
                                Text("Could not read saved settings.", color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = onRetryLoad) { Text("Retry loading") }
                            }
                            station.failure == SettingsFailure.SAVE ->
                                Text("Could not save settings. Try Save settings again.", color = MaterialTheme.colorScheme.error)
                            station.isSaved -> Text("Settings saved on this phone.")
                            station.hasChanges -> Text("Unsaved changes.")
                        }
                        Panel("FT8 / WSJT-CB profile") {
                            Text("27.265 MHz • alternative 27.045 MHz")
                            Text("15 s slots • 12 kHz DSP • 79 symbols")
                            Text("Selected connection: $connection")
                        }
                        DecodesPanel(lan.connection.decodes, lan.canReplay, onReplayLan, onClearLan)
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            Text("Start RX — not implemented")
                        }
                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            Text("Enable TX — not implemented")
                        }
                    }
                    1 -> {
                        Text("Radio connection", style = MaterialTheme.typography.titleLarge)
                        listOf("USB OTG / CAT", "Phone VOX", "WSJT-CB over LAN").forEach { mode ->
                            FilterChip(selected = connection == mode, onClick = { connection = mode }, label = { Text(mode) })
                        }
                        UsbPanel(usb, onRefreshUsb, onUsbPermission)
                    }
                    2 -> {
                        CapturePanel(capture, onSelectInput, onStartCapture, onStopCapture, onSaveCapture)
                        HorizontalDivider()
                        RecordedSlotPanel(recording, onRecordedUtc, onRecordedProfile, onImportRecording, onCancelRecording, onClearRecording)
                    }
                    4 -> MessagePreviewPanel(hashes, onSimulateReceive, onCommitHashes, onResetHashes,
                        waveform, onWaveformInput, onWaveformFrequency, onWaveformLevel, onWaveformConsent,
                        onGenerateWaveform, onCancelWaveform, onSaveWaveform)
                    3 -> {
                        LanPanel(lan, onLanPortChange, onLanAddressChange, onStartLan, onStopLan, onReplayLan, onClearLan)
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppPreview() {
    EightCbApp(StationSettingsUiState(saved = StationIdentity(), isLoading = false))
}
