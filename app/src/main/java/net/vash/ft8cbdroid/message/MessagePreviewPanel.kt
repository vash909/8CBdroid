package net.vash.ft8cbdroid.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.core.message.CbMessageCodec
import net.vash.ft8cbdroid.core.message.EncodingMode
import net.vash.ft8cbdroid.core.message.MessageFormat
import net.vash.ft8cbdroid.core.message.MessagePart
import net.vash.ft8cbdroid.core.message.PackResult
import net.vash.ft8cbdroid.core.message.UnresolvedReason
import net.vash.ft8cbdroid.core.Ft8Payload77

@Composable
fun MessagePreviewPanel(
    session: HashSessionUiState,
    onReceive: (Ft8Payload77) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
    waveform: WaveformUiState,
    onWaveformInput: (String, EncodingMode) -> Unit,
    onFrequency: (String) -> Unit,
    onLevel: (String) -> Unit,
    onAcceptChanges: (Boolean) -> Unit,
    onGenerate: (String, EncodingMode) -> Unit,
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
    onResumeReceive: (Boolean) -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("CQ 1AT106") }
    var freeText by rememberSaveable { mutableStateOf(false) }
    val mode = if (freeText) EncodingMode.FREE_TEXT else EncodingMode.AUTOMATIC
    LaunchedEffect(text, mode) { onWaveformInput(text, mode) }
    val result = remember(text, freeText) {
        CbMessageCodec.pack(text, if (freeText) EncodingMode.FREE_TEXT else EncodingMode.AUTOMATIC)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Message preview", style = MaterialTheme.typography.titleLarge)
        Text("Check how WSJT-CB encodes your message. This preview runs offline; live transmission is pending.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !freeText, onClick = {
                freeText = false; onWaveformInput(text, EncodingMode.AUTOMATIC)
            }, label = { Text("Automatic") })
            FilterChip(selected = freeText, onClick = {
                freeText = true; onWaveformInput(text, EncodingMode.FREE_TEXT)
            }, label = { Text("Free text") })
        }
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 37) { text = it; onWaveformInput(it, mode) } },
            label = { Text("Message") },
            placeholder = { Text("<1AT106> 26AT101 R-10") },
            supportingText = { Text("${text.length}/37 characters; free text carries at most 13") },
            isError = result is PackResult.Unsupported,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        when (result) {
            is PackResult.Unsupported -> Text(result.reason, color = MaterialTheme.colorScheme.error)
            is PackResult.Encoded -> {
                Text("Encoded message", style = MaterialTheme.typography.titleMedium)
                Text(result.transmittedText)
                val format = when (result.message.format) {
                    MessageFormat.FREE_TEXT -> "Free text"
                    MessageFormat.STANDARD -> "Standard message"
                    MessageFormat.STANDARD_PORTABLE -> "Portable standard message"
                    MessageFormat.NONSTANDARD_CALL -> "Nonstandard callsign message"
                }
                Text(format)
                if (result.contentChanged) {
                    Text("The encoded text differs from your input. A report, locator or part of the text may be lost.",
                        color = MaterialTheme.colorScheme.error)
                }
                if (result.message.parts.any { it is MessagePart.UnresolvedCall }) {
                    Text("A station without the matching callsign hash would read:")
                    Text(result.message.text)
                }
            }
        }
        WaveformPanel(waveform, (result as? PackResult.Encoded)?.contentChanged == true,
            result is PackResult.Encoded, onFrequency, onLevel, onAcceptChanges,
            { onGenerate(text, mode) }, onCancel, onSave,
            onPreviewOutput, onPreviewGain, onPreviewRamp, onPlayPreview, onStopPreview, onMeasureLatency, onSlotPeriod, onQueueSlot, onResumeReceive)
        Text("Offline receive session", style = MaterialTheme.typography.titleLarge)
        Text("Simulate reception of the encoded payload. Editing or previewing a message does not teach this receiver.")
        Text("Local station: ${session.stationCall ?: "Not set"}")
        Text("Learned calls: ${session.learnedCount}/1000 • Pending: ${session.pendingCount}/200")
        Button(onClick = { (result as? PackResult.Encoded)?.let { onReceive(it.payload) } },
            enabled = result is PackResult.Encoded) { Text("Simulate receive") }
        OutlinedButton(onClick = onCommit, enabled = session.pendingCount > 0) { Text("Commit learned calls") }
        OutlinedButton(onClick = onReset) { Text("Reset learned calls") }
        Text("Commit makes pending calls available for the next receive. Reset keeps only your saved station. This session survives rotation, but is not saved after the app process closes.")
        if (session.capacityReached) Text("Call storage is full. Hash resolution is disabled until Reset learned calls.",
            color = MaterialTheme.colorScheme.error)
        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        session.message?.let { message ->
            Text("Last simulated receive", style = MaterialTheme.typography.titleMedium)
            Text(message.text)
            for (part in message.parts.filterIsInstance<MessagePart.HashedCall>()) {
                Text(when (part) {
                    is MessagePart.UnresolvedCall -> if (part.reason == UnresolvedReason.CAPACITY_REACHED)
                        "Resolution unavailable: call storage is full (${part.hash.width}-bit hash ${part.hash.value})."
                        else "Unknown identity (${part.hash.width}-bit hash ${part.hash.value})."
                    is MessagePart.ResolvedCall -> "Known hash candidate: ${part.callsign} (${part.hash.width}-bit). This is not proof of identity."
                    is MessagePart.AmbiguousCall -> "Ambiguous identity (${part.hash.width}-bit): ${part.candidates.joinToString(", ")}. No callsign selected."
                })
            }
        }
    }
}
