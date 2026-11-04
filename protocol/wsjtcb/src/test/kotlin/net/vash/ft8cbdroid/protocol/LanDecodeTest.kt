package net.vash.ft8cbdroid.protocol

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LanDecodeTest {
    private fun decode(bytes: ByteArray = DesktopFixtures.decode) =
        (WsjtCbProtocol.readMessage(bytes) as WsjtCbProtocol.Message.Decode).decode

    @Test fun readsQtTimeSignedSnrAndDoublePrecisionDeltaTime() {
        val decoded = decode()
        assertTrue(decoded.isNew)
        assertEquals(49_635_250L, decoded.utcMillisOfDay)
        assertEquals(-17, decoded.snr)
        assertEquals(0.25, decoded.deltaTimeSeconds, 0.0)
        assertEquals(1000L, decoded.deltaFrequencyHz)
        assertEquals("~", decoded.mode)
        assertEquals("CQ 1AT106 JN45", decoded.message)
        assertTrue(decoded.lowConfidence)
        assertFalse(decoded.offAir)
    }

    @Test fun preservesReplayOffAirAndOptionalLegacyFlags() {
        val replay = decode(DesktopFixtures.decode.copyOf().also { it[23] = 0; it[68] = 1 })
        assertFalse(replay.isNew)
        assertTrue(replay.offAir)
        val legacy = decode(DesktopFixtures.decode.copyOf(67))
        assertFalse(legacy.lowConfidence)
        assertFalse(legacy.offAir)
        assertEquals(decode(), decode(DesktopFixtures.decode + byteArrayOf(9, 8, 7)))
    }

    @Test fun distinguishesInvalidQtTimeFromMidnightAndSupportsUnsignedOffset() {
        val bytes = DesktopFixtures.decode.copyOf()
        ByteBuffer.wrap(bytes).putInt(24, -1).putInt(40, -1)
        assertNull(decode(bytes).utcMillisOfDay)
        assertEquals(0xffffffffL, decode(bytes).deltaFrequencyHz)
        ByteBuffer.wrap(bytes).putInt(24, 0)
        assertEquals(0L, decode(bytes).utcMillisOfDay)
        ByteBuffer.wrap(bytes).putInt(24, 86_399_999)
        assertEquals(86_399_999L, decode(bytes).utcMillisOfDay)
    }

    @Test fun acceptsBothSupportedSchemas() {
        assertEquals(decode(), decode(DesktopFixtures.decode.copyOf().also { it[7] = 2 }))
    }

    @Test fun rejectsTruncatedRequiredFieldsInvalidTimeAndNonFiniteDeltaTime() {
        for (size in 23 until 67) {
            assertThrows("Truncation at $size", IllegalArgumentException::class.java) {
                decode(DesktopFixtures.decode.copyOf(size))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            decode(DesktopFixtures.decode.copyOf().also { ByteBuffer.wrap(it).putInt(24, 86_400_000) })
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                decode(DesktopFixtures.decode.copyOf().also { ByteBuffer.wrap(it).putDouble(32, value) })
            }
        }
        for (offset in listOf(23, 67, 68)) {
            assertThrows(IllegalArgumentException::class.java) {
                decode(DesktopFixtures.decode.copyOf().also { it[offset] = 2 })
            }
        }
    }

    @Test fun readsDesktopClearAndEncodesReplayWithoutPayload() {
        assertTrue(WsjtCbProtocol.readMessage(DesktopFixtures.clear) is WsjtCbProtocol.Message.Clear)
        val expected = "adbccbda00000003000000070000000757534a542d4342"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, WsjtCbProtocol.replay("WSJT-CB", 3))
        assertEquals(2, WsjtCbProtocol.readHeader(WsjtCbProtocol.replay("WSJT-CB", 2)).schema)
    }

    @Test fun deduplicatesLiveAndReplayedCopiesWithoutLosingLiveMarker() {
        val live = decode()
        val replay = live.copy(isNew = false)
        assertEquals(listOf(live), DecodeHistory.append(listOf(live), replay))
        assertEquals(listOf(live), DecodeHistory.append(listOf(replay), live))
        assertEquals(listOf(live), DecodeHistory.append(listOf(live), live))
    }

    @Test fun retainsOnlyTheLatestTwoHundredDistinctMessages() {
        var history = emptyList<LanDecode>()
        repeat(250) { index -> history = DecodeHistory.append(history, decode().copy(message = "CQ TEST $index")) }
        assertEquals(200, history.size)
        assertEquals("CQ TEST 50", history.first().message)
        assertEquals("CQ TEST 249", history.last().message)
    }
}
