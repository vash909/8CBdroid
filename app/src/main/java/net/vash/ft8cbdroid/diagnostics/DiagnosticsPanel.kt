package net.vash.ft8cbdroid.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.core.diagnostics.*
import java.util.Locale

@Composable
fun DiagnosticsPanel(state: DiagnosticsState, onStart: () -> Unit, onStop: () -> Unit,
    onDelete: () -> Unit, onExport: () -> Unit, onRetry: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    Text("Optional session diagnostics", style = MaterialTheme.typography.titleMedium)
    Text("Records technical counters, decode times, memory, CPU, battery and thermal observations every 30 seconds, for up to one hour. No audio, message text, callsigns, location or network addresses are saved.")
    Text("Collection stays on this phone unless you export a report. It does not start RX or TX. Keep a receive session running for a sustained-operation test.")
    if (state.loading) Text("Loading diagnostics…")
    if (state.canStart) Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start diagnostics") }
    if (state.active) Button(onClick = onStop, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Stop diagnostics") }
    state.report?.let { report ->
        Text(when (report.end) {
            DiagnosticEnd.RUNNING -> "Collecting diagnostics"
            DiagnosticEnd.OPERATOR -> "Collection stopped"
            DiagnosticEnd.LIMIT -> "One-hour collection limit reached"
            DiagnosticEnd.INTERRUPTED -> "Collection interrupted; it has not restarted"
        })
        Text("Elapsed: ${report.elapsedMillis / 1000} s • Samples: ${report.samples.size} / ${SessionDiagnostics.MAX_SAMPLES}")
        Text("Complete audio windows: ${report.events[ReceiveDiagnosticEvent.WINDOW_COMPLETE] ?: 0}")
        Text("Incomplete windows discarded: ${report.events[ReceiveDiagnosticEvent.WINDOW_DISCARDED] ?: 0}")
        Text("Decoded windows: ${report.events[ReceiveDiagnosticEvent.DECODE_COMPLETE] ?: 0} • Failed: ${report.events[ReceiveDiagnosticEvent.DECODE_FAILED] ?: 0}")
        Text("Unprocessed windows (overload or cancellation): ${report.events[ReceiveDiagnosticEvent.WINDOW_UNPROCESSED] ?: 0}")
        if (report.decodeCount > 0) Text(String.format(Locale.ROOT, "Decode time: %.1f ms average • %d ms maximum", report.decodeTotalMillis.toDouble() / report.decodeCount, report.decodeMaxMillis))
        report.samples.lastOrNull()?.resources?.let { r ->
            r.pssKiB?.let { Text(String.format(Locale.ROOT, "Process memory (PSS): %.1f MiB", it / 1024.0)) }
            r.batteryTemperatureC?.let { Text(String.format(Locale.ROOT, "Battery temperature: %.1f °C", it)) }
            Text(when (r.plugged) { true -> "External power connected; battery drain cannot be inferred."; false -> "Running on battery; level changes are an estimate."; null -> "Power source unavailable." })
        }
        OutlinedButton(onClick = onExport, enabled = !state.busy && !state.exporting && !state.choosing) { Text("Export diagnostic report") }
    }
    if (!state.loading && !state.busy && (state.report != null || state.error != null))
        TextButton(onClick = { confirmDelete = true }, enabled = !state.exporting) { Text("Delete diagnostic report") }
    if (state.error != null) {
        Text(state.error, color = MaterialTheme.colorScheme.error)
        if (state.report == null && !state.busy && !state.loading) OutlinedButton(onClick = onRetry) { Text("Retry loading diagnostics") }
    }
    state.notice?.let { Text(it) }
    Text("One report is retained, including after an interruption. Delete it before starting another. Exported copies are managed by their destination. Missing measurements are reported as unavailable; short or externally powered tests do not establish battery life.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete diagnostics?") }, text = { Text("Stops collection and deletes this app's diagnostic report. RX and TX are unchanged. Exported copies remain at their destination.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete report") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}
