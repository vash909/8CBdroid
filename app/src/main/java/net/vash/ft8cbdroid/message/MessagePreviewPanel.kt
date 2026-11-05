package net.vash.ft8cbdroid.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.core.message.CbMessageCodec
import net.vash.ft8cbdroid.core.message.EncodingMode
import net.vash.ft8cbdroid.core.message.MessageFormat
import net.vash.ft8cbdroid.core.message.MessagePart
import net.vash.ft8cbdroid.core.message.PackResult

@Composable
fun MessagePreviewPanel() {
    var text by rememberSaveable { mutableStateOf("CQ 1AT106") }
    var freeText by rememberSaveable { mutableStateOf(false) }
    val result = remember(text, freeText) {
        CbMessageCodec.pack(text, if (freeText) EncodingMode.FREE_TEXT else EncodingMode.AUTOMATIC)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Message preview", style = MaterialTheme.typography.titleLarge)
        Text("Check how WSJT-CB encodes your message. This preview runs offline; live transmission is pending.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !freeText, onClick = { freeText = false }, label = { Text("Automatic") })
            FilterChip(selected = freeText, onClick = { freeText = true }, label = { Text("Free text") })
        }
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 37) text = it },
            label = { Text("Message") },
            placeholder = { Text("<1AT106> 26AT101 R-10") },
            supportingText = { Text("${text.length}/37 characters; free text carries at most 13") },
            isError = result is PackResult.Unsupported,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        when (result) {
            is PackResult.Unsupported -> Text(result.reason, color = MaterialTheme.colorScheme.error)
            is PackResult.Encoded -> {
                Text("Encoded message", style = MaterialTheme.typography.titleMedium)
                Text(result.transmittedText)
                val format = when (result.message.format) {
                    MessageFormat.FREE_TEXT -> "Free text"
                    MessageFormat.STANDARD -> "Standard message"
                    MessageFormat.STANDARD_PORTABLE -> "Portable standard message"
                    MessageFormat.NONSTANDARD_CALL -> "Nonstandard callsign message"
                }
                Text(format)
                if (result.contentChanged) {
                    Text("The encoded text differs from your input. A report, locator or part of the text may be lost.",
                        color = MaterialTheme.colorScheme.error)
                }
                if (result.message.parts.any { it is MessagePart.UnresolvedCall }) {
                    Text("A station without the matching callsign hash would read:")
                    Text(result.message.text)
                }
            }
        }
    }
}
