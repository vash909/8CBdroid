package net.vash.ft8cbdroid.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.util.Locale

@Composable
fun WaveformPanel(
    state: WaveformUiState,
    hasContentChanges: Boolean,
    canEncode: Boolean,
    onFrequency: (String) -> Unit,
    onLevel: (String) -> Unit,
    onAcceptChanges: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onPreviewOutput: (Int?) -> Unit = {},
    onPreviewGain: (String) -> Unit = {},
    onPreviewRamp: (String) -> Unit = {},
    onPlayPreview: () -> Unit = {},
    onStopPreview: () -> Unit = {},
    onMeasureLatency: () -> Unit = {},
    onSlotPeriod: (net.vash.ft8cbdroid.core.audio.SlotPeriod) -> Unit = {},
    onQueueSlot: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline waveform", style = MaterialTheme.typography.titleLarge)
        Text("Generate a WAV for inspection and desktop decoding. Generating does not play audio. Use the explicit speaker preview below; live radio TX is unavailable.")
        OutlinedTextField(value = state.frequency, onValueChange = onFrequency,
            label = { Text("Base audio frequency (Hz)") }, supportingText = { Text("200–2900 Hz; first FT8 tone") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state.levelPercent, onValueChange = onLevel,
            label = { Text("Peak level (%)") }, supportingText = { Text("1–100 percent of digital full scale; not radio power") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            modifier = Modifier.fillMaxWidth())
        if (hasContentChanges) Row {
            Checkbox(checked = state.acceptContentChanges, onCheckedChange = onAcceptChanges,
                modifier = Modifier.semantics { contentDescription = "Accept encoded message changes" })
            Text("Use the encoded message shown above, including the reported content changes.")
        }
        Button(onClick = onGenerate, enabled = canEncode && !state.isGenerating &&
            (!hasContentChanges || state.acceptContentChanges)) {
            Text(if (state.isGenerating) "Generating…" else "Generate offline WAV")
        }
        if (state.isGenerating) OutlinedButton(onClick = onCancel) { Text("Cancel generation") }
        state.summary?.let {
            Text("Generated message: ${it.encodedText}")
            Text("12,000 Hz • mono PCM16 • 180,000 samples • 15 seconds")
            Text("Signal: 12.64 seconds after 0.8 seconds of silence; 20 ms ramps.")
            Text(String.format(Locale.ROOT, "Base: %.2f Hz • Level: %.1f%% • Peak: %.4f",
                it.frequencyHz, it.levelPercent, it.peak))
        }
        OutlinedButton(onClick = onSave, enabled = state.canSave) {
            Text(if (state.isSaving) "Saving WAV…" else "Save WAV")
        }
        HorizontalDivider()
        Text("Speaker preview · bench only", style = MaterialTheme.typography.titleMedium)
        Text("Plays one complete slot immediately, without UTC scheduling or PTT. Keep radios disconnected. Android media volume also affects loudness.")
        state.outputs.forEach { output ->
            FilterChip(selected = state.outputId == output.id, onClick = { onPreviewOutput(output.id) }, label = { Text(output.label) })
        }
        if (state.outputs.isEmpty()) Text("No phone speaker is available.")
        OutlinedButton(onClick = { onPreviewOutput(null) }, enabled = state.outputId != null) { Text("Clear output selection") }
        OutlinedTextField(state.previewGain, onPreviewGain, label = { Text("Preview gain (%)") }, singleLine = true,
            supportingText = { Text("1–100%; multiplied by the generated peak level") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(state.previewRamp, onPreviewRamp, label = { Text("Preview edge ramp (ms)") }, singleLine = true,
            supportingText = { Text("5–100 ms; additional fades at the signal edges") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        state.previewValidation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onPlayPreview, enabled = state.canPlay) { Text("Play speaker preview") }
        OutlinedButton(onClick = onStopPreview, enabled = state.isPlaying) { Text("Stop preview") }
        if (state.isPlaying) Text(state.operation ?: "Playing preview…")
        state.playback?.let { Text("${it.output} • client ${it.clientRateHz} Hz mono • ${it.playedFrames}/${180_000} frames played") }
        HorizontalDivider()
        Text("One UTC slot · bench only", style = MaterialTheme.typography.titleMedium)
        Text("Synchronize with NTP or GPS in Time synchronization. Measure speaker latency, choose even (00/30) or odd (15/45), then queue one slot. The first tone targets slot +0.5 s; PCM starts 0.3 s before the boundary; no PTT or automatic repetition.")
        OutlinedButton(onClick = onMeasureLatency, enabled = state.outputId != null && !state.isPlaying && !state.isGenerating && !state.isSaving && !state.isChoosingDestination) { Text("Measure speaker latency") }
        state.latency?.let { Text(String.format(Locale.ROOT, "Measured output latency: %.1f ms • spread %.1f ms • valid for 5 minutes on this output", it.nanos/1e6, it.spreadNanos/1e6)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            net.vash.ft8cbdroid.core.audio.SlotPeriod.entries.forEach { period ->
                FilterChip(selected = state.period == period, onClick = { onSlotPeriod(period) }, label = { Text(if (period == net.vash.ft8cbdroid.core.audio.SlotPeriod.EVEN) "Even · 00/30" else "Odd · 15/45") })
            }
        }
        Button(onClick = onQueueSlot, enabled = state.canPlay && state.latency != null) { Text("Queue one UTC slot") }
        state.queuedUtcMillis?.let { Text("Queued slot UTC: ${java.time.Instant.ofEpochMilli(it)} • edit, Stop or leaving this page cancels it") }
        state.playback?.firstFrameErrorMillis?.let { Text(String.format(Locale.ROOT, "Measured first-frame error: %+.1f ms", it)) }
        Text("Android may resample in its output mixer. USB output, VOX and live TX are not implemented.", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
    }
}
