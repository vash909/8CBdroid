package net.vash.ft8cbdroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.vash.ft8cbdroid.audio.*
import net.vash.ft8cbdroid.operate.*
import net.vash.ft8cbdroid.core.message.ManualPeer
import net.vash.ft8cbdroid.core.message.manualPeer
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.core.recording.RecordedDecodeFlag
import kotlin.math.roundToInt
import net.vash.ft8cbdroid.lan.LanUiState
import net.vash.ft8cbdroid.protocol.LanPhase
import net.vash.ft8cbdroid.settings.StationSettingsUiState
import net.vash.ft8cbdroid.time.TimeUiState
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val utcTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC)

data class ActivityMessage(val time: String, val message: String, val signal: String, val offset: Int,
    val detail: String, val peer: ManualPeer? = null)

/** Local estimates and desktop reports keep their source and confidence labels. */
fun activityMessages(source: OperatingSource, capture: CaptureUiState, lan: LanUiState, ownCall: String = ""): List<ActivityMessage> =
    if (source == OperatingSource.LOCAL) capture.live.rows.map { row ->
        ActivityMessage(utcTime.format(row.decode.slotStartUtc), when (val text = row.decode.message) {
            is UnpackResult.Decoded -> text.message.text
            is UnpackResult.Unsupported -> "Unsupported: ${text.reason}"
        }, row.decode.frame.estimatedSnrDb?.let { String.format(Locale.ROOT, "%+.0f*", it) } ?: "—",
            row.decode.frame.audioFrequencyHz.roundToInt(), buildList {
                add("Local audio · estimated SNR* · DT ${String.format(Locale.ROOT, "%+.2f", row.decode.deltaTimeSeconds)} s")
                if (RecordedDecodeFlag.UNKNOWN_HASH in row.decode.flags) add("Unknown hash")
                if (RecordedDecodeFlag.AMBIGUOUS_HASH in row.decode.flags) add("Ambiguous hash")
            }.joinToString(" · "), manualPeer(row, ownCall))
    } else lan.connection.decodes.asReversed().map { row ->
        val time = row.utcMillisOfDay?.let { String.format(Locale.ROOT, "%02d:%02d:%02d", it / 3_600_000, it / 60_000 % 60, it / 1000 % 60) } ?: "—"
        ActivityMessage(time, row.message.ifEmpty { "(empty message)" }, String.format(Locale.ROOT, "%+d", row.snr),
            row.deltaFrequencyHz.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt(),
            listOfNotNull(if (row.isNew) "Desktop · live · ${row.mode}" else "Desktop · replay · ${row.mode}", if (row.offAir) "Off air" else null,
                if (row.lowConfidence) "Low confidence" else null).joinToString(" · "))
    }

