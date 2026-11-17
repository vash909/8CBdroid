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
import net.vash.ft8cbdroid.core.logbook.*

@Composable
fun LogTransferPanel(state: LogTransferState, model: LogTransferViewModel, log: LogbookState) {
    val ready = log.loaded && !log.busy && log.draft == null && log.error == null && log.unsavedCompletions == 0 && !state.busy && !state.choosing
    Text("Log files", style = MaterialTheme.typography.titleMedium)
    Text("ADIF exchanges saved FT8 contacts. A full backup also preserves pending reviews, exact times, identities and provenance. Files contain callsigns, times and notes; share only with a destination you choose.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ model.choose(LogFileKind.ADIF) }, enabled = ready) { Text("Import ADIF") }
        OutlinedButton({ model.prepareExport(LogFileKind.ADIF) }, enabled = ready) { Text("Export ADIF") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ model.prepareExport(LogFileKind.ADIF, true) }, enabled = ready) { Text("Share ADIF") }
        OutlinedButton({ model.prepareExport(LogFileKind.BACKUP) }, enabled = ready) { Text("Back up log") }
    }
    OutlinedButton({ model.choose(LogFileKind.BACKUP) }, enabled = ready) { Text("Restore backup") }
    Text("ADIF band label", style = MaterialTheme.typography.labelLarge)
    for (policy in AdifBandPolicy.entries) FilterChip(selected = state.policy == policy, onClick = { model.policy(policy) }, enabled = ready,
        label = { Text(if (policy == AdifBandPolicy.WSJT_CB_11M) "WSJT-CB: include 11m" else "Frequency only: omit 11m") })
    Text("FREQ always keeps the exact dial in MHz. 11m is a WSJT-CB extension, used only within its pinned 26.965–27.405 MHz range. Other loggers may reject the band label or CB callsigns. ADIF times have one-second precision; Unicode notes use an app field and an escaped ASCII comment.", style = MaterialTheme.typography.bodySmall)
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (state.committing) "Saving reviewed entries atomically…" else "Preparing log file…")
        if (!state.committing) TextButton(model::cancel) { Text("Cancel transfer") }
    }
    state.notice?.let { Text(it) }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.retryExport) OutlinedButton(model::retryExport, enabled = !state.busy && !state.choosing) { Text("Retry export") }
    state.preview?.let { preview ->
        Text(if (preview.kind == LogFileKind.ADIF) "ADIF import preview" else "Backup restore preview", style = MaterialTheme.typography.titleLarge)
        Text("${preview.rows.count { it.status == LogEntryStatus.NEW }} new · ${preview.rows.count { it.status == LogEntryStatus.DUPLICATE }} possible duplicates · ${preview.rows.count { it.status == LogEntryStatus.EXISTING }} already present · ${preview.rows.count { it.status == LogEntryStatus.CONFLICT }} conflicts · ${preview.issues.size} rejected")
        Text("Existing entries are never overwritten. Conflicts stay unchanged. ADIF duplicates are unchecked; selecting one explicitly keeps a separate contact. Backup identities preserve historical entries; restoring a file is not new audio or RF evidence.")
        for (issue in preview.issues.take(12)) Text("Record ${issue.record}: ${issue.message}", color = MaterialTheme.colorScheme.error)
        for (warning in preview.warnings.take(12)) Text("Record ${warning.record}: ${warning.message}", style = MaterialTheme.typography.bodySmall)
        if (preview.issues.size > 12 || preview.warnings.size > 12) Text("Showing the first 12 errors/warnings; totals are above.")
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(preview.rows, key = { (if (it.record == null) "pending:" else "record:") + it.id }) { row ->
                Row {
                    Checkbox(row.id in state.selected, { model.select(row.id, it) }, enabled = !state.busy && row.status in setOf(LogEntryStatus.NEW, LogEntryStatus.DUPLICATE),
                        modifier = Modifier.semantics { contentDescription = "Select ${row.details.peerCall} at ${Instant.ofEpochMilli(row.details.startedUtcMillis)}" })
                    Column(Modifier.weight(1f)) {
                        Text("${row.details.ownCall} → ${row.details.peerCall} · ${if (row.pending == null) "saved contact" else "pending review"}")
                        Text("${Instant.ofEpochMilli(row.details.startedUtcMillis)} · ${mhz(row.details.dialHz)} MHz")
                        Text(when (row.status) { LogEntryStatus.NEW -> "New"; LogEntryStatus.DUPLICATE -> "Possible duplicate: keep only after review"; LogEntryStatus.EXISTING -> "Already present: skipped"; LogEntryStatus.CONFLICT -> "Identity conflict: existing entry kept" })
                    }
                }
            }
        }
        Row {
            Checkbox(state.reviewed, model::reviewed, enabled = !state.busy,
                modifier = Modifier.semantics { contentDescription = "Confirm reviewed callsigns and frequencies" })
            Text("I reviewed the selected callsigns, UTC times and dial frequencies.", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(model::apply, enabled = state.canApply && ready) { Text("Import selected") }
            OutlinedButton(model::refreshPreview, enabled = !state.busy) { Text("Refresh preview") }
        }
        TextButton(model::cancel, enabled = !state.busy) { Text("Cancel import") }
    }
    HorizontalDivider()
}
