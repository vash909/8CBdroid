package net.vash.ft8cbdroid.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.core.ConnectionMode
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.core.profile.*

@Composable
fun ProfilesPanel(state: ProfilesUiState, onEdit: (ProfileDraft) -> Unit, onSelect: (String) -> Unit,
    onNew: () -> Unit, onDiscard: () -> Unit, onSave: () -> Unit, onApply: () -> Unit,
    onDelete: () -> Unit, onRetry: () -> Unit, onLoadModels: () -> Unit,
    onQuery: (String) -> Unit, onModel: (Int) -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Text("Save named setups for phone audio, USB CAT or a WSJT-CB desktop. Applying stops reception and clears time correction. Nothing connects or transmits automatically.")
    if (state.busy) Text("Working on profiles…")
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(it) }
    if (state.saved == null) {
        OutlinedButton(onClick = onRetry, enabled = !state.busy) { Text("Retry loading profiles") }
        return
    }
    Text("Saved profiles", style = MaterialTheme.typography.titleMedium)
    state.saved.profiles.forEach { p ->
        FilterChip(selected = p.id == state.draft?.value?.id, enabled = state.canEdit && !state.dirty,
            onClick = { onSelect(p.id) }, label = { Text(p.name + if (p.id == state.saved.activeId) " · active" else "") })
    }
    OutlinedButton(onClick = onNew, enabled = state.canEdit && !state.dirty && state.saved.profiles.size < 32) { Text("New profile") }
    val draft = state.draft ?: return
    val p = draft.value
    val enabled = state.canEdit
    fun change(value: OperatingProfile) = onEdit(draft.copy(value = value))
    OutlinedTextField(p.name, { change(p.copy(name = it.take(48))) }, label = { Text("Profile name") },
        singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
    Text("Connection setup", style = MaterialTheme.typography.titleMedium)
    ConnectionMode.entries.forEach { mode ->
        FilterChip(selected = p.mode == mode, enabled = enabled, onClick = { change(p.copy(mode = mode)) }, label = { Text(when (mode) {
            ConnectionMode.PHONE_VOX -> "Phone audio / VOX setup"
            ConnectionMode.USB_CAT -> "USB CAT setup"
            ConnectionMode.WSJT_CB_LAN -> "WSJT-CB LAN setup"
        }) })
    }
    NumericField("Reference dial (Hz)", draft.frequency, enabled) { onEdit(draft.copy(frequency = it.take(12))) }
    Text("Reference only: this value never tunes a radio. LAN displays the desktop's reported dial.", style = MaterialTheme.typography.bodySmall)
    Text("CAT backend: ${if (p.radio == null) "none" else "Hamlib"}")
    if (p.mode == ConnectionMode.USB_CAT || p.radio != null) {
        Text(p.radio?.let { "${it.label} · #${it.id} · ${it.version}" } ?: "No radio model selected")
        Text("CAT connection and physical radio control are not implemented. Catalog entries are not tested compatibility.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onLoadModels, enabled = enabled) { Text("Load radio models") }
        state.catalog?.let {
            OutlinedTextField(state.query, onQuery, label = { Text("Find radio model or ID") }, singleLine = true,
                enabled = enabled, modifier = Modifier.fillMaxWidth())
            Text("${state.models.size} serial models · showing up to 8")
            state.models.take(8).forEach { model ->
                TextButton(onClick = { onModel(model.id) }, enabled = enabled) { Text("${model.manufacturer} ${model.name} · #${model.id}") }
            }
            if (state.models.isEmpty()) Text("No matching serial radio models.")
        }
        NumericField("Serial rate (baud)", draft.baud, enabled) { onEdit(draft.copy(baud = it.take(8))) }
        Choice("Data bits", (5..8).toList(), p.serial.dataBits, enabled, { it.toString() }) { change(p.copy(serial = p.serial.copy(dataBits = it))) }
        Choice("Stop bits", listOf(1, 2), p.serial.stopBits, enabled, { it.toString() }) { change(p.copy(serial = p.serial.copy(stopBits = it))) }
        Choice("Parity", SerialParity.entries, p.serial.parity, enabled, { it.name }) { change(p.copy(serial = p.serial.copy(parity = it))) }
        Choice("Flow control", SerialFlow.entries, p.serial.flow, enabled, { it.name.replace('_', '/') }) { change(p.copy(serial = p.serial.copy(flow = it))) }
    }
    HorizontalDivider()
    Text("Audio routing is separate from CAT. Store a route type; choose the actual attached input in Audio & source. An unavailable route never falls back to the phone microphone.")
    Choice("Preferred input", ProfileAudioRoute.entries, p.audioInput, enabled, { it.label }) { change(p.copy(audioInput = it)) }
    Choice("Preferred output · saved only", ProfileAudioRoute.entries, p.audioOutput, enabled, { it.label }) { change(p.copy(audioOutput = it)) }
    Text("Live audio TX and VOX transmission are unavailable; Message tools offers a separate phone-speaker preview. USB audio requires separate physical qualification.", style = MaterialTheme.typography.bodySmall)
    Choice("Decoder", Ft8DecodeProfile.entries, p.decoder, enabled, { it.label }) { change(p.copy(decoder = it)) }
    HorizontalDivider()
    Text("LAN configuration", style = MaterialTheme.typography.titleMedium)
    NumericField("Profile UDP port", draft.port, enabled) { onEdit(draft.copy(port = it.take(6))) }
    OutlinedTextField(p.lan.desktopAddress, { change(p.copy(lan = p.lan.copy(desktopAddress = it.take(64)))) },
        label = { Text("Profile desktop IPv4 · optional") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
    Text("Blank accepts a desktop from any address. Callsign, locator and NTP server remain shared station settings.", style = MaterialTheme.typography.bodySmall)
    if (state.dirty) {
        Text("Unsaved profile changes. Save or discard before selecting another profile.")
        draft.validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
    OutlinedButton(onClick = onApply, enabled = state.canApply, modifier = Modifier.fillMaxWidth()) { Text("Apply profile") }
    OutlinedButton(onClick = onDiscard, enabled = enabled && state.dirty) { Text("Discard profile changes") }
    OutlinedButton(onClick = { confirmDelete = true }, enabled = enabled && !state.dirty && p.id != state.saved.activeId) { Text("Delete profile") }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${p.name}?") },
        text = { Text("This removes this saved setup from the phone.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun NumericField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
@Composable
private fun <T> Choice(label: String, values: List<T>, selected: T, enabled: Boolean, text: (T) -> String, onValue: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    // A column stays usable at large font sizes and on narrow phones.
    values.forEach { value -> FilterChip(selected = selected == value, onClick = { onValue(value) }, enabled = enabled, label = { Text(text(value)) }) }
}
