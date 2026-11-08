package net.vash.ft8cbdroid.core.audio

import kotlinx.coroutines.flow.StateFlow
import net.vash.ft8cbdroid.core.Ft8PcmSlot
import kotlin.math.cos

/** Process-local Android IDs are selected explicitly and are never persisted. */
data class PreviewOutput(val id: Int, val label: String)
data class PreviewSettings(val gainPercent: Int = 100, val rampMillis: Int = 20) {
    init {
        require(gainPercent in 1..100) { "Preview gain must be between 1 and 100 percent." }
        require(rampMillis in 5..100) { "Preview ramp must be between 5 and 100 ms." }
    }
}
data class PreviewReport(val output: String, val clientRateHz: Int, val writtenFrames: Int, val playedFrames: Long)
class PreviewException(message: String) : IllegalStateException(message)
interface AudioPreview : AutoCloseable {
    val outputs: StateFlow<List<PreviewOutput>>
    /** Owns output/focus until return, including cancellation cleanup; must never resume itself. */
    suspend fun play(slot: Ft8PcmSlot, outputId: Int, settings: PreviewSettings, update: (PreviewReport) -> Unit)
}

/** Keep the 15-second slot and its silence intact; additional ramps apply to signal edges. */
fun previewSamples(slot: Ft8PcmSlot, settings: PreviewSettings): FloatArray {
    val pcm = slot.toFloatArray()
    val first = pcm.indexOfFirst { it != 0f }
    if (first < 0) return pcm
    val last = pcm.indexOfLast { it != 0f }
    val ramp = minOf(settings.rampMillis * 12, (last - first + 1) / 2).coerceAtLeast(1)
    val gain = settings.gainPercent / 100f
    for (i in pcm.indices) {
        val edge = minOf(i - first, last - i)
        val envelope = if (edge in 0 until ramp) (.5 - .5 * cos(Math.PI * edge / ramp)).toFloat() else 1f
        pcm[i] *= gain * envelope
    }
    return pcm
}
