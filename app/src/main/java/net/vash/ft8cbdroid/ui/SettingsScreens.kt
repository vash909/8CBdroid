package net.vash.ft8cbdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.audio.CaptureUiState
import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.MaidenheadLocator
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.settings.*

@Composable
fun SettingsMenu(onOpen: (AppDestination) -> Unit) {
    Text("YOUR STATION", style = MaterialTheme.typography.labelSmall, color = RadioColors.muted)
    SettingsLink("Station", "Callsign and Maidenhead locator", { onOpen(AppDestination.STATION) })
    SettingsLink("Audio & source", "Phone microphone, USB input or WSJT-CB LAN", { onOpen(AppDestination.AUDIO) })
    SettingsLink("Time synchronization", "Regional / custom NTP servers and GPS", { onOpen(AppDestination.TIME) })
    SettingsLink("WSJT-CB LAN", "Desktop address, UDP port and connection", { onOpen(AppDestination.LAN) })
    SettingsLink("USB / CAT", "Discover adapters and manage permissions", { onOpen(AppDestination.USB) })
    Spacer(Modifier.height(8.dp))
    Text("TOOLS & DIAGNOSTICS", style = MaterialTheme.typography.labelSmall, color = RadioColors.muted)
    SettingsLink("Microphone tools", "Detailed spectrum, capture and WAV export", { onOpen(AppDestination.CAPTURE) })
    SettingsLink("Message tools", "CB codec, hash sessions and offline WAV generation", { onOpen(AppDestination.MESSAGES) })
    SettingsLink("Recorded audio", "Import and decode a 15-second FT8 WAV", { onOpen(AppDestination.RECORDINGS) })
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.semantics { contentDescription = title },
        shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, RadioColors.line)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = RadioColors.muted)
            }
            Text("↗", Modifier.padding(start = 12.dp), color = RadioColors.gold)
        }
    }
}

@Composable
fun StationSettingsPanel(state: StationSettingsUiState, onCall: (String) -> Unit, onGrid: (String) -> Unit,
    onSave: () -> Unit, onRetry: () -> Unit) {
    Text("Your identity on 11 metres", style = MaterialTheme.typography.bodyMedium, color = RadioColors.muted)
    OutlinedTextField(value = state.draft.callsign, onValueChange = onCall, label = { Text("CB callsign") },
        placeholder = { Text("1AT106") }, singleLine = true, enabled = state.canEdit,
        isError = state.draft.callsign.isNotEmpty() && !CbCallsign.isValid(state.draft.callsign),
        supportingText = { Text("Complete CB callsign, for example 1AT106") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = state.draft.grid, onValueChange = onGrid, label = { Text("Maidenhead locator") },
        placeholder = { Text("JN45") }, singleLine = true, enabled = state.canEdit,
        isError = state.draft.grid.isNotEmpty() && !MaidenheadLocator.isValid(state.draft.grid),
        supportingText = { Text("4 or 6 characters, for example JN45 or JN45AB") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.isSaving) "Saving…" else "Save settings")
    }
    when {
        state.isLoading -> Text("Loading settings…")
        state.failure == SettingsFailure.READ -> {
            Text("Could not read saved settings.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text("Retry loading") }
        }
        state.failure == SettingsFailure.SAVE -> Text("Could not save settings. Try Save settings again.", color = MaterialTheme.colorScheme.error)
        state.isSaved -> Text("Settings saved on this phone.", color = RadioColors.olive)
        state.hasChanges -> Text("Unsaved changes.", color = RadioColors.muted)
    }
}

@Composable
fun AudioSourceSettings(source: OperatingSource, capture: CaptureUiState, onSource: (OperatingSource) -> Unit,
    onInput: (Int) -> Unit, onProfile: (Ft8DecodeProfile) -> Unit, onOpen: (AppDestination) -> Unit) {
    Text("Receive source", style = MaterialTheme.typography.titleMedium)
    OperatingSource.entries.forEach { item ->
        FilterChip(selected = source == item, onClick = { onSource(item) }, label = { Text(item.label) })
    }
    if (source == OperatingSource.LOCAL) {
        Text("Audio input", style = MaterialTheme.typography.titleMedium)
        Text("Select the actual Android input. USB audio appears when a compatible device is attached.", style = MaterialTheme.typography.bodySmall, color = RadioColors.muted)
        if (capture.inputs.isEmpty()) Text("No supported audio input is available.")
        capture.inputs.forEach { input ->
            FilterChip(selected = input.id == capture.selectedId, onClick = { onInput(input.id) },
                enabled = capture.canConfigure, label = { Text(input.label) })
        }
        Text("Decode profile", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ft8DecodeProfile.entries.forEach { profile ->
                FilterChip(selected = capture.liveProfile == profile, onClick = { onProfile(profile) },
                    enabled = capture.canConfigure, label = { Text(profile.label) })
            }
        }
        Text("Start RX on Operate decodes complete UTC slots. Monitor displays audio without time synchronization.",
            style = MaterialTheme.typography.bodySmall, color = RadioColors.muted)
        OutlinedButton(onClick = { onOpen(AppDestination.CAPTURE) }) { Text("Microphone tools") }
    } else {
        Text("Receive desktop status and messages over UDP. LAN does not stream radio audio or control desktop transmission.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onOpen(AppDestination.LAN) }) { Text("Configure WSJT-CB LAN") }
    }
    HorizontalDivider()
    Text("Audio output", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = "Phone speaker / USB output", onValueChange = {}, enabled = false,
        label = { Text("Transmit audio") }, modifier = Modifier.fillMaxWidth())
    Text("Output selection and audio transmission are not available yet.", style = MaterialTheme.typography.bodySmall, color = RadioColors.muted)
}

@Composable
fun ChatPlaceholder() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, border = BorderStroke(1.dp, RadioColors.line), color = RadioColors.panel) {
                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { NavigationGlyph(AppDestination.CHAT) }
            }
            Text("A place to connect.", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Text("Chat between 8CBdroid operators is coming later.", style = MaterialTheme.typography.bodyLarge,
                color = RadioColors.muted, textAlign = TextAlign.Center)
            Text("COMING SOON", style = MaterialTheme.typography.labelSmall, color = RadioColors.gold)
        }
    }
}
