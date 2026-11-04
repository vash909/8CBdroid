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
import net.vash.ft8cbdroid.protocol.WsjtCbProtocol
import net.vash.ft8cbdroid.settings.DataStoreStationPreferencesRepository
import net.vash.ft8cbdroid.settings.SettingsFailure
import net.vash.ft8cbdroid.settings.StationSettingsUiState
import net.vash.ft8cbdroid.settings.StationSettingsViewModel
import net.vash.ft8cbdroid.settings.stationPreferences

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val stationViewModel = ViewModelProvider(
            this,
            StationSettingsViewModel.factory(
                DataStoreStationPreferencesRepository(applicationContext.stationPreferences),
            ),
        )[StationSettingsViewModel::class.java]
        setContent {
            val station by stationViewModel.uiState.collectAsStateWithLifecycle()
            EightCbApp(
                station = station,
                onCallsignChange = stationViewModel::changeCallsign,
                onGridChange = stationViewModel::changeGrid,
                onSaveStation = stationViewModel::save,
                onRetryLoad = stationViewModel::retryLoad,
            )
        }
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
    onCallsignChange: (String) -> Unit = {},
    onGridChange: (String) -> Unit = {},
    onSaveStation: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var desktop by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(WsjtCbProtocol.DEFAULT_LISTEN_PORT.toString()) }
    var connection by rememberSaveable { mutableStateOf("VOX telefono") }
    val sections = listOf("Stazione", "Radio", "Audio", "LAN")

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
                Text("FT8 • 11 metri • Android", color = MaterialTheme.colorScheme.primary)
                Panel("Anteprima di sviluppo") {
                    Text("Nominativo e locator si possono salvare sul telefono. Gli altri controlli sono dimostrativi; RX, TX e connessioni saranno implementati nelle prossime fasi.")
                }
                when (section) {
                    0 -> {
                        Text("La tua stazione", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = station.draft.callsign,
                            onValueChange = onCallsignChange,
                            label = { Text("Nominativo CB") },
                            placeholder = { Text("1AT106") },
                            singleLine = true,
                            enabled = station.canEdit,
                            isError = station.draft.callsign.isNotEmpty() && !CbCallsign.isValid(station.draft.callsign),
                            supportingText = { Text("Nominativo CB completo, per esempio 1AT106") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = station.draft.grid, onValueChange = onGridChange,
                            label = { Text("Locator Maidenhead") }, placeholder = { Text("JN45") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            enabled = station.canEdit,
                            isError = station.draft.grid.isNotEmpty() && !MaidenheadLocator.isValid(station.draft.grid),
                            supportingText = { Text("4 o 6 caratteri, per esempio JN45 o JN45AB") },
                        )
                        Button(
                            onClick = onSaveStation,
                            enabled = station.canSave,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (station.isSaving) "Salvataggio…" else "Salva impostazioni")
                        }
                        when {
                            station.isLoading -> Text("Caricamento impostazioni…")
                            station.failure == SettingsFailure.READ -> {
                                Text("Impossibile leggere le impostazioni salvate.", color = MaterialTheme.colorScheme.error)
                                OutlinedButton(onClick = onRetryLoad) { Text("Riprova caricamento") }
                            }
                            station.failure == SettingsFailure.SAVE ->
                                Text("Salvataggio non riuscito. Riprova con Salva impostazioni.", color = MaterialTheme.colorScheme.error)
                            station.isSaved -> Text("Impostazioni salvate sul telefono.")
                            station.hasChanges -> Text("Modifiche non salvate.")
                        }
                        Panel("Profilo FT8 / WSJT-CB") {
                            Text("27.265 MHz • alternativa 27.045 MHz")
                            Text("Slot 15 s • DSP 12 kHz • 79 simboli")
                            Text("Collegamento selezionato: $connection")
                        }
                        Panel("Attività di banda") {
                            Text("Nessuna decodifica. Il motore FT8 non è ancora integrato.")
                        }
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            Text("Avvia RX — da implementare")
                        }
                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                            Text("Abilita TX — da implementare")
                        }
                    }
                    1 -> {
                        Text("Collegamento radio", style = MaterialTheme.typography.titleLarge)
                        listOf("USB OTG / CAT", "VOX telefono", "WSJT-CB via LAN").forEach { mode ->
                            FilterChip(selected = connection == mode, onClick = { connection = mode }, label = { Text(mode) })
                        }
                        Panel("USB OTG / CAT") {
                            Text("Interfaccia RadioController pronta per Hamlib via JNI o driver CAT Android equivalenti.")
                            Text("Da aggiungere: permesso USB, enumerazione, driver seriali, profili radio e PTT.")
                            Text("Il supporto dipenderà da modello, cavo e protocollo CAT. L’audio USB richiede un percorso separato.")
                        }
                        OutlinedButton(onClick = {}, enabled = false) { Text("Cerca dispositivi USB") }
                    }
                    2 -> {
                        Text("Audio e VOX", style = MaterialTheme.typography.titleLarge)
                        Panel("Microfono + altoparlante del telefono") {
                            Text("Percorso previsto per accoppiamento acustico. La radio dovrà avere il VOX attivo.")
                            Text("Da implementare: permesso microfono, AudioRecord/AudioTrack, livelli e risincronizzazione a 12 kHz.")
                        }
                        Panel("Audio USB") {
                            Text("Percorso previsto tramite scheda audio USB compatibile Android. CAT e audio saranno configurati separatamente.")
                        }
                        OutlinedButton(onClick = {}, enabled = false) { Text("Calibra ingresso / uscita") }
                    }
                    3 -> {
                        Text("WSJT-CB nella rete locale", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = desktop, onValueChange = { desktop = it },
                            label = { Text("IP del PC WSJT-CB (filtro previsto)") },
                            placeholder = { Text("192.168.1.10") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Porta UDP di ascolto sul telefono") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            isError = port.toIntOrNull()?.let { it !in 1..65535 } ?: true,
                        )
                        Panel("Configurazione prevista") {
                            Text("In WSJT-CB imposta il server UDP sull’IP Wi-Fi del telefono e sulla stessa porta. Abilita le richieste UDP per i futuri comandi.")
                            Text("Il telefono riceverà heartbeat, stato, decodifiche e log. Le risposte useranno la porta sorgente del PC.")
                            Text("Codec di intestazione disponibile; socket e payload completi ancora da implementare. Nessun flusso audio nelle API UDP.")
                        }
                        Button(onClick = {}, enabled = false) { Text("Avvia ascolto — da implementare") }
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
