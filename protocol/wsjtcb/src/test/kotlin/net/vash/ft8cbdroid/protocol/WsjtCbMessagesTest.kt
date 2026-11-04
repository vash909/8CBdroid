package net.vash.ft8cbdroid.protocol

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class WsjtCbMessagesTest {
    @Test fun readsDesktopHeartbeatAndLegacySchemaFallback() {
        val heartbeat = WsjtCbProtocol.readMessage(DesktopFixtures.heartbeat) as WsjtCbProtocol.Message.Heartbeat
        assertEquals("WSJT-CB", heartbeat.header.instanceId)
        assertEquals(3, heartbeat.maximumSchema)
        assertEquals("1.4.1", heartbeat.version)
        assertEquals("94f776d", heartbeat.revision)
        val legacy = WsjtCbProtocol.readMessage(DesktopFixtures.legacyHeartbeat) as WsjtCbProtocol.Message.Heartbeat
        assertEquals(2, legacy.maximumSchema)
        assertEquals("", legacy.version)
    }

    @Test fun capsAdvertisedFutureMaximumAtSupportedSchema() {
        val bytes = DesktopFixtures.heartbeat.copyOf()
        ByteBuffer.wrap(bytes).putInt(23, 99)
        assertEquals(3, (WsjtCbProtocol.readMessage(bytes) as WsjtCbProtocol.Message.Heartbeat).maximumSchema)
    }

    @Test fun readsStatusPrefixAndIgnoresLaterExtensions() {
        val status = (WsjtCbProtocol.readMessage(DesktopFixtures.status + byteArrayOf(1, 2, 3)) as WsjtCbProtocol.Message.Status).status
        assertEquals(27_265_000L, status.dialFrequencyHz)
        assertEquals("FT8", status.mode)
        assertEquals("26AT101", status.dxCall)
        assertEquals("-12", status.report)
        assertTrue(status.txEnabled)
        assertFalse(status.transmitting)
        assertTrue(status.decoding)
    }

    @Test fun rejectsTruncatedStatusFieldsAndInvalidBoolean() {
        for (size in 23 until DesktopFixtures.status.size) {
            assertThrows("Truncation at $size", IllegalArgumentException::class.java) {
                WsjtCbProtocol.readMessage(DesktopFixtures.status.copyOf(size))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WsjtCbProtocol.readMessage(DesktopFixtures.status.copyOf().also { it[it.lastIndex] = 2 })
        }
    }

    @Test fun rejectsPartialHeartbeatFieldsAndInvalidSchemaMaximum() {
        for (size in listOf(24, 25, 26, 28, 31, 34, 40)) {
            assertThrows(IllegalArgumentException::class.java) {
                WsjtCbProtocol.readMessage(DesktopFixtures.heartbeat.copyOf(size))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WsjtCbProtocol.readMessage(DesktopFixtures.heartbeat.copyOf().also { ByteBuffer.wrap(it).putInt(23, 1) })
        }
    }

    @Test fun validatesOptionalLiteralIpv4Filter() {
        listOf("", "127.0.0.1", "192.168.1.10").forEach { assertTrue(LanSettings.isDesktopAddressValid(it)) }
        listOf("localhost", "::1", "192.168.1", "1.2.3.256", "-1.2.3.4", "1..2.3").forEach {
            assertFalse(it, LanSettings.isDesktopAddressValid(it))
        }
    }
}
