package net.vash.ft8cbdroid.time

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.vash.ft8cbdroid.core.time.NtpServers

data class TimeActions(
    val selectPreset: (String) -> Unit = {}, val changeCustom: (String) -> Unit = {},
    val save: () -> Unit = {}, val retry: () -> Unit = {}, val ntp: () -> Unit = {},
    val gps: () -> Unit = {}, val apply: () -> Unit = {}, val cancel: () -> Unit = {},
)

@Composable
fun TimePanel(state: TimeUiState, actions: TimeActions) {
    var expanded by remember { mutableStateOf(false) }
    val selected = NtpServers.presets.firstOrNull { it.id == state.draft.presetId }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Time synchronization", style = MaterialTheme.typography.titleLarge)
        Text("UTC slots require a valid NTP or GPS measurement. Only this app's time is corrected; Android's clock is unchanged.")
        Text("Active time: ${state.sourceDescription}")
        Text("An applied NTP source refreshes every five minutes while this app is visible. Updates wait for an idle audio boundary. GPS never starts an NTP request.")
        if (state.refreshing) Text("Refreshing NTP…")
        if (state.refreshPending) Text("NTP measurement ready; waiting for a safe audio boundary.")
        state.refreshWarning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = state.canEdit) {
                Text(selected?.let { "${it.region} • ${it.label}" } ?: "Custom NTP server")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                NtpServers.presets.forEach { preset ->
                    DropdownMenuItem(text = { Text("${preset.region} • ${preset.label}") },
                        onClick = { actions.selectPreset(preset.id); expanded = false })
                }
                DropdownMenuItem(text = { Text("Custom NTP server") }, onClick = { actions.selectPreset("custom"); expanded = false })
            }
        }
        selected?.let { Text("NTP server: ${it.host}") }
        OutlinedTextField(value = state.draft.customHost, onValueChange = actions.changeCustom,
            enabled = state.canEdit && state.draft.presetId == "custom", label = { Text("Custom NTP server") },
            placeholder = { Text("192.168.1.10 or ntp.example.org") },
            supportingText = { Text("IPv4, IPv6 or hostname • UDP port 123 • no URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Text("Choose your region or a nearby server. Worldwide uses anycast. No location permission is needed for NTP.")
        state.validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = actions.save, enabled = state.canSave) { Text(if (state.saving) "Saving time settings…" else "Save time settings") }
        if (state.loading) Text("Loading time settings…")
        if (!state.loading && state.saved == null) OutlinedButton(onClick = actions.retry) { Text("Retry time settings") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.ntp, enabled = state.canMeasure) { Text("Measure NTP") }
            OutlinedButton(onClick = actions.gps, enabled = state.canEdit) { Text("Measure GPS") }
        }
        if (state.awaitingGpsPermission) Text("Waiting for precise location permission…")
        state.busy?.let { Text("Measuring $it…"); OutlinedButton(onClick = actions.cancel) { Text("Cancel time measurement") } }
        state.measurement?.let { value ->
            Text(String.format(Locale.ROOT, "%s • correction %+.1f ms", value.source, value.offsetMillis()))
            Text(value.uncertaintyMillis?.let { String.format(Locale.ROOT, "Estimated uncertainty: ±%.1f ms", it) }
                ?: "Timing uncertainty is not reported by this GPS receiver.")
            value.roundTripMillis?.let { Text(String.format(Locale.ROOT, "Network round trip: %.1f ms", it)) }
            Text("Measurement age: ${(state.ageMillis ?: 0) / 1000} seconds • expires after 10 minutes")
            Button(onClick = actions.apply, enabled = state.canApply && (state.ageMillis ?: Long.MAX_VALUE) in 0 until 600_000) {
                Text("Apply measured time")
            }
        }
        Text("GPS works without Internet and needs precise location permission and a clear sky view. Coordinates are not stored. Measurements stop on background or rotation.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
    }
}
