package net.vash.ft8cbdroid.audio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.core.audio.AudioInputKind
import net.vash.ft8cbdroid.core.message.UnpackResult

@Composable
fun PhoneAudioPanel(state: CaptureUiState, onSelect: (Int) -> Unit, onReceive: () -> Unit,
    onMonitor: () -> Unit, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Phone audio bench", style = MaterialTheme.typography.titleLarge)
        Text("Keep radios disconnected. Speaker output pauses reception. Leaving this page stops both; radio VOX is not qualified.")
        state.inputs.filter { it.kind == AudioInputKind.PHONE }.forEach { input ->
            FilterChip(selected = state.selectedId == input.id, enabled = state.canConfigure,
                onClick = { onSelect(input.id) }, label = { Text(input.label) })
        }
        val phoneSelected = state.inputs.any { it.id == state.selectedId && it.kind == AudioInputKind.PHONE }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onReceive, enabled = state.canStart && phoneSelected) { Text("Receive FT8") }
            OutlinedButton(onClick = onMonitor, enabled = state.canStart && phoneSelected) { Text("Monitor microphone") }
        }
        OutlinedButton(onClick = onStop, enabled = state.phase != CapturePhase.IDLE || state.awaitingPermission || state.outputBusy) { Text("Stop microphone") }
        Text(if (state.outputBusy) "Microphone paused for speaker output"
            else when (state.phase) {
                CapturePhase.IDLE -> if (state.awaitingPermission) "Waiting for microphone permission…" else "Microphone idle"
                CapturePhase.STARTING -> "Opening microphone…"
                CapturePhase.CAPTURING -> "Microphone active"
                CapturePhase.STOPPING -> "Releasing microphone…"
            })
        Text("FT8 requires NTP/GPS sync. Monitor needs no time correction. Recovery clears partial slots and learned calls.", style = MaterialTheme.typography.bodySmall)
        state.live.lastSlotUtcMillis?.let { Text("Received slots: ${state.live.decodedSlots} · last UTC ${java.time.Instant.ofEpochMilli(it)}") }
        state.live.rows.take(3).forEach { row ->
            val text = (row.decode.message as? UnpackResult.Decoded)?.message?.text ?: "Unsupported frame"
            Text("Local microphone · $text")
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
    }
}
