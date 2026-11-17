package net.vash.ft8cbdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.time.TimeUiState

/** Read-only clock presentation. Updating this strip never starts audio or requests time. */
@Composable
fun Ft8PeriodStrip(time: TimeUiState) {
    val period = time.period
    val parity = if (period?.odd == true) "Odd" else "Even"
    val seconds = (period?.elapsedMillis ?: 0) / 1000
    val basis = if (time.periodCorrected) "Corrected UTC" else "Device UTC (not synchronized)"
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
        Column(Modifier.fillMaxWidth(.65f).widthIn(max = 240.dp).semantics(mergeDescendants = true) {
            contentDescription = if (period == null) "FT8 period timer loading" else "FT8 period: $parity, $seconds of 15 seconds; $basis"
        }, horizontalAlignment = Alignment.End) {
            Text(if (period == null) "Period timer loading" else "$parity $seconds / 15 s · ${if (time.periodCorrected) "UTC" else "Unsynced"}",
                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = RadioColors.muted)
            LinearProgressIndicator(progress = { period?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (time.periodCorrected) RadioColors.olive else RadioColors.muted,
                trackColor = RadioColors.line)
        }
    }
}
