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
    onLiveProfile: (Ft8DecodeProfile) -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Microphone input", style = MaterialTheme.typography.titleLarge)
        Text("Capture 12 kHz PCM, assemble UTC slots and optionally decode local FT8 after NTP/GPS synchronization. Speaker output is pending.")
        if (state.inputs.isEmpty()) Text("No supported audio input is available.")
        state.inputs.forEach { input ->
            FilterChip(selected = state.selectedId == input.id, onClick = { onSelect(input.id) },
                enabled = state.canConfigure, label = { Text(input.label) })
        }
        Row {
            Checkbox(checked = state.assembleUtc, onCheckedChange = onAssembleUtc, enabled = state.canConfigure)
            Text("Assemble UTC slots (requires NTP/GPS sync in Station)")
        }
        Row {
            Checkbox(checked = state.decodeLive, onCheckedChange = onDecodeLive, enabled = state.canConfigure)
            Text("Decode live local FT8")
        }
        if (state.decodeLive) {
            Text("Uses complete microphone UTC slots. Synchronize time in Station, then press Start microphone.")
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
            OutlinedButton(onClick = onStop, enabled = state.phase != CapturePhase.IDLE || state.awaitingPermission) { Text("Stop microphone") }
        }
        Text(when (state.phase) {
            CapturePhase.IDLE -> if (state.awaitingPermission) "Waiting for microphone permission…" else "Microphone idle"
            CapturePhase.STARTING -> "Opening microphone…"
            CapturePhase.CAPTURING -> "Microphone active"
            CapturePhase.STOPPING -> "Releasing microphone…"
        })
        state.metrics?.let { metrics ->
            Text("Actual input: ${metrics.route.input.label}")
            Text("Capture: ${metrics.route.clientRate} Hz mono PCM16 → 12,000 Hz DSP PCM")
            Text("Device format: ${metrics.route.deviceFormat}")
            Text("Source: ${metrics.route.source}")
            Text(metrics.route.processing)
            Text(String.format(Locale.ROOT, "Input peak: %.1f dBFS • RMS: %.1f dBFS", metrics.peakDbfs, metrics.rmsDbfs))
            LinearProgressIndicator(progress = { ((metrics.peakDbfs + 60) / 60).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("Clipped input samples: ${metrics.clippedSamples}")
            Text("Samples: ${metrics.inputSamples} input / ${metrics.outputSamples} at 12 kHz")
            Text("Recent buffer: ${metrics.retainedSamples} / 60,000 samples • recorder buffer: ${metrics.route.bufferFrames} frames")
        }
        state.slots?.let { slots ->
            Text("UTC slots: ${slots.completed} complete • ${slots.collected}/180,000 samples • ${slots.discarded} incomplete windows discarded")
            slots.driftPpm?.let { Text(String.format(Locale.ROOT, "Measured audio clock drift: %+.1f ppm", it)) }
        }
        state.latestSlotUtcMillis?.let {
            Text("Latest UTC slot: ${java.time.Instant.ofEpochMilli(it)} • ${state.slotSource}")
            Text(state.slotUncertaintyMillis?.let { String.format(Locale.ROOT, "Estimated slot timing uncertainty: ±%.1f ms", it) }
                ?: "Slot timing uncertainty is unavailable: GPS receiver accuracy is not reported.")
        }
        OutlinedButton(onClick = onSaveSlot, enabled = state.canExportSlot) { Text("Save latest UTC slot WAV") }
        Text("Only complete 15-second UTC windows are retained. Stop before saving; initial partials and interrupted windows are discarded. Only the latest complete slot is retained in memory.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
        OutlinedButton(onClick = onSave, enabled = state.canExport) { Text("Save recent microphone WAV") }
        Text("The most recent five seconds stay in memory until replaced or cleared. Stop before saving. Leaving Audio, rotating or backgrounding stops capture and clears its buffer. Restart is always explicit.")
    }
}
