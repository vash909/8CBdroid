package net.vash.ft8cbdroid.core.audio

import kotlinx.coroutines.flow.StateFlow

class CaptureException(message: String) : IllegalStateException(message)

data class AudioInput(val id: Int, val label: String)
data class CaptureRoute(
    val input: AudioInput,
    val clientRate: Int,
    val deviceFormat: String,
    val source: String,
    val processing: String,
    val bufferFrames: Int,
)
data class CaptureMetrics(
    val route: CaptureRoute,
    val inputSamples: Long,
    val outputSamples: Long,
    val peakDbfs: Double,
    val rmsDbfs: Double,
    val clippedSamples: Long,
    val retainedSamples: Int,
)

/** Capture owns hardware until this suspending call returns, including cancellation cleanup. */
interface MicrophoneCapture {
    val inputs: StateFlow<List<AudioInput>>
    suspend fun capture(inputId: Int, update: suspend (CaptureMetrics) -> Unit)
    fun snapshot(): FloatArray
    fun clear()
    fun close()
}
