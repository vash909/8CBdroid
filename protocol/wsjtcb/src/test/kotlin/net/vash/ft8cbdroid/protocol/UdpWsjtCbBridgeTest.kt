package net.vash.ft8cbdroid.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.vash.ft8cbdroid.core.BackendResult
import org.junit.Assert.*
import org.junit.Test

class UdpWsjtCbBridgeTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Test fun repliesToEphemeralSourcePortAndReceivesStatus() = runBlocking<Unit> {
        withListener { bridge, port ->
            desktop().use { sender ->
                sender.send(port, DesktopFixtures.status) // Status alone cannot establish a peer.
                sender.send(port, DesktopFixtures.heartbeat)
                val reply = sender.reply()
                assertEquals(port, reply.port)
                val header = WsjtCbProtocol.readHeader(reply.data.copyOf(reply.length))
                assertEquals(3, header.schema)
                assertEquals("WSJT-CB", header.instanceId)
                assertEquals(sender.localPort, awaitState(bridge) { it.peer != null }.peer!!.port)
                assertNull(bridge.state.value.status)
                sender.send(port, DesktopFixtures.status)
                val status = awaitState(bridge) { it.status != null }.status!!
                assertEquals(27_265_000L, status.dialFrequencyHz)
                assertEquals("26AT101", status.dxCall)
            }
        }
    }

    @Test fun malformedAndUnknownPacketsDoNotKillListenerOrClaimPeer() = runBlocking<Unit> {
        withListener { bridge, port ->
            desktop().use { sender ->
                sender.send(port, byteArrayOf(1, 2, 3))
                sender.send(port, DesktopFixtures.heartbeat.copyOf(24))
                sender.send(port, DesktopFixtures.legacyHeartbeat.copyOf().also { it[11] = 99 })
                sender.send(port, DesktopFixtures.legacyHeartbeat)
                assertEquals(2, WsjtCbProtocol.readHeader(sender.reply().let { it.data.copyOf(it.length) }).schema)
                assertEquals(LanPhase.CONNECTED, awaitState(bridge) { it.peer != null }.phase)
            }
        }
    }

    @Test fun pinsDesktopEndpointUntilCloseThenAcceptsANewDesktop() = runBlocking<Unit> {
        withListener { bridge, port ->
            desktop().use { first -> desktop().use { other ->
                first.send(port, DesktopFixtures.heartbeat)
                first.reply()
                other.send(port, DesktopFixtures.heartbeat)
                other.soTimeout = 150
                assertThrows(SocketTimeoutException::class.java) { other.reply() }
                assertEquals(first.localPort, bridge.state.value.peer!!.port)
                first.send(port, DesktopFixtures.close)
                awaitState(bridge) { it.phase == LanPhase.LISTENING }
                other.soTimeout = 2_000
                other.send(port, DesktopFixtures.heartbeat)
                other.reply()
                assertEquals(other.localPort, awaitState(bridge) { it.peer?.port == other.localPort }.peer!!.port)
            } }
        }
    }

    @Test fun ignoresDifferentInstanceIdOnTheSameEndpoint() = runBlocking<Unit> {
        withListener { bridge, port ->
            desktop().use { sender ->
                sender.send(port, DesktopFixtures.heartbeat)
                sender.reply()
                sender.send(port, DesktopFixtures.close.copyOf().also { it[16] = 'X'.code.toByte() })
                sender.send(port, DesktopFixtures.status)
                assertEquals("WSJT-CB", awaitState(bridge) { it.status != null }.peer!!.instanceId)
            }
        }
    }

    @Test fun filtersUnexpectedSourceAddress() = runBlocking<Unit> {
        withListener("127.0.0.2") { bridge, port ->
            desktop().use { sender ->
                sender.soTimeout = 150
                sender.send(port, DesktopFixtures.heartbeat)
                assertThrows(SocketTimeoutException::class.java) { sender.reply() }
                assertEquals(LanPhase.LISTENING, bridge.state.value.phase)
            }
        }
    }

    @Test fun periodicallyRepliesAndExpiresSilentPeerWithMonotonicClock() = runBlocking<Unit> {
        val clock = AtomicLong(0)
        val bridge = UdpWsjtCbBridge(1_000, 3_000, 10, clock::get)
        val port = unusedPort()
        bridge.use {
            assertEquals(BackendResult.Success, bridge.listen(LanSettings(port)))
            desktop().use { sender ->
                sender.send(port, DesktopFixtures.heartbeat)
                sender.reply()
                sender.send(port, DesktopFixtures.status)
                awaitState(bridge) { it.status != null }
                clock.set(1_500)
                assertEquals(WsjtCbProtocol.Type.HEARTBEAT, sender.reply().let { WsjtCbProtocol.readHeader(it.data.copyOf(it.length)).type })
                clock.set(4_000)
                val expired = awaitState(bridge) { it.phase == LanPhase.LISTENING }
                assertNull(expired.peer)
                assertNull(expired.status)
            }
        }
    }

    @Test fun reportsBusyPortAndCanRetryAfterItIsReleased() = runBlocking<Unit> {
        UdpWsjtCbBridge().use { bridge ->
            val port = desktop().use { occupied ->
                assertTrue(bridge.listen(LanSettings(occupied.localPort)) is BackendResult.Unavailable)
                assertEquals(LanPhase.ERROR, bridge.state.value.phase)
                occupied.localPort
            }
            assertEquals(BackendResult.Success, bridge.listen(LanSettings(port)))
        }
    }

    @Test fun stopReleasesSocketAndAllowsRestartOnSamePort() = runBlocking<Unit> {
        val port = unusedPort()
        UdpWsjtCbBridge().use { bridge ->
            assertEquals(BackendResult.Success, bridge.listen(LanSettings(port)))
            assertTrue(bridge.listen(LanSettings(port)) is BackendResult.Unavailable)
            bridge.stop()
            assertEquals(LanPhase.STOPPED, bridge.state.value.phase)
            assertEquals(BackendResult.Success, bridge.listen(LanSettings(port)))
            desktop().use { sender ->
                sender.send(port, DesktopFixtures.heartbeat)
                sender.reply()
                awaitState(bridge) { it.peer != null }
            }
        }
    }

    private fun desktop() = DatagramSocket(0, loopback).apply { soTimeout = 2_000 }
    private fun unusedPort() = desktop().use { it.localPort }
    private fun DatagramSocket.send(port: Int, bytes: ByteArray) = send(DatagramPacket(bytes, bytes.size, loopback, port))
    private fun DatagramSocket.reply(): DatagramPacket = DatagramPacket(ByteArray(4096), 4096).also(::receive)
    private suspend fun awaitState(bridge: UdpWsjtCbBridge, predicate: (LanState) -> Boolean): LanState =
        withTimeout(3_000) { bridge.state.first(predicate) }

    private suspend fun withListener(address: String = "", test: suspend (UdpWsjtCbBridge, Int) -> Unit) {
        val port = unusedPort()
        UdpWsjtCbBridge().use { bridge ->
            assertEquals(BackendResult.Success, bridge.listen(LanSettings(port, address)))
            test(bridge, port)
        }
    }
}
