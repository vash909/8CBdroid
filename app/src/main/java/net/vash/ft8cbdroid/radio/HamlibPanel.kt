package net.vash.ft8cbdroid.radio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HamlibPanel(state: HamlibUiState, onLoad: () -> Unit, onQuery: (String) -> Unit,
    onSelect: (Int) -> Unit, onDummy: () -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hamlib model catalog", style = MaterialTheme.typography.titleMedium)
            Text("Browse protocol metadata. No radio connection or CAT control is available yet.")
            OutlinedButton(onClick = onLoad, enabled = state.busy == null) {
                Text(if (state.catalog == null) "Load Hamlib models" else "Reload Hamlib models")
            }
            state.catalog?.let { catalog ->
                Text("${catalog.version} · ${catalog.models.size} catalog entries")
                Text("Catalog entries are not tested radio compatibility.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = state.query, onValueChange = onQuery,
                    label = { Text("Search manufacturer, model or ID") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                val matches = state.matches
                Text("${matches.size} matches · showing up to 12")
                matches.take(12).forEach { model ->
                    TextButton(onClick = { onSelect(model.id) }) {
                        Text("${model.manufacturer} ${model.name} · #${model.id}")
                    }
                }
                if (matches.isEmpty()) Text("No matching models. Try another name or ID.")
                state.selected?.let { model ->
                    HorizontalDivider()
                    Text("Model #${model.id}: ${model.manufacturer} ${model.name}")
                    Text("Upstream driver status: ${model.driverStatus}")
                    Text("Declared reads: frequency ${yesNo(model.readsFrequency)}, mode ${yesNo(model.readsMode)}, PTT ${yesNo(model.readsPtt)}")
                    if (model.baudMax > 0) Text("Serial rate range: ${model.baudMin}–${model.baudMax} baud")
                    Text("These are backend declarations, not verified operations on your radio.", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
            Text("Software-only Dummy check", style = MaterialTheme.typography.titleSmall)
            Text("Exercises a simulated radio inside Hamlib. No USB, network radio, audio or physical PTT is used.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onDummy, enabled = state.busy == null) { Text("Run Dummy check") }
            state.dummy?.let { Text("Dummy passed · 27.265 MHz · USB mode · PTT off") }
            if (state.busy != null) {
                Text(if (state.busy == HamlibWork.CATALOG) "Loading Hamlib models…" else "Checking Dummy…")
                OutlinedButton(onClick = onCancel) { Text("Cancel Hamlib check") }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
private fun yesNo(value: Boolean) = if (value) "yes" else "no"
