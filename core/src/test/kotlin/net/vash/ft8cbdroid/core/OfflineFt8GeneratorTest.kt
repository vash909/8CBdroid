package net.vash.ft8cbdroid.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.test.runTest
import net.vash.ft8cbdroid.core.message.*
import org.junit.Assert.*
import org.junit.Test

class OfflineFt8GeneratorTest {
    private class Engine : Ft8Engine {
        val calls = mutableListOf<Triple<Ft8Payload77, Float, Float>>()
        override suspend fun decode(slot: Ft8PcmSlot) = error("Generation must not call its own decoder")
        override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot {
            calls += Triple(payload, audioFrequencyHz, peakAmplitude)
            return Ft8PcmSlot.fromSamples(FloatArray(Ft8PcmSlot.sampleCount))
        }
    }

    @Test fun messageGenerationPassesIndependentDesktopPayloadsAndExplicitSettings() = runTest {
        val engine = Engine()
        val generator = OfflineFt8Generator(engine)
        val rows = requireNotNull(javaClass.getResourceAsStream("/wsjtcb/messages.tsv"))
            .bufferedReader().readLines().filter { !it.startsWith('#') && it.isNotEmpty() }.map { it.split('\t') }
        val ids = setOf("cq-1AT106", "to-26AT101", "from-26AT101", "free-text", "hash22-report", "standard-portable")
        val selected = rows.filter { it[0] in ids }
        assertEquals(ids, selected.map { it[0] }.toSet())
        for (row in selected) {
            val input = row[1].chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            val result = generator.generate(input, EncodingMode.AUTOMATIC, Ft8GenerationSettings(1234.5f, .25f), true)
            assertTrue(row[0], result is WaveformGenerationResult.Generated)
            assertEquals(row[2], engine.calls.last().first.toBitString())
            assertEquals(1234.5f, engine.calls.last().second)
            assertEquals(.25f, engine.calls.last().third)
        }
    }

    @Test fun lossesRequireExplicitConsentAndNeverSilentlyGenerate() = runTest {
        val engine = Engine()
        val generator = OfflineFt8Generator(engine)
        val request = "<1AT106> 26AT101 R-10"
        val refused = generator.generate(request, EncodingMode.AUTOMATIC, Ft8GenerationSettings())
        assertEquals(WaveformGenerationResult.ConfirmationRequired("<1AT106> 26AT101"), refused)
        assertTrue(engine.calls.isEmpty())
        val accepted = generator.generate(request, EncodingMode.AUTOMATIC, Ft8GenerationSettings(), true)
            as WaveformGenerationResult.Generated
        assertEquals("<1AT106> 26AT101", accepted.waveform.message.transmittedText)
        assertEquals(1, engine.calls.size)
    }

    @Test fun unsupportedInputCannotReachDspAndExplicitTextModeIsPreserved() = runTest {
        val engine = Engine()
        val generator = OfflineFt8Generator(engine)
        for (input in listOf("<...> 26AT101", "ABC123", "CQ 53HDFKJEASD"))
            assertTrue(generator.generate(input, EncodingMode.AUTOMATIC, Ft8GenerationSettings(), true)
                is WaveformGenerationResult.Unsupported)
        assertTrue(engine.calls.isEmpty())
        assertTrue(generator.generate("ABC123", EncodingMode.FREE_TEXT, Ft8GenerationSettings())
            is WaveformGenerationResult.Generated)
    }

    @Test fun settingsRejectNonfiniteAndOutOfBandValues() {
        for (frequency in listOf(Float.NaN, Float.POSITIVE_INFINITY, 199.9f, 2900.1f))
            assertTrue(runCatching { Ft8GenerationSettings(frequency, .4f) }.isFailure)
        for (level in listOf(Float.NaN, Float.NEGATIVE_INFINITY, 0f, .009f, 1.001f))
            assertTrue(runCatching { Ft8GenerationSettings(1000f, level) }.isFailure)
        assertEquals(12000, Ft8GenerationSettings(200f, .01f).sampleRateHz)
        Ft8GenerationSettings(2900f, 1f)
    }

    @Test fun wavHasExactPcm16HeaderAndSignedLittleEndianSamples() {
        val message = CbMessageCodec.pack("CQ 1AT106") as PackResult.Encoded
        val pcm = FloatArray(Ft8PcmSlot.sampleCount).apply { this[0] = -1f; this[1] = 1f; this[2] = -.5f; this[3] = .5f }
        val bytes = GeneratedFt8Waveform(message, Ft8GenerationSettings(), Ft8PcmSlot.fromSamples(pcm)).wavBytes()
        assertEquals(360044, bytes.size)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVEfmt ", bytes.copyOfRange(8, 16).toString(Charsets.US_ASCII))
        assertEquals("data", bytes.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(360036, buffer.getInt(4)); assertEquals(16, buffer.getInt(16))
        assertEquals(1, buffer.getShort(20).toInt()); assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(12000, buffer.getInt(24)); assertEquals(24000, buffer.getInt(28))
        assertEquals(2, buffer.getShort(32).toInt()); assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(360000, buffer.getInt(40))
        assertEquals(-32767, buffer.getShort(44).toInt()); assertEquals(32767, buffer.getShort(46).toInt())
        assertEquals(-16383, buffer.getShort(48).toInt()); assertEquals(16384, buffer.getShort(50).toInt())
    }
}
