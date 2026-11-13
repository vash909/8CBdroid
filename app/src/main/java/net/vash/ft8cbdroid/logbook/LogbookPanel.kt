package net.vash.ft8cbdroid.logbook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.Instant
import net.vash.ft8cbdroid.core.StationIdentity

@Composable
fun LogbookPanel(state: LogbookState, model: LogbookViewModel, station: StationIdentity?, referenceHz: Long) {
    // This page owns layout only. Persistence, validation and completion handling live outside Compose.
    val draft = state.draft
    if (draft != null) {
        Text(if (draft.expectedVersion != null) "Edit QSO" else if (draft.completionId != null) "Review completed exchange" else "Manual QSO",
            style = MaterialTheme.typography.titleLarge)
        Text(if (draft.completionId != null) "Source: local phone audio. Review the contact and confirm the radio dial; audio completion alone is not RF proof."
            else "Source: manual operator entry. Confirm the contact details and radio dial frequency.")
        LogField("Station CB callsign", draft.ownCall, state.busy) { model.edit { d -> d.copy(ownCall = it) } }
        LogField("Peer CB callsign", draft.peerCall, state.busy) { model.edit { d -> d.copy(peerCall = it) } }
        LogField("Start UTC", draft.startUtc, state.busy) { model.edit { d -> d.copy(startUtc = it) } }
        LogField("End UTC", draft.endUtc, state.busy) { model.edit { d -> d.copy(endUtc = it) } }
        Text("UTC format: 2026-11-13T12:00:00Z", style = MaterialTheme.typography.bodySmall)
        LogField("Station locator (optional)", draft.ownGrid, state.busy) { model.edit { d -> d.copy(ownGrid = it) } }
        LogField("Peer locator (optional)", draft.peerGrid, state.busy) { model.edit { d -> d.copy(peerGrid = it) } }
        LogField("Radio dial frequency (MHz)", draft.dialMhz, state.busy) {
            model.edit { d -> d.copy(dialMhz = it, dialConfirmed = false) }
        }
        Row {
            Checkbox(draft.dialConfirmed, { model.edit { d -> d.copy(dialConfirmed = it) } }, enabled = !state.busy,
                modifier = Modifier.semantics { contentDescription = "Confirm actual radio dial frequency" })
            Text("I confirm this is the actual radio dial frequency; it was not read through CAT.", Modifier.weight(1f))
        }
        Text("Mode: FT8. Radio dial and audio offset are stored separately; no measured RF frequency is claimed.", style = MaterialTheme.typography.bodySmall)
        LogField("Sent report (dB, optional)", draft.sent, state.busy) { model.edit { d -> d.copy(sent = it) } }
        LogField("Received report (dB, optional)", draft.received, state.busy) { model.edit { d -> d.copy(received = it) } }
        LogField("Notes", draft.notes, state.busy) { model.edit { d -> d.copy(notes = it) } }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(model::save, enabled = !state.busy) { Text("Save QSO") }
            OutlinedButton(model::cancel, enabled = !state.busy) { Text("Cancel") }
        }
        Text("Cancel keeps a pending exchange available for review. Unsaved manual edits are discarded.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("${state.snapshot.records.size} saved · ${state.snapshot.pending.size} pending", style = MaterialTheme.typography.titleMedium)
        state.notice?.let { Text(it) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.unsavedCompletions > 0) Text("${state.unsavedCompletions} completed exchange(s) still need a successful write. Retry before closing the app.", color = MaterialTheme.colorScheme.error)
        if (!state.loaded || state.error != null) OutlinedButton(model::reload, enabled = !state.busy) { Text("Retry log") }
        Button({ model.manual(station, referenceHz) }, enabled = state.loaded && !state.busy) { Text("Add manual QSO") }
        for (pending in state.snapshot.pending) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Pending: ${pending.details.ownCall} → ${pending.details.peerCall}")
                    Text("${Instant.ofEpochMilli(pending.details.endedUtcMillis)} · local audio")
                    Text("Reference dial: ${mhz(pending.details.dialHz)} MHz · TX audio: ${pending.txAudioHz} Hz")
                    OutlinedButton({ model.review(pending.id) }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = "Review ${pending.details.peerCall} exchange at ${Instant.ofEpochMilli(pending.details.endedUtcMillis)}" }) { Text("Review and log") }
                }
            }
        }
        OutlinedTextField(state.query, model::query, label = { Text("Search callsign or notes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (state.visible.isEmpty()) Text(if (state.query.isBlank()) "No saved QSOs." else "No QSOs match this search.")
        else LazyColumn(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 650.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.visible, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${entry.details.ownCall} → ${entry.details.peerCall}", style = MaterialTheme.typography.titleMedium)
                        Text("${Instant.ofEpochMilli(entry.details.startedUtcMillis)} → ${Instant.ofEpochMilli(entry.details.endedUtcMillis)}")
                        Text("FT8 · confirmed dial ${mhz(entry.details.dialHz)} MHz")
                        Text(if (entry.completionId == null) "Manual entry" else "Local audio · operator reviewed")
                        Text(entry.routeDescription, style = MaterialTheme.typography.bodySmall)
                        Text("Time: ${if (entry.timeBasis == net.vash.ft8cbdroid.core.logbook.QsoTimeBasis.CORRECTED_APP_UTC) "corrected app UTC" else "operator entered UTC"}")
                        Text("Reports: sent ${entry.details.sentReport ?: "—"} / received ${entry.details.receivedReport ?: "—"}")
                        entry.txAudioHz?.let { Text("TX audio offset: $it Hz") }
                        if (entry.details.notes.isNotBlank()) Text(entry.details.notes)
                        OutlinedButton({ model.editRecord(entry.id) }, enabled = !state.busy) { Text("Edit QSO") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogField(label: String, value: String, busy: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
}
