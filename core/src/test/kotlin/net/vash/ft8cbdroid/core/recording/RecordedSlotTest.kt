package net.vash.ft8cbdroid.core.recording

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlinx.coroutines.test.runTest
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.message.*
import org.junit.Assert.*
import org.junit.Test

class RecordedSlotTest {
    private fun chunk(tag: String, data: ByteArray): ByteArray = tag.toByteArray() +
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array() + data +
        if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun format(channels: Int = 1, rate: Int = 12000, type: Int = 1, bits: Int = 16) =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort(type.toShort()).putShort(channels.toShort())
            .putInt(rate).putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort()).array()
    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = "WAVE".toByteArray() + chunks.fold(byteArrayOf()) { a, b -> a + b }
        return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).array() + body
    }
    private fun data() = ByteArray(360000).apply {
        this[0] = 0; this[1] = 0x80.toByte(); this[2] = 0xff.toByte(); this[3] = 0x7f
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid input accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun readsSignedSamplesAndOddMetadataChunksWithoutAssumingA44ByteHeader() {
        val wav = riff(chunk("JUNK", byteArrayOf(1, 2, 3)), chunk("data", data()), chunk("fmt ", format()))
        val pcm = RecordedSlotWav.read(ByteArrayInputStream(wav)).toFloatArray()
        assertEquals(-1f, pcm[0]); assertEquals(32767 / 32768f, pcm[1]); assertEquals(0f, pcm.last())
    }
    @Test fun rejectsUnsupportedFormatsAndIncompleteSlotsInsteadOfConvertingOrPadding() {
        for (fmt in listOf(format(2), format(rate = 48000), format(type = 3), format(bits = 8), format() + byteArrayOf(1, 0))) {
            rejects { RecordedSlotWav.parse(riff(chunk("fmt ", fmt), chunk("data", data()))) }
        }
        for (size in listOf(0, 2, 359998, 360002)) {
            rejects { RecordedSlotWav.parse(riff(chunk("fmt ", format()), chunk("data", ByteArray(size)))) }
        }
    }
    @Test fun rejectsMissingDuplicateTruncatedAndHostileChunkLengths() {
        val valid = riff(chunk("fmt ", format()), chunk("data", data()))
        val badLength = valid.copyOf().apply { for (i in 40..43) this[i] = 0xff.toByte() }
        val invalid = listOf(valid.copyOf(valid.size - 1), valid + 0, valid.copyOf().apply { this[0] = 0 }, badLength,
            riff(chunk("fmt ", format())), riff(chunk("data", data())),
            riff(chunk("fmt ", format()), chunk("fmt ", format()), chunk("data", data())),
            riff(chunk("fmt ", format()), chunk("data", data()), chunk("data", data())),
            riff(chunk("fmt ", format()), chunk("data", data()), byteArrayOf(1, 2, 3)))
        invalid.forEach { bytes -> rejects { RecordedSlotWav.parse(bytes) } }
        rejects { RecordedSlotWav.read(ByteArrayInputStream(ByteArray(RecordedSlotWav.maxBytes + 1))) }
    }
    @Test fun requiresExplicitUtcSlotBoundary() {
        assertEquals(Instant.parse("2026-11-06T12:00:15Z"), RecordedSlotContext.parse("2026-11-06T12:00:15Z").slotStartUtc)
        listOf("", "2026-11-06T12:00:01Z", "2026-11-06T12:00:00.1Z", "2026-11-06T13:00:00+01:00", "badZ").forEach {
            rejects { RecordedSlotContext.parse(it) }
        }
    }
    @Test fun preservesTypedUnsupportedAndUnknownHashRowsWithoutInventingACall() = runTest {
        val encoded = CbMessageCodec.pack("<1AT106> 26AT101") as PackResult.Encoded
        val unknown = Ft8DecodedFrame(encoded.payload, .8f, 1000f, 30, -8f)
        val unsupported = Ft8DecodedFrame(Ft8Payload77.fromBits("1".repeat(77)), .72f, 1500f, 20)
        val engine = object : Ft8Engine {
            override suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile) = Ft8DecodeResult(listOf(unknown, unsupported), 12)
            override suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float): Ft8PcmSlot = error("No TX")
        }
        val decoder = RecordedSlotDecoder(engine)
        val context = RecordedSlotContext.parse("2026-11-06T12:00:00Z")
        val pcm = Ft8PcmSlot.fromSamples(FloatArray(180000))
        val cold = decoder.decode(pcm, context)
        assertEquals(12, cold.candidateCount)
        val row = cold.decodes.first()
        assertEquals(.3f, row.deltaTimeSeconds, .0001f)
        assertEquals(800, row.signalStartUtc.toEpochMilli() - context.slotStartUtc.toEpochMilli())
        assertTrue(RecordedDecodeFlag.UNKNOWN_HASH in row.flags)
        assertTrue(RecordedDecodeFlag.SNR_ESTIMATED in row.flags)
        assertEquals("<...> 26AT101", (row.message as UnpackResult.Decoded).message.text)
        assertTrue(RecordedDecodeFlag.UNSUPPORTED_MESSAGE in cold.decodes.last().flags)
        assertTrue(RecordedDecodeFlag.SNR_UNAVAILABLE in cold.decodes.last().flags)
        val local = decoder.decode(pcm, context, "1AT106")
        assertEquals("<1AT106> 26AT101", (local.decodes.first().message as UnpackResult.Decoded).message.text)
        assertEquals("<...> 26AT101", (decoder.decode(pcm, context).decodes.first().message as UnpackResult.Decoded).message.text)
    }
}
