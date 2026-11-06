package net.vash.ft8cbdroid.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Streaming, causal 129-tap Hamming-windowed sinc at 4.5 kHz, followed by fractional interpolation.
 * Phase/history survive read boundaries. Delay is 64 input samples; there is no tail flush.
 */
class CaptureResampler(val inputRate: Int) {
    init { require(inputRate == 48000 || inputRate == 44100) }
    private val taps = DoubleArray(129) { i ->
        val x = i - 64
        val cutoff = 4500.0 / inputRate
        (if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)) *
            (.54 - .46 * cos(2 * PI * i / 128))
    }.also { values -> val sum = values.sum(); values.indices.forEach { values[it] /= sum } }
    private val history = FloatArray(130)
    private var cursor = 0
    private var consumed = 0L
    private var produced = 0L
    private fun filtered(offset: Int): Double = taps.indices.sumOf { i ->
        taps[i] * history[(cursor - 1 - i - offset + history.size * 2) % history.size]
    }
    fun process(input: ShortArray, count: Int = input.size): FloatArray {
        require(count in 0..input.size && count <= inputRate) { "Capture chunks must be no longer than one second." }
        val result = FloatArray(ceil(count * 12000.0 / inputRate).toInt() + 1)
        var size = 0
        repeat(count) { i ->
            history[cursor] = input[i] / 32768f
            cursor = (cursor + 1) % history.size
            val numerator = produced * inputRate
            val ceilingIndex = (numerator + 11999) / 12000
            if (consumed == ceilingIndex) {
                val fraction = (numerator % 12000) / 12000.0
                val value = if (fraction == 0.0) filtered(0) else filtered(1) * (1 - fraction) + filtered(0) * fraction
                result[size++] = value.toFloat()
                produced++
            }
            consumed++
        }
        return result.copyOf(size)
    }
}

/** Five seconds of recent DSP PCM, overwritten deliberately rather than queued without limit. */
class CaptureBuffer(private val capacity: Int = 60_000) {
    init { require(capacity in 1..180_000) }
    private val samples = FloatArray(capacity)
    private var cursor = 0
    private var size = 0
    @Synchronized fun append(values: FloatArray) {
        values.forEach { samples[cursor] = it; cursor = (cursor + 1) % capacity; size = minOf(size + 1, capacity) }
    }
    @Synchronized fun snapshot(): FloatArray = FloatArray(size) { samples[(cursor - size + it + capacity) % capacity] }
    @Synchronized fun clear() { samples.fill(0f); cursor = 0; size = 0 }
}

data class InputLevel(val peakDbfs: Double, val rmsDbfs: Double, val clipped: Int) {
    companion object {
        fun measure(input: ShortArray, count: Int): InputLevel {
            require(count in 1..input.size)
            var peak = 0.0; var energy = 0.0; var clipped = 0
            repeat(count) { i ->
                val value = input[i] / 32768.0
                peak = maxOf(peak, abs(value)); energy += value * value
                if (input[i] == Short.MIN_VALUE || input[i] == Short.MAX_VALUE) clipped++
            }
            fun db(value: Double) = if (value > 0) maxOf(-120.0, 20 * log10(value)) else -120.0
            return InputLevel(db(peak), db(sqrt(energy / count)), clipped)
        }
    }
}

/** Diagnostic capture export, deliberately not a UTC-aligned FT8 slot. */
object CaptureWav {
    fun encode(samples: FloatArray): ByteArray {
        require(samples.size in 1..60000)
        val data = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(12000).putInt(24000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { require(it.isFinite()); data.putShort((it * 32768).roundToInt().coerceIn(-32768, 32767).toShort()) }
        return data.array()
    }
}
