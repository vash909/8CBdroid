package net.vash.ft8cbdroid.core.audio

import kotlin.math.cos
import kotlin.math.sin
import net.vash.ft8cbdroid.core.message.Ft8GenerationSettings

/** Two-second calibration tone, with 100 ms leading/trailing silence and 20 ms fades. Not an FT8 message. */
fun phoneTestTone(frequencyHz: Float, peakAmplitude: Float): FloatArray {
    Ft8GenerationSettings(frequencyHz, peakAmplitude)
    return FloatArray(26_400) { index ->
        val signal = index - 1200
        if (signal !in 0 until 24_000) 0f else {
            val edge = minOf(signal, 23_999 - signal)
            val envelope = if (edge < 240) .5 - .5 * cos(Math.PI * edge / 240) else 1.0
            (peakAmplitude * envelope * sin(2 * Math.PI * frequencyHz * signal / 12_000)).toFloat()
        }
    }
}
