package net.vash.ft8cbdroid.recording

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.core.recording.RecordedDecodeFlag

@Composable
fun RecordedSlotPanel(
    state: RecordedSlotUiState,
    onUtc: (String) -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Decode a recorded slot", style = MaterialTheme.typography.titleLarge)
        Text("Import one 15-second mono PCM16 WAV at 12,000 Hz. This does not access the microphone or start live reception.")
        OutlinedTextField(value = state.utc, onValueChange = onUtc,
            label = { Text("Slot start (UTC)") }, placeholder = { Text("2026-11-06T12:00:00Z") },
            supportingText = { Text("Use a known 00, 15, 30 or 45 second boundary. File dates are not used.") },
            enabled = state.canImport, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = onImport, enabled = state.canImport) { Text("Open WAV and decode") }
        if (state.isChoosing) Text("Choose a WAV document…")
        if (state.isDecoding) {
            Text("Decoding recorded slot…")
            OutlinedButton(onClick = onCancel) { Text("Cancel decode") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
        state.result?.let { result ->
            Text("Recorded file • ${result.context.slotStartUtc}")
            Text("${result.decodes.size} valid channel frames • ${result.candidateCount} candidates")
            if (result.decodes.isEmpty()) Text("No valid FT8 frames found.")
            result.decodes.forEach { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (val message = row.message) {
                            is UnpackResult.Decoded -> Text(message.message.text)
                            is UnpackResult.Unsupported -> Text("Unsupported message: ${message.reason}")
                        }
                        Text(String.format(Locale.ROOT, "Offset %.2f Hz • DT %+.2f s • Sync %d",
                            row.frame.audioFrequencyHz, row.deltaTimeSeconds, row.frame.syncScore))
                        Text(row.frame.estimatedSnrDb?.let { String.format(Locale.ROOT, "Estimated SNR: %+.1f dB / 2500 Hz", it) }
                            ?: "Estimated SNR: unavailable")
                        Text("Signal start: ${row.signalStartUtc}")
                        Text("Flags: ${row.flags.joinToString { flag -> when (flag) {
                            RecordedDecodeFlag.CRC_LDPC_VALID -> "Integrity checked"
                            RecordedDecodeFlag.SNR_ESTIMATED -> "Estimated SNR"
                            RecordedDecodeFlag.SNR_UNAVAILABLE -> "SNR unavailable"
                            RecordedDecodeFlag.UNKNOWN_HASH -> "Unknown hash"
                            RecordedDecodeFlag.AMBIGUOUS_HASH -> "Ambiguous hash"
                            RecordedDecodeFlag.UNSUPPORTED_MESSAGE -> "Unsupported message"
                        } }}")
                    }
                }
            }
            Text("SNR is an experimental local estimate. DT is onset minus 0.5 s, with an 80 ms search grid. CRC/LDPC validity does not identify a peer or complete a QSO. Hash lookup uses only your saved station; files do not teach other sessions.")
            OutlinedButton(onClick = onClear) { Text("Clear recorded results") }
        }
    }
}
