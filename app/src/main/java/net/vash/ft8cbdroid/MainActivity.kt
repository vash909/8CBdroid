package net.vash.ft8cbdroid

import android.os.Bundle
import androidx.activity.ComponentActivity
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
import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.MaidenheadLocator
import net.vash.ft8cbdroid.core.StationIdentity
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
    private lateinit var lanViewModel: LanViewModel
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
        setContent {
            val station by stationViewModel.uiState.collectAsStateWithLifecycle()
            val lan by lanViewModel.uiState.collectAsStateWithLifecycle()
            EightCbApp(
                station = station,
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
            )
        }
    }

    override fun onStop() {
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
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var connection by rememberSaveable { mutableStateOf("Phone VOX") }
    val sections = listOf("Station", "Radio", "Audio", "LAN")

    MaterialTheme(colorScheme = palette) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    sections.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = section == index,
                            onClick = { section = index },
                            icon = { Text(listOf("FT8", "USB", "VOX", "UDP")[index]) },
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
                    Text("Station settings can be saved and desktop status/decodes can be monitored over LAN. Local FT8, radio control and audio are not implemented yet.")
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
                        Panel("USB OTG / CAT") {
                            Text("Radio control is planned through Hamlib or equivalent Android CAT drivers.")
                            Text("USB permissions, device detection, serial drivers, radio profiles and PTT are not implemented.")
                            Text("Support will depend on the radio model, cable and CAT protocol. USB audio uses a separate path.")
                        }
                        OutlinedButton(onClick = {}, enabled = false) { Text("Find USB devices") }
                    }
                    2 -> {
                        Text("Audio and VOX", style = MaterialTheme.typography.titleLarge)
                        Panel("Phone microphone and speaker") {
                            Text("Planned for acoustic coupling to a radio with VOX enabled.")
                            Text("Microphone access, audio input/output, level calibration and resampling are not implemented.")
                        }
                        Panel("Audio USB") {
                            Text("Planned for an Android-compatible USB audio interface. CAT and audio will be configured separately.")
                        }
                        OutlinedButton(onClick = {}, enabled = false) { Text("Calibrate input / output") }
                    }
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
