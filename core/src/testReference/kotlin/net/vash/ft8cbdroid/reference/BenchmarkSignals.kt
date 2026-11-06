package net.vash.ft8cbdroid.reference

import kotlin.math.*
import net.vash.ft8cbdroid.core.Ft8PcmSlot

/** Test-only reproducible stimuli, never included in the main app. */
object BenchmarkSignals {
    private class Noise(seed: Int) {
        private var state = seed
        fun sample(): Double {
            var sum = 0.0
            repeat(12) {
                state = state xor (state shl 13)
                state = state xor (state ushr 17)
                state = state xor (state shl 5)
                sum += (state ushr 8).toDouble() / 16_777_216
            }
            return sum - 6 // Unit variance, approximately Gaussian (12 uniforms), not exact AWGN.
        }
    }
    private fun quantize(value: Double): Float {
        require(abs(value) < 1) { "Benchmark input clipped" }
        return floor(value * 32768 + .5).toInt() / 32768f
    }
    fun signal(clean: Ft8PcmSlot?, snrDb: Int?, seed: Int, toneHz: Int = 0): Ft8PcmSlot {
        val source = clean?.toFloatArray()
        val gain = .02
        val power = source?.sliceArray(9600 until 161280)?.sumOf { (it * gain).pow(2) }?.div(151680)
        val sigma = if (snrDb != null) sqrt(requireNotNull(power) * 6000 / (2500 * 10.0.pow(snrDb / 10.0))) else .06
        val noise = Noise(seed)
        return Ft8PcmSlot.fromSamples(FloatArray(Ft8PcmSlot.sampleCount) { i ->
            val value = (source?.get(i)?.times(gain) ?: 0.0) + noise.sample() * sigma +
                if (toneHz == 0) 0.0 else .05 * sin(2 * PI * toneHz * i / 12000)
            quantize(value)
        })
    }
    fun pcm16(slot: Ft8PcmSlot): ByteArray {
        val samples = slot.toFloatArray()
        return ByteArray(samples.size * 2).also { bytes ->
            samples.forEachIndexed { i, value ->
                val n = (value * 32768).toInt()
                bytes[2*i] = n.toByte(); bytes[2*i+1] = (n shr 8).toByte()
            }
        }
    }
}
