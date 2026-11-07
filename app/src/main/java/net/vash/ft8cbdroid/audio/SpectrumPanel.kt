package net.vash.ft8cbdroid.audio

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vash.ft8cbdroid.core.audio.*
import java.time.Instant
import kotlin.math.roundToInt

/** Color conversion is display work on a worker, independent of both the audio reader and FT8 engine. */
private fun waterfall(frames: List<SpectrumFrame>): ImageBitmap {
    val width = SpectrumFft.BINS
    val pixels = IntArray(width * SpectrumMonitor.HISTORY)
    frames.forEachIndexed { row, frame ->
        frame.dbfs.forEachIndexed { bin, db ->
            val t = ((db + 90) / 80).coerceIn(0f, 1f)
            val r = ((t - .45f).coerceAtLeast(0f) / .55f * 255).roundToInt()
            val g = ((t - .15f).coerceAtLeast(0f) / .85f * 240).roundToInt()
            val b = (35 + minOf(t * 2, 1f) * 200).roundToInt()
            pixels[row * width + bin] = android.graphics.Color.rgb(r, g, b)
        }
    }
    return Bitmap.createBitmap(pixels, width, SpectrumMonitor.HISTORY, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private data class RenderedSpectrum(val sessionId: Long, val image: ImageBitmap)

@Composable
fun SpectrumPanel(state: SpectrumState, view: SpectrumView, markers: List<SpectrumMarker>, onView: (SpectrumView) -> Unit) {
    val image by produceState<RenderedSpectrum?>(null, state.sessionId, state.frames) {
        value = if (state.frames.isEmpty()) null else withContext(Dispatchers.Default) { RenderedSpectrum(state.sessionId, waterfall(state.frames)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Spectrum and waterfall", style = MaterialTheme.typography.titleLarge)
        Text("Local microphone • 2.93 Hz resolution • up to 2 updates/s")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OffsetCursor.entries.forEach { cursor ->
                FilterChip(selected = view.cursor == cursor, onClick = { onView(view.copy(cursor = cursor)) }, label = { Text("Select ${cursor.name}") })
            }
        }
        Slider(value = view.selectedHz.toFloat(), onValueChange = { onView(view.select(it)) }, valueRange = 200f..2950f,
            modifier = Modifier.semantics { contentDescription = "${view.cursor.name} audio offset in Hz" })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onView(view.select(view.selectedHz - 25f)) }) { Text("−25 Hz") }
            OutlinedButton(onClick = { onView(view.select(view.selectedHz + 25f)) }) { Text("+25 Hz") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3000f to "1×", 1500f to "2×", 750f to "4×").forEach { (span, label) ->
                FilterChip(selected = view.spanHz == span, onClick = { onView(view.zoom(span)) }, label = { Text("Zoom $label") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onView(view.pan(-1)) }, enabled = view.startHz > 0) { Text("Lower band") }
            OutlinedButton(onClick = { onView(view.pan(1)) }, enabled = view.startHz + view.spanHz < 3000) { Text("Higher band") }
        }
        Text("Visible band: ${view.startHz.roundToInt()}–${(view.startHz + view.spanHz).roundToInt()} Hz")
        Text("RX ${view.rxHz} Hz • TX ${view.txHz} Hz • Tap selects ${view.cursor.name}")
        val selectedColor = Color(0xFF67E8C4)
        val txColor = Color(0xFFFF7E9C)
        val markerColor = Color(0xFFFFD166)
        fun Modifier.selection(description: String) = this.semantics { contentDescription = description }
            .pointerInput(view) { detectTapGestures { onView(view.select(view.at(it.x / size.width))) } }
        Canvas(Modifier.fillMaxWidth().height(196.dp).selection("Local audio spectrum and waterfall. Tap to select ${view.cursor.name} offset.")) {
            val spectrumHeight = size.height * .36f
            drawRect(Color(0xFF080F1D))
            listOf(-20, -40, -60, -80).forEach { db ->
                val y = -db / 100f * spectrumHeight
                drawLine(Color.DarkGray, Offset(0f, y), Offset(size.width, y))
            }
            state.frames.firstOrNull()?.let { frame ->
                val path = Path(); var began = false
                frame.dbfs.forEachIndexed { bin, db ->
                    val x = view.fraction(SpectrumFft.frequency(bin)) * size.width
                    if (x in 0f..size.width) {
                        val y = (-db / 100 * spectrumHeight).coerceIn(0f, spectrumHeight)
                        if (!began) { path.moveTo(x, y); began = true } else path.lineTo(x, y)
                    }
                }
                drawPath(path, selectedColor, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
            image?.takeIf { it.sessionId == state.sessionId && state.frames.isNotEmpty() }?.image?.let {
                val first = (view.startHz / SpectrumFft.frequency(1)).roundToInt().coerceIn(0, it.width - 1)
                val width = (view.spanHz / SpectrumFft.frequency(1)).roundToInt().coerceIn(1, it.width - first)
                drawImage(it, srcOffset = IntOffset(first, 0), srcSize = IntSize(width, it.height),
                    dstOffset = IntOffset(0, spectrumHeight.roundToInt()),
                    dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), (size.height - spectrumHeight).roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None)
            }
            listOf(view.rxHz to selectedColor, view.txHz to txColor).forEach { (hz, color) ->
                val x = view.fraction(hz.toFloat()) * size.width
                if (x in 0f..size.width) drawLine(color, Offset(x, 0f), Offset(x, size.height), 2f)
            }
            markers.forEach { marker ->
                val x = view.fraction(marker.frequencyHz) * size.width
                if (x in 0f..size.width) drawCircle(markerColor, 4.dp.toPx(), Offset(x, spectrumHeight + 6.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0f, .5f, 1f).forEach { Text("${view.at(it).roundToInt()} Hz") }
        }
        Text("Green: RX • Pink: TX • Yellow: decoded frequency")
        Text("Spectrum: 0 to −100 dBFS peak amplitude. Waterfall: −90 to −10 dBFS; newest row at top. Not an SNR meter.")
        Text("${state.frames.size}/${SpectrumMonitor.HISTORY} rows • ${state.replaced} display windows replaced")
        if (state.frames.isEmpty()) Text("Start microphone to display local audio. NTP/GPS is needed only for UTC/live FT8 mode.")
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (markers.isNotEmpty()) {
            Text("Yellow markers: latest local decoded slot ${Instant.ofEpochMilli(markers.first().utcMillis)}. Frequency indicators, not waterfall event times.")
            markers.forEach { marker ->
                TextButton(onClick = { onView(view.copy(cursor = OffsetCursor.RX).select(marker.frequencyHz)) }) {
                    Text("Decode ${marker.frequencyHz.roundToInt()} Hz → RX")
                }
            }
        }
        Text("RX/TX cursors are temporary selections. They do not filter decodes, tune a radio or transmit.")
    }
}
