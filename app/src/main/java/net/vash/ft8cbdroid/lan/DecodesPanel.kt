package net.vash.ft8cbdroid.lan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.vash.ft8cbdroid.protocol.DecodeHistory
import net.vash.ft8cbdroid.protocol.LanDecode

fun formatDecodeTime(millis: Long?): String {
    if (millis == null) return "UTC time unavailable"
    return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d UTC",
        millis / 3_600_000, (millis / 60_000) % 60, (millis / 1_000) % 60, millis % 1_000)
}

@Composable
fun DecodesPanel(
    decodes: List<LanDecode>,
    canReplay: Boolean,
    onReplay: () -> Unit,
    onClear: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("LAN band activity", style = MaterialTheme.typography.titleMedium)
            Text("Received from WSJT-CB; no local FT8 decoding.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReplay, enabled = canReplay) { Text("Request replay") }
                TextButton(onClick = onClear, enabled = decodes.isNotEmpty()) { Text("Clear list") }
            }
            Text("Replay requires Accept UDP requests in WSJT-CB. Clear list affects this phone only.",
                style = MaterialTheme.typography.bodySmall)
            if (decodes.isEmpty()) {
                Text("No LAN decodes received. Connect a desktop, then wait for a decode or request replay.")
            } else {
                Text("${decodes.size} / ${DecodeHistory.LIMIT} messages • newest received first",
                    style = MaterialTheme.typography.labelSmall)
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(decodes.asReversed()) { decode ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(decode.message.ifEmpty { "(empty message)" }, style = MaterialTheme.typography.bodyLarge)
                            Text(formatDecodeTime(decode.utcMillisOfDay), style = MaterialTheme.typography.labelSmall)
                            Text(String.format(Locale.ROOT, "%d dB • Δt %.2f s • %d Hz • %s",
                                decode.snr, decode.deltaTimeSeconds, decode.deltaFrequencyHz, decode.mode),
                                style = MaterialTheme.typography.bodySmall)
                            val flags = buildList {
                                add(if (decode.isNew) "Live" else "Replay")
                                if (decode.lowConfidence) add("Low confidence")
                                if (decode.offAir) add("Off air")
                            }
                            Text(flags.joinToString(" • "), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
