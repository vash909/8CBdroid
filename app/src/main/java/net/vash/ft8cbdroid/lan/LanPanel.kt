package net.vash.ft8cbdroid.lan

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.vash.ft8cbdroid.protocol.LanPhase

@Composable
fun LanPanel(
    state: LanUiState,
    onPortChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReplay: () -> Unit,
    onClear: () -> Unit,
) {
    Text("WSJT-CB on your local network", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        value = state.desktopAddress, onValueChange = onAddressChange,
        label = { Text("Desktop IPv4 address (optional)") },
        supportingText = { Text("Leave blank to accept the first desktop heartbeat.") },
        placeholder = { Text("192.168.1.10") },
        singleLine = true, enabled = state.canEdit, isError = !state.validAddress,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.port, onValueChange = onPortChange,
        label = { Text("Phone UDP listening port") },
        supportingText = { Text("1–65535; use the same port in WSJT-CB.") },
        singleLine = true, enabled = state.canEdit, isError = !state.validPort,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onStart, enabled = state.canStart) { Text("Start listening") }
        OutlinedButton(onClick = onStop, enabled = state.active && !state.busy) { Text("Stop") }
    }
    (state.error ?: state.connection.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (state.connection.phase) {
                LanPhase.STOPPED -> "Listener stopped"
                LanPhase.LISTENING -> "Listening on UDP ${state.connection.listenPort} — waiting for a desktop"
                LanPhase.CONNECTED -> "Desktop connected"
                LanPhase.ERROR -> "Listener unavailable"
            }, style = MaterialTheme.typography.titleMedium)
            state.connection.peer?.let { peer ->
                Text("${peer.address}:${peer.port} • ${peer.instanceId}")
                Text("Desktop version: ${peer.version.ifEmpty { "not provided" }} • schema ${peer.schema}")
                if (state.connection.status == null) Text("No desktop status received yet.")
            }
            state.connection.status?.let { status ->
                Text(String.format(Locale.ROOT, "%.6f MHz • %s", status.dialFrequencyHz / 1_000_000.0, status.mode))
                Text("DX: ${status.dxCall.ifEmpty { "—" }} • report: ${status.report.ifEmpty { "—" }}")
                Text("TX enabled: ${status.txEnabled} • transmitting: ${status.transmitting} • decoding: ${status.decoding}")
            }
        }
    }
    Text("In WSJT-CB, set the UDP server to this phone's Wi-Fi IP address and the port above. Both devices must be on the same LAN.")
    Text("Keep the app in the foreground. Listening stops when it goes into the background. One desktop is tracked at a time; an inactive peer is released after 45 seconds.")
    DecodesPanel(state.connection.decodes, state.canReplay, onReplay, onClear)
    Text("This connection displays desktop status and decodes. Logging, audio and radio control are not available yet.")
}
