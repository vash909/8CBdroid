package net.vash.ft8cbdroid.audio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.vash.ft8cbdroid.core.Ft8DecodeProfile

@Composable
fun CapturePanel(state: CaptureUiState, onSelect: (Int) -> Unit, onStart: () -> Unit, onStop: () -> Unit, onSave: () -> Unit,
    onAssembleUtc: (Boolean) -> Unit = {}, onSaveSlot: () -> Unit = {}, onDecodeLive: (Boolean) -> Unit = {},
    onLiveProfile: (Ft8DecodeProfile) -> Unit = {},
    visualization: @Composable () -> Unit = {},
    observations: @Composable () -> Unit = { CaptureObservations(state.metrics, state.slots) }) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Microphone input", style = MaterialTheme.typography.titleLarge)
        Text("Capture 12 kHz PCM, assemble UTC slots and optionally decode local FT8 after NTP/GPS synchronization. Use Message tools for the coordinated phone speaker/microphone bench.")
        if (state.inputs.isEmpty()) Text("No supported audio input is available.")
        state.inputs.forEach { input ->
            FilterChip(selected = state.selectedId == input.id, onClick = { onSelect(input.id) },
                enabled = state.canConfigure, label = { Text(input.label) })
        }
        Row {
            Checkbox(checked = state.assembleUtc, onCheckedChange = onAssembleUtc, enabled = state.canConfigure)
            Text("Assemble UTC slots (requires NTP/GPS sync in Settings → Time)")
        }
        Row {
            Checkbox(checked = state.decodeLive, onCheckedChange = onDecodeLive, enabled = state.canConfigure)
            Text("Decode live local FT8")
        }
        if (state.decodeLive) {
            Text("Uses complete microphone UTC slots. Synchronize time in Settings → Time, then press Start microphone.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Ft8DecodeProfile.entries.forEach { profile ->
                    FilterChip(selected = state.liveProfile == profile, onClick = { onLiveProfile(profile) },
                        enabled = state.canConfigure, label = { Text("Local ${profile.label}") })
                }
            }
            Text(if (state.liveProfile == Ft8DecodeProfile.FAST) "Fast uses less work and can miss weaker signals."
                else "Standard uses the default search budget.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = state.canStart) { Text("Start microphone") }
            OutlinedButton(onClick = onStop, enabled = state.phase != CapturePhase.IDLE || state.awaitingPermission || state.outputBusy) { Text("Stop microphone") }
        }
        Text(when (state.phase) {
            CapturePhase.IDLE -> if (state.awaitingPermission) "Waiting for microphone permission…" else "Microphone idle"
            CapturePhase.STARTING -> "Opening microphone…"
            CapturePhase.CAPTURING -> "Microphone active"
            CapturePhase.STOPPING -> "Releasing microphone…"
        })
        if (state.outputBusy) Text("Microphone starts are blocked while the speaker session owns phone audio. Stop microphone also cancels pending receive recovery.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
        visualization()
        observations()
        state.latestSlotUtcMillis?.let {
            Text("Latest UTC slot: ${java.time.Instant.ofEpochMilli(it)} • ${state.slotSource}")
            Text(state.slotUncertaintyMillis?.let { String.format(Locale.ROOT, "Estimated slot timing uncertainty: ±%.1f ms", it) }
                ?: "Slot timing uncertainty is unavailable: GPS receiver accuracy is not reported.")
        }
        OutlinedButton(onClick = onSaveSlot, enabled = state.canExportSlot) { Text("Save latest UTC slot WAV") }
        Text("Only complete 15-second UTC windows are retained. Stop before saving; initial partials and interrupted windows are discarded. Only the latest complete slot is retained in memory.")
        OutlinedButton(onClick = onSave, enabled = state.canExport) { Text("Save recent microphone WAV") }
        Text("The most recent five seconds stay in memory until replaced or cleared. Stop before saving. Reception continues across screens, rotation, background and screen-off until you press Stop. Message tools can resume an already active microphone after successful output only with your explicit recovery choice.")
    }
}
