package net.vash.ft8cbdroid.operate

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.core.audio.SlotPeriod
import net.vash.ft8cbdroid.core.message.*

data class ManualActions(
    val peer: (String) -> Unit = {}, val report: (String) -> Unit = {},
    val choose: (ManualMessage) -> Unit = {}, val custom: (String) -> Unit = {},
    val freeText: (Boolean) -> Unit = {}, val accept: (Boolean) -> Unit = {},
    val frequency: (String) -> Unit = {}, val level: (String) -> Unit = {},
    val period: (SlotPeriod) -> Unit = {}, val output: (Int?) -> Unit = {},
    val calibrate: () -> Unit = {}, val tone: () -> Unit = {},
    val enable: () -> Unit = {}, val send: () -> Unit = {},
    val selectPeer: (ManualPeer) -> Unit = {},
    val useSuggestion: () -> Unit = {}, val resetQso: () -> Unit = {},
    val startAutomatic: (Boolean) -> Unit = {}, val stopAutomatic: () -> Unit = {},
)

@Composable
fun ManualOperatingPanel(state: ManualOperatingState, actions: ManualActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Phone audio operation", style = MaterialTheme.typography.titleMedium)
        Text("Phone speaker → radio microphone / VOX. Set the radio dial and mode manually. Audio output does not confirm radio TX or a completed QSO. Keep this page visible.", style = MaterialTheme.typography.bodySmall)
        Text("AutoSeq: ${state.autoSequence.reason} · sent ${state.autoSequence.transmissions}")
        if (state.autoSequence.active) OutlinedButton(onClick = actions.stopAutomatic) { Text("Stop AutoSeq") }
        else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.startAutomatic(true) }, enabled = state.canArm) { Text("Auto CQ") }
            OutlinedButton(onClick = { actions.startAutomatic(false) }, enabled = state.canArm && state.peer.isNotEmpty()) { Text("Auto answer") }
        }
        Text("AutoSeq continues across screens and screen-off: three sends per message, a two-minute no-progress timeout and a four-minute session limit. Edits or Stop cancel it; it never restarts itself.", style = MaterialTheme.typography.bodySmall)
        state.qso?.let { qso ->
            Text("QSO: ${qso.phase.name.lowercase().replace('_', ' ')} · ${qso.peer ?: "No peer"}")
            state.suggestion?.let { suggestion ->
                Text("Suggested: ${suggestion.text}")
                OutlinedButton(onClick = actions.useSuggestion, enabled = !state.busy) { Text("Use suggested message") }
            }
            OutlinedButton(onClick = actions.resetQso, enabled = !state.busy) { Text("Reset QSO") }
            state.qsoNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Suggestions never arm or send audio. Use a suggestion, then enable and send one slot explicitly.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(state.peer, actions.peer, label = { Text("CB peer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.report, actions.report, label = { Text("Report (dB) · editable estimate") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ManualMessage.CQ, ManualMessage.REPLY, ManualMessage.REPORT).forEach { choice ->
                FilterChip(selected = state.choice == choice, onClick = { actions.choose(choice) }, label = { Text(choice.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(ManualMessage.ROGER, ManualMessage.RR73, ManualMessage.SIGNOFF).forEach { choice ->
                FilterChip(selected = state.choice == choice, onClick = { actions.choose(choice) }, label = { Text(choice.label) })
            }
        }
        OutlinedTextField(state.text, actions.custom, label = { Text("TX message") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            Checkbox(state.freeText, actions.freeText, modifier = Modifier.semantics { contentDescription = "Encode as free text" })
            Text("Encode as free text (13 characters)")
        }
        when (val packed = state.packed) {
            is PackResult.Unsupported -> Text(packed.reason, color = MaterialTheme.colorScheme.error)
            is PackResult.Encoded -> {
                Text("Encoded: ${packed.transmittedText}")
                if (packed.contentChanged) Row {
                    Checkbox(state.acceptChanges, actions.accept, modifier = Modifier.semantics { contentDescription = "Accept TX content changes" })
                    Text("Use the encoded text shown above, including content changes")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.period == SlotPeriod.EVEN, onClick = { actions.period(SlotPeriod.EVEN) }, label = { Text("Even · 00/30") })
            FilterChip(selected = state.period == SlotPeriod.ODD, onClick = { actions.period(SlotPeriod.ODD) }, label = { Text("Odd · 15/45") })
        }
        state.outputs.forEach { output ->
            FilterChip(selected = output.id == state.outputId, onClick = { actions.output(output.id) }, label = { Text(output.label) })
        }
        OutlinedButton(onClick = { actions.output(null) }, enabled = state.outputId != null) { Text("Clear operating speaker") }
        OutlinedTextField(state.level, actions.level, label = { Text("TX audio level (%)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.calibrate, enabled = state.available && !state.busy && state.outputId != null) { Text("Calibrate speaker") }
            OutlinedButton(onClick = actions.tone, enabled = state.available && !state.busy && state.outputId != null) { Text("Test tone · 2 s") }
        }
        state.latency?.let { Text(java.lang.String.format(java.util.Locale.ROOT, "Speaker latency: %.1f ms · valid for 5 minutes", it.nanos / 1e6)) }
        state.validation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("Each completed speaker slot disables TX. Choose and enable the next message manually; an unused TX arm expires after two minutes. The session continues across screens, rotation and screen-off. Stop, edits, profile changes or audio/time errors disarm TX; interrupted slots are not added to Sent.", style = MaterialTheme.typography.bodySmall)
    }
}
