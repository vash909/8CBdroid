package net.vash.ft8cbdroid.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import net.vash.ft8cbdroid.core.audio.*
import org.junit.Assert.*
import org.junit.Test

class CapturePcmTest {
    private fun tone(rate: Int, frequency: Double) = ShortArray(rate) { (8192 * sin(2 * PI * frequency * it / rate)).roundToInt().toShort() }
    private fun rms(values: FloatArray) = sqrt(values.drop(1000).sumOf { it.toDouble() * it } / (values.size - 1000))
    @Test fun chunkBoundariesDoNotChangePhaseOrSamplesAtEitherRate() {
        for (rate in listOf(48000, 44100)) {
            val source = tone(rate, 1234.0)
            val expected = CaptureResampler(rate).process(source)
            val engine = CaptureResampler(rate); val actual = mutableListOf<Float>()
            var position = 0
            val chunks = listOf(1, 7, 882, 13, 960, 3)
            var i = 0
            while (position < source.size) {
                val end = minOf(source.size, position + chunks[i++ % chunks.size])
                actual += engine.process(source.copyOfRange(position, end)).toList(); position = end
            }
            assertArrayEquals(expected, actual.toFloatArray(), 0f)
            assertEquals(12000, actual.size)
        }
    }
    @Test fun ft8PassbandSurvivesAndHighFrequenciesDoNotAliasIntoIt() {
        for (rate in listOf(48000, 44100)) {
            for (frequency in listOf(200.0, 1000.0, 2900.0)) {
                val output = CaptureResampler(rate).process(tone(rate, frequency))
                assertEquals(.25 / sqrt(2.0), rms(output), .006)
                val crossings = (1001 until output.size).count { output[it - 1] <= 0 && output[it] > 0 }
                assertEquals(frequency, crossings * 12000.0 / (output.size - 1000), 2.0)
            }
            val alias = rms(CaptureResampler(rate).process(tone(rate, 10000.0)))
            assertTrue("10 kHz rejection: $rate/$alias", alias < .0003)
        }
    }
    @Test fun outputCountDoesNotDriftAcrossThirtySecondsAndSilenceStaysSilent() {
        for (rate in listOf(48000, 44100)) {
            val resampler = CaptureResampler(rate)
            var count = 0
            repeat(30) { val out = resampler.process(ShortArray(rate)); count += out.size; assertTrue(out.all { it == 0f }) }
            assertEquals(360000, count)
        }
    }
    @Test fun levelsDistinguishSilenceSignalAndBothClippingRails() {
        assertEquals(-120.0, InputLevel.measure(ShortArray(10), 10).rmsDbfs, 0.0)
        val signal = InputLevel.measure(tone(48000, 1000.0), 48000)
        assertEquals(-12.041, signal.peakDbfs, .001)
        assertEquals(-15.051, signal.rmsDbfs, .002)
        assertEquals(0, signal.clipped)
        val rails = InputLevel.measure(shortArrayOf(-32768, 32767, 0), 3)
        assertEquals(2, rails.clipped); assertEquals(0.0, rails.peakDbfs, 0.0)
    }
    @Test fun recentBufferWrapsInChronologicalOrderAndClearRemovesOldAudio() {
        val buffer = CaptureBuffer(4)
        buffer.append(floatArrayOf(1f, 2f, 3f)); buffer.append(floatArrayOf(4f, 5f, 6f))
        val snapshot = buffer.snapshot()
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), snapshot, 0f)
        snapshot.fill(0f)
        assertEquals(3f, buffer.snapshot()[0], 0f)
        buffer.clear(); assertTrue(buffer.snapshot().isEmpty())
        buffer.append(floatArrayOf(8f)); assertArrayEquals(floatArrayOf(8f), buffer.snapshot(), 0f)
    }
    @Test fun diagnosticWavHasCorrectFormatDurationAndSaturation() {
        val bytes = CaptureWav.encode(floatArrayOf(-2f, -.5f, 0f, .5f, 2f))
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(54, bytes.size); assertEquals(46, data.getInt(4))
        assertEquals(12000, data.getInt(24)); assertEquals(1, data.getShort(22).toInt())
        assertEquals(10, data.getInt(40))
        assertEquals(-32768, data.getShort(44).toInt()); assertEquals(32767, data.getShort(52).toInt())
    }
}
