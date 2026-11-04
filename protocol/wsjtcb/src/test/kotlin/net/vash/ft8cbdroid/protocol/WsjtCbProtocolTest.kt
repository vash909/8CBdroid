package net.vash.ft8cbdroid.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class WsjtCbProtocolTest {
    // Independent big-endian Qt QByteArray header fixture: ID "CB", no payload.
    private val header = byteArrayOf(
        0xad.toByte(), 0xbc.toByte(), 0xcb.toByte(), 0xda.toByte(),
        0, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 2, 0x43, 0x42,
    )

    @Test fun readsQtHeaderFixture() {
        val decoded = WsjtCbProtocol.readHeader(header)
        assertEquals(2, decoded.schema)
        assertEquals(WsjtCbProtocol.Type.STATUS, decoded.type)
        assertEquals("CB", decoded.instanceId)
        assertEquals(18, decoded.payloadOffset)
    }

    @Test fun encodesHeartbeatUsingQtByteArrayAndAdvertisesSchemaThree() {
        val bytes = WsjtCbProtocol.heartbeat("CB")
        assertArrayEquals(header.copyOf().also { it[11] = 0 }, bytes.copyOfRange(0, 18))
        assertEquals(3, ByteBuffer.wrap(bytes, 18, 4).int)
    }

    @Test fun preservesUnknownTypesAndTrailingFields() {
        val bytes = header.copyOf(header.size + 4)
        bytes[11] = 99
        assertNull(WsjtCbProtocol.readHeader(bytes).type)
        assertEquals(99, WsjtCbProtocol.readHeader(bytes).typeCode)
    }

    @Test fun distinguishesNullAndEmptyQtByteArrays() {
        val bytes = header.copyOf(16)
        assertEquals("", WsjtCbProtocol.readHeader(bytes.also { ByteBuffer.wrap(it).putInt(12, 0) }).instanceId)
        assertNull(WsjtCbProtocol.readHeader(bytes.also { ByteBuffer.wrap(it).putInt(12, -1) }).instanceId)
    }

    @Test fun rejectsTruncationBadMagicInvalidLengthsAndUnsupportedSchemas() {
        val malformed = listOf(
            header.copyOf(15), header.copyOf(17),
            header.copyOf().also { it[0] = 0 },
            header.copyOf().also { it[7] = 1 },
            header.copyOf().also { it[7] = 4 },
            header.copyOf().also { ByteBuffer.wrap(it).putInt(12, Int.MAX_VALUE) },
            header.copyOf().also { ByteBuffer.wrap(it).putInt(12, -2) },
        )
        malformed.forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { WsjtCbProtocol.readHeader(bytes) }
        }
    }
}
