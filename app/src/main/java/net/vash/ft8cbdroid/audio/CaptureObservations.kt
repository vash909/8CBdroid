package net.vash.ft8cbdroid.audio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.*
import net.vash.ft8cbdroid.core.audio.*
import java.util.Locale

@Composable
fun CaptureObservationHost(source: StateFlow<CaptureUiState>) {
    val flow = remember(source) { source.map { it.metrics to it.slots }.distinctUntilChanged() }
    val current by flow.collectAsStateWithLifecycle(initialValue = null to null)
    CaptureObservations(current.first, current.second)
}

@Composable
fun SpectrumHost(source: StateFlow<CaptureUiState>, onView: (SpectrumView) -> Unit) {
    val flow = remember(source) { source.map { Triple(it.spectrum, it.spectrumView, localSpectrumMarkers(it.live.rows)) }.distinctUntilChanged() }
    val current by flow.collectAsStateWithLifecycle(initialValue = Triple(SpectrumState(), SpectrumView(), emptyList()))
    SpectrumPanel(current.first, current.second, current.third, onView)
}

@Composable
fun CaptureObservations(metrics: CaptureMetrics?, slots: UtcSlotProgress?) {
    var details by rememberSaveable { mutableStateOf(false) }
    metrics?.let {
        Text("Actual input: ${it.route.input.label}")
        Text(String.format(Locale.ROOT, "Input peak: %.1f dBFS • RMS: %.1f dBFS", it.peakDbfs, it.rmsDbfs))
        LinearProgressIndicator(progress = { ((it.peakDbfs + 60) / 60).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("Clipped input samples: ${it.clippedSamples}")
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide audio diagnostics" else "Show audio diagnostics") }
        if (details) {
            Text("Capture: ${it.route.clientRate} Hz mono PCM16 → 12,000 Hz DSP PCM")
            Text("Device format: ${it.route.deviceFormat}")
            Text("Source: ${it.route.source}")
            Text(it.route.processing)
            Text("Samples: ${it.inputSamples} input / ${it.outputSamples} at 12 kHz")
            Text("Recent buffer: ${it.retainedSamples} / 60,000 samples • recorder buffer: ${it.route.bufferFrames} frames")
        }
    }
    slots?.let {
        Text("UTC slots: ${it.completed} complete • ${it.collected}/180,000 samples • ${it.discarded} incomplete windows discarded")
        it.driftPpm?.let { drift -> Text(String.format(Locale.ROOT, "Measured audio clock drift: %+.1f ppm", drift)) }
    }
}
