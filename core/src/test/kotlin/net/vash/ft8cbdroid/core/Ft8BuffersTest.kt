package net.vash.ft8cbdroid.core

import org.junit.Assert.*
import org.junit.Test

class Ft8BuffersTest {
    @Test fun payloadPreservesLastBitAndOwnsItsStorage() {
        val bits = "1" + "0".repeat(75) + "1"
        val bytes = Ft8Payload77.fromBits(bits).toByteArray()
        val payload = Ft8Payload77.fromBytes(bytes)
        bytes.fill(0)
        payload.toByteArray().fill(0)
        assertEquals(bits, payload.toBitString())
        assertEquals(payload, Ft8Payload77.fromBits(bits))
    }

    @Test fun rejectsTruncatedPayloadsAndNonzeroPadding() {
        assertTrue(runCatching { Ft8Payload77.fromBytes(ByteArray(9)) }.isFailure)
        assertTrue(runCatching { Ft8Payload77.fromBytes(ByteArray(10).also { it[9] = 1 }) }.isFailure)
        assertTrue(runCatching { Ft8Payload77.fromBits("0".repeat(76)) }.isFailure)
        assertTrue(runCatching { Ft8Payload77.fromBits("0".repeat(76) + "X") }.isFailure)
    }

    @Test fun pcmOwnsItsStorageAndRejectsIncompleteOrInvalidSlots() {
        val samples = FloatArray(Ft8PcmSlot.sampleCount)
        samples[0] = -1f
        samples[samples.lastIndex] = 1f
        val slot = Ft8PcmSlot.fromSamples(samples)
        samples.fill(0f)
        slot.toFloatArray().fill(0f)
        assertEquals(-1f, slot.toFloatArray().first(), 0f)
        assertEquals(1f, slot.toFloatArray().last(), 0f)
        assertTrue(runCatching { Ft8PcmSlot.fromSamples(FloatArray(100)) }.isFailure)
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, 1.001f)) {
            samples[0] = value
            assertTrue(runCatching { Ft8PcmSlot.fromSamples(samples) }.isFailure)
        }
    }
}