@Composable
fun OperatePanel(station: StationSettingsUiState, source: OperatingSource, capture: CaptureUiState,
    lan: LanUiState, time: TimeUiState, onDestination: (AppDestination) -> Unit,
    onReceive: () -> Unit, onMonitor: () -> Unit, onStop: () -> Unit,
    onView: (SpectrumView) -> Unit, spectrum: @Composable () -> Unit,
    profileName: String? = null, referenceFrequencyHz: Long = 27_265_000L, profileReady: Boolean = true,
    manual: ManualOperatingState = ManualOperatingState(), manualActions: ManualActions = ManualActions()) {
    var sent by rememberSaveable { mutableStateOf(false) }
    val receiving = if (source == OperatingSource.LOCAL) capture.phase != CapturePhase.IDLE || capture.awaitingPermission else lan.active || lan.busy
    val rows = remember(source, capture.live.rows, lan.connection.decodes, station.saved?.callsign) { activityMessages(source, capture, lan, station.saved?.callsign.orEmpty()) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val small = source == OperatingSource.LOCAL || maxHeight < 740.dp || LocalDensity.current.fontScale > 1.2f || capture.error != null || lan.error != null
        Column(Modifier.fillMaxSize().then(if (small) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onDestination(AppDestination.PROFILES) }, contentPadding = PaddingValues(0.dp)) {
                Text(if (profileReady) "Profile: ${profileName ?: "Default"}" else "Profiles need attention · open settings",
                    style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("8CBdroid", style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace, color = RadioColors.gold)
                    val dial = if (source == OperatingSource.LOCAL) String.format(Locale.ROOT, "%.3f", referenceFrequencyHz / 1_000_000.0) else lan.connection.status?.let {
                        String.format(Locale.ROOT, "%.3f", it.dialFrequencyHz / 1_000_000.0)
                    } ?: "—"
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(dial, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Text("MHz / ${if (source == OperatingSource.LOCAL) "FT8" else lan.connection.status?.mode?.ifBlank { "—" } ?: "—"}",
                            Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = RadioColors.muted)
                    }
                    Text(if (source == OperatingSource.LOCAL) "PROFILE REFERENCE DIAL" else "DESKTOP DIAL · READ ONLY",
                        style = MaterialTheme.typography.labelSmall, color = RadioColors.muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onDestination(AppDestination.STATION) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(station.saved?.callsign?.takeIf { it.isNotBlank() } ?: "Set station")
                        station.saved?.grid?.takeIf { it.isNotBlank() }?.let { Text(" / $it", color = RadioColors.muted, style = MaterialTheme.typography.labelSmall) }
                    }
                    Text(if (!receiving) "RX IDLE" else if (source == OperatingSource.LAN) {
                        if (lan.connection.status?.transmitting == true) "DESKTOP TX"
                        else if (lan.connection.phase == LanPhase.CONNECTED) "LAN CONNECTED" else "LISTENING"
                    } else if (capture.decodeLive) "RECEIVING" else "MONITORING",
                        style = MaterialTheme.typography.labelMedium, color = if (receiving) RadioColors.olive else RadioColors.muted)
                }
            }
            Surface(shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, RadioColors.line)) {
                Box(Modifier.padding(6.dp)) {
                    if (source == OperatingSource.LOCAL) spectrum() else Box(Modifier.fillMaxWidth().height(108.dp), contentAlignment = Alignment.Center) {
                        Text("LAN carries messages, not waterfall audio.", style = MaterialTheme.typography.bodySmall, color = RadioColors.muted)
                    }
                    TextButton(onClick = { onDestination(if (source == OperatingSource.LOCAL) AppDestination.CAPTURE else AppDestination.LAN) },
                        modifier = Modifier.align(Alignment.TopEnd).background(RadioColors.background.copy(alpha = .9f), MaterialTheme.shapes.small),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(if (source == OperatingSource.LOCAL) "Expand ↗" else "Connection ↗", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            ActivityLog(rows, sent, { sent = it }, source,
                if (small) Modifier.fillMaxWidth().height(200.dp) else Modifier.fillMaxWidth().weight(1f), onView, capture.spectrumView,
                manual.sent.map { entry -> ActivityMessage(utcTime.format(java.time.Instant.ofEpochMilli(entry.utcMillis)), entry.text,
                    "—", entry.frequencyHz.roundToInt(), "Completed speaker audio · radio TX unconfirmed · reference dial ${entry.referenceDialHz} Hz") },
                manualActions.selectPeer)
            (if (source == OperatingSource.LOCAL) capture.error else lan.error ?: lan.connection.error)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OffsetField("RX Hz", capture.spectrumView.rxHz, source == OperatingSource.LOCAL, Modifier.weight(1f)) {
                    onView(capture.spectrumView.copy(cursor = OffsetCursor.RX).select(it.toFloat()))
                }
                OutlinedTextField(value = if (source == OperatingSource.LOCAL) manual.frequency else "—", onValueChange = manualActions.frequency,
                    label = { Text("TX Hz") }, singleLine = true, enabled = source == OperatingSource.LOCAL,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                Surface(color = RadioColors.panel, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, RadioColors.line)) {
                    Text(if (source == OperatingSource.LAN) "LOCAL TX OFF" else if (manual.busy) when (manual.phase) {
                        ManualPhase.CALIBRATING -> "CALIBRATING"
                        ManualPhase.GENERATING -> "ENCODING"
                        ManualPhase.WAITING -> "TX QUEUED"
                        ManualPhase.PLAYING -> "TX AUDIO"
                        ManualPhase.TONE -> "TEST TONE"
                        else -> "CLEANUP"
                    } else if (manual.armed) "TX ARMED" else "TX OFF", Modifier.padding(10.dp), style = MaterialTheme.typography.labelMedium, color = RadioColors.muted)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onReceive, enabled = profileReady && !receiving && if (source == OperatingSource.LOCAL) capture.canStart else lan.canStart,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Start RX") }
                OutlinedButton(onClick = onStop, enabled = receiving || manual.busy || manual.armed, modifier = Modifier.weight(.7f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Stop") }
                OutlinedButton(onClick = manualActions.enable, enabled = source == OperatingSource.LOCAL && !manual.busy && (manual.armed || manual.canArm), modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (manual.armed) "Disable TX" else "Enable TX") }
            }
            if (source == OperatingSource.LOCAL) {
                manual.queuedUtcMillis?.let { Text("Queued UTC: ${java.time.Instant.ofEpochMilli(it)}", style = MaterialTheme.typography.bodySmall) }
                manual.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                manual.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = manualActions.send, enabled = manual.armed && !manual.busy, modifier = Modifier.fillMaxWidth()) { Text("Send one slot") }
                TextButton(onClick = onMonitor, enabled = profileReady && !receiving && capture.canStart) { Text("Monitor") }
                ManualOperatingPanel(manual, manualActions)
            } else Text("Local audio TX is disabled in LAN mode.", style = MaterialTheme.typography.bodySmall)
            // The correction remains explicit; this summary never implies system-clock synchronization.
            Text(time.sourceDescription, style = MaterialTheme.typography.labelSmall, color = RadioColors.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OffsetField(label: String, value: Int, enabled: Boolean, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(value = if (enabled) text else "—", onValueChange = { next ->
        if (next.length <= 4 && next.all { it in '0'..'9' }) { text = next; audioOffset(next)?.let(onValue) }
    }, label = { Text(label) }, enabled = enabled, isError = enabled && audioOffset(text) == null, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
}

@Composable
private fun ActivityLog(rows: List<ActivityMessage>, sent: Boolean, onSent: (Boolean) -> Unit,
    source: OperatingSource, modifier: Modifier, onView: (SpectrumView) -> Unit, view: SpectrumView, sentRows: List<ActivityMessage> = emptyList(), onPeer: (ManualPeer) -> Unit = {}) {
    Surface(modifier, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, RadioColors.line)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { onSent(false) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("Received ${rows.size}", color = if (!sent) RadioColors.gold else RadioColors.muted)
                }
                TextButton(onClick = { onSent(true) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Text("Sent ${sentRows.size}", color = if (sent) RadioColors.gold else RadioColors.muted)
                }
                Spacer(Modifier.weight(1f))
                Text(if (source == OperatingSource.LOCAL) "LOCAL" else "LAN", style = MaterialTheme.typography.labelSmall, color = RadioColors.olive)
            }
            HorizontalDivider(color = RadioColors.line)
            if ((if (sent) sentRows else rows).isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (sent) "No transmitted messages" else "Listening starts here", style = MaterialTheme.typography.bodyMedium)
                    Text(if (sent) "Only completed speaker slots appear here; this is not a QSO log." else "${if (source == OperatingSource.LOCAL) "Start RX after time synchronization" else "Start RX to listen for a desktop"}.",
                        style = MaterialTheme.typography.bodySmall, color = RadioColors.muted)
                }
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(if (sent) sentRows else rows) { _, row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.time, style = MaterialTheme.typography.labelSmall, color = RadioColors.muted)
                            Text("${row.signal} dB", style = MaterialTheme.typography.labelSmall, color = RadioColors.olive)
                            Text("${row.offset} Hz", style = MaterialTheme.typography.labelSmall, color = RadioColors.muted)
                        }
                        Text(row.message, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.detail, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = RadioColors.muted)
                            if (!sent && source == OperatingSource.LOCAL) TextButton(onClick = {
                                onView(view.copy(cursor = OffsetCursor.RX).select(row.offset.toFloat()))
                            }, contentPadding = PaddingValues(4.dp)) { Text("→ RX", style = MaterialTheme.typography.labelMedium) }
                            if (!sent && source == OperatingSource.LOCAL) row.peer?.let { peer ->
                                TextButton(onClick = { onPeer(peer) }, contentPadding = PaddingValues(4.dp)) { Text("Use peer", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        HorizontalDivider(color = RadioColors.line)
                    }
                }
            }
        }
    }
}
