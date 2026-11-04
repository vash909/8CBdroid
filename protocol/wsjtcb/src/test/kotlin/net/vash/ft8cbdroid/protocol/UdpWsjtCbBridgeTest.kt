package net.vash.ft8cbdroid.protocol

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
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
                sender.send(port, DesktopFixtures.decode)
                awaitState(bridge) { it.decodes.isNotEmpty() }
                clock.set(1_500)
                assertEquals(WsjtCbProtocol.Type.HEARTBEAT, sender.reply().let { WsjtCbProtocol.readHeader(it.data.copyOf(it.length)).type })
                clock.set(4_000)
                val expired = awaitState(bridge) { it.phase == LanPhase.LISTENING }
                assertNull(expired.peer)
                assertNull(expired.status)
                assertTrue(expired.decodes.isEmpty())
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
            repeat(40) { cycle ->
                assertEquals("Restart cycle $cycle", BackendResult.Success, bridge.listen(LanSettings(port)))
                assertTrue(bridge.listen(LanSettings(port)) is BackendResult.Unavailable)
                desktop().use { sender ->
                    sender.send(port, DesktopFixtures.heartbeat)
                    sender.reply()
                    awaitState(bridge) { it.peer != null }
                }
                bridge.stop()
                assertEquals(LanPhase.STOPPED, bridge.state.value.phase)
            }
        }
    }

    @Test fun receivesDecodesOnlyAfterHeartbeatAndDeduplicatesReplay() = runBlocking<Unit> {
        withListener { bridge, port ->
            desktop().use { sender ->
                sender.send(port, DesktopFixtures.decode)
                sender.send(port, DesktopFixtures.heartbeat)
                sender.reply()
                awaitState(bridge) { it.peer != null }
                assertTrue(bridge.state.value.decodes.isEmpty())
                sender.send(port, DesktopFixtures.decode.copyOf(66))
                sender.send(port, DesktopFixtures.decode)
                val decoded = awaitState(bridge) { it.decodes.size == 1 }.decodes.single()
                assertEquals("CQ 1AT106 JN45", decoded.message)
                sender.send(port, DesktopFixtures.decode.copyOf().also { it[23] = 0 })
                sender.send(port, DesktopFixtures.status)
                val afterReplay = awaitState(bridge) { it.status != null }
                assertEquals(1, afterReplay.decodes.size)
                assertTrue(afterReplay.decodes.single().isNew)
            }
        }
    }

    @Test fun requestsReplayOnSelectedSourcePortAndHandlesBothClearActions() = runBlocking<Unit> {
        withListener { bridge, port ->
            assertTrue(bridge.requestReplay() is BackendResult.Unavailable)
            desktop().use { sender ->
                sender.send(port, DesktopFixtures.heartbeat)
                sender.reply()
                sender.send(port, DesktopFixtures.decode)
                awaitState(bridge) { it.decodes.isNotEmpty() }
                bridge.clearDecodes()
                assertTrue(bridge.state.value.decodes.isEmpty())
                sender.soTimeout = 150
                assertThrows(SocketTimeoutException::class.java) { sender.reply() }
                sender.soTimeout = 2_000
                assertEquals(BackendResult.Success, bridge.requestReplay())
                val request = sender.reply()
                assertEquals(port, request.port)
                val header = WsjtCbProtocol.readHeader(request.data.copyOf(request.length))
                assertEquals(WsjtCbProtocol.Type.REPLAY, header.type)
                assertEquals("WSJT-CB", header.instanceId)
                assertEquals(3, header.schema)
                sender.send(port, DesktopFixtures.decode.copyOf().also { it[23] = 0 })
                assertFalse(awaitState(bridge) { it.decodes.isNotEmpty() }.decodes.single().isNew)
                sender.send(port, DesktopFixtures.clear)
                sender.send(port, DesktopFixtures.status)
                assertTrue(awaitState(bridge) { it.status != null }.decodes.isEmpty())
                assertEquals(LanPhase.CONNECTED, bridge.state.value.phase)
            }
        }
    }

    @Test fun ignoresForeignDecodeAndClearPackets() = runBlocking<Unit> {
        withListener { bridge, port ->
            desktop().use { sender -> desktop().use { foreign ->
                sender.send(port, DesktopFixtures.heartbeat)
                sender.reply()
                sender.send(port, DesktopFixtures.decode)
                awaitState(bridge) { it.decodes.isNotEmpty() }
                foreign.send(port, DesktopFixtures.clear)
                foreign.send(port, DesktopFixtures.decode.copyOf().also { ByteBuffer.wrap(it).putInt(28, -18) })
                sender.send(port, DesktopFixtures.clear.copyOf().also { it[16] = 'X'.code.toByte() })
                sender.send(port, DesktopFixtures.status)
                val state = awaitState(bridge) { it.status != null }
                assertEquals(-17, state.decodes.single().snr)
            } }
        }
    }

    @Test fun boundsHistoryUnderLiveTraffic() = runBlocking<Unit> {
        withListener { bridge, port ->
            desktop().use { sender ->
                sender.send(port, DesktopFixtures.heartbeat)
                sender.reply()
                repeat(25) { batch ->
                    repeat(10) { offset ->
                        sender.send(port, DesktopFixtures.decode.copyOf().also {
                            ByteBuffer.wrap(it).putInt(24, batch * 10 + offset)
                        })
                    }
                    awaitState(bridge) { it.decodes.lastOrNull()?.utcMillisOfDay == (batch * 10 + 9).toLong() }
                }
                assertEquals(200, bridge.state.value.decodes.size)
                assertEquals(50L, bridge.state.value.decodes.first().utcMillisOfDay)
                bridge.stop()
                assertTrue(bridge.state.value.decodes.isEmpty())
            }
        }
    }

    @Test fun desktopCloseDiscardsItsDecodeHistory() = runBlocking<Unit> {
        withListener { bridge, port ->
            desktop().use { sender ->
                sender.send(port, DesktopFixtures.heartbeat)
                sender.reply()
                sender.send(port, DesktopFixtures.decode)
                awaitState(bridge) { it.decodes.isNotEmpty() }
                sender.send(port, DesktopFixtures.close)
                assertTrue(awaitState(bridge) { it.phase == LanPhase.LISTENING }.decodes.isEmpty())
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
