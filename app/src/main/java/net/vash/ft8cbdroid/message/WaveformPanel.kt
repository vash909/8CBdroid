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
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Offline waveform", style = MaterialTheme.typography.titleLarge)
        Text("Generate a WAV for inspection and desktop decoding. This does not play audio or transmit.")
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
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
    }
}
