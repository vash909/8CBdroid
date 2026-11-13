package net.vash.ft8cbdroid.audio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.util.Locale
import net.vash.ft8cbdroid.core.audio.LiveSlotReceiver
import net.vash.ft8cbdroid.core.audio.LiveReceiveState
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.core.recording.RecordedDecodeFlag

@Composable
fun LiveReceivePanel(state: LiveReceiveState, profile: Ft8DecodeProfile, phase: CapturePhase) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Live local decodes", style = MaterialTheme.typography.titleLarge)
        Text("Source: local microphone • ${profile.label} • latest ${LiveSlotReceiver.MAX_ROWS} frames")
        Text("${state.decodedSlots} slots decoded • ${state.skippedSlots} pending slots replaced • ${state.failedSlots} failed")
        state.decodingUtcMillis?.let { Text("Decoding local slot: ${Instant.ofEpochMilli(it)}") }
        state.lastSlotUtcMillis?.let { Text("Last local slot: ${Instant.ofEpochMilli(it)}" + (state.decodeMillis?.let { ms -> " • $ms ms" } ?: "")) }
        if (state.rows.isEmpty()) Text(if (phase == CapturePhase.IDLE) "Start the microphone to receive local FT8."
            else "Waiting for complete UTC slots and valid FT8 frames…")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("Learned local calls: ${state.learnedCalls}")
        if (state.hashCapacityReached) Text("Local callsign cache is full. Hashes remain unresolved until reception is restarted.")
        state.rows.forEach { row ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Local microphone • ${row.decode.slotStartUtc}")
                    when (val message = row.decode.message) {
                        is UnpackResult.Decoded -> Text(message.message.text)
                        is UnpackResult.Unsupported -> Text("Unsupported message: ${message.reason}")
                    }
                    Text(String.format(Locale.ROOT, "Offset %.2f Hz • DT %+.2f s • Sync %d",
                        row.decode.frame.audioFrequencyHz, row.decode.deltaTimeSeconds, row.decode.frame.syncScore))
                    Text(row.decode.frame.estimatedSnrDb?.let { String.format(Locale.ROOT, "Estimated SNR: %+.1f dB / 2500 Hz", it) }
                        ?: "Estimated SNR: unavailable")
                    Text("Time source: ${row.timeSource}" + (row.uncertaintyMillis?.let { String.format(Locale.ROOT, " • estimated ±%.1f ms", it) }
                        ?: " • timing uncertainty unavailable"))
                    if (RecordedDecodeFlag.UNKNOWN_HASH in row.decode.flags) Text("Unknown callsign hash")
                    if (RecordedDecodeFlag.AMBIGUOUS_HASH in row.decode.flags) Text("Ambiguous callsign hash")
                }
            }
        }
        Text("Local audio and LAN results use separate sessions. Calls learned in a complete decode batch become available to later slots. Navigation, rotation, background and screen-off preserve active RX. Stop and input/station changes clear local results and learned calls. Process death never restarts reception.")
    }
}
