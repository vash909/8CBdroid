package net.vash.ft8cbdroid.usb

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun UsbPanel(state: UsbInventoryState, onRefresh: () -> Unit, onPermission: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("USB OTG / CAT", style = MaterialTheme.typography.titleMedium)
            Text("Find USB interfaces and grant access. Radio CAT commands, frequency readout, PTT and USB audio are not implemented yet.")
            if (!state.hostAvailable) Text("USB host mode is unavailable on this device.")
            OutlinedButton(onClick = onRefresh, enabled = state.hostAvailable) { Text("Find USB devices") }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                !state.scanned && state.hostAvailable -> Text("Connect a USB data/OTG adapter and tap Find USB devices.")
                state.devices.isEmpty() && state.hostAvailable -> Text("No USB devices found. Check the OTG adapter, data cable and device power.")
            }
            state.devices.forEach { device ->
                HorizontalDivider()
                Text(String.format(Locale.ROOT, "VID %04X • PID %04X • device %d", device.vendorId, device.productId, device.deviceId))
                Text(device.driver?.let { "$it • ${device.portCount} serial port(s)" }
                    ?: "No supported serial driver. This may be an audio or other USB device.")
                Text(when (device.access) {
                    UsbAccess.NOT_REQUESTED -> "USB access not requested"
                    UsbAccess.REQUESTING -> "Waiting for USB permission…"
                    UsbAccess.GRANTED -> "USB access granted; radio connection is not implemented"
                    UsbAccess.DENIED -> "USB access denied. You can request it again."
                })
                if (device.serialRecognized && device.access != UsbAccess.GRANTED) {
                    OutlinedButton(onClick = { onPermission(device.key) }, enabled = state.pendingKey == null) {
                        Text(if (device.access == UsbAccess.DENIED) "Retry USB permission" else "Request USB permission")
                    }
                }
            }
            Text("A recognized serial chip does not identify the radio or confirm CAT compatibility. No port is opened and no command is sent.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
