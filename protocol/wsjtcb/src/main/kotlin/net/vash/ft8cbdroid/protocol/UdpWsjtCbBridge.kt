package net.vash.ft8cbdroid.protocol

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.vash.ft8cbdroid.core.BackendResult

/** One unicast desktop per listener. Sends only heartbeat replies, never radio commands. */
class UdpWsjtCbBridge internal constructor(
    private val heartbeatMillis: Long,
    private val timeoutMillis: Long,
    private val pollMillis: Int,
    private val nowMillis: () -> Long,
) : WsjtCbBridge {
    constructor() : this(15_000, 45_000, 250, { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })

    private val lock = Any()
    private val mutableState = MutableStateFlow(LanState())
    override val state = mutableState.asStateFlow()
    private var session: Session? = null

    override suspend fun listen(settings: LanSettings): BackendResult {
        var created: Session? = null
        try {
            return withContext(Dispatchers.IO) {
                synchronized(lock) {
                    if (session != null) return@synchronized BackendResult.Unavailable("Listener is already running")
                    var socket: DatagramSocket? = null
                    try {
                        val bound = DatagramSocket(null)
                        socket = bound
                        bound.soTimeout = pollMillis
                        bound.bind(InetSocketAddress("0.0.0.0", settings.listenPort))
                        val allowed = settings.expectedDesktopAddress.takeIf(String::isNotEmpty)?.let {
                            InetAddress.getByAddress(it.split('.').map { octet -> octet.toInt().toByte() }.toByteArray())
                        }
                        val active = Session(bound, allowed)
                        created = active
                        session = active
                        mutableState.value = LanState(LanPhase.LISTENING, bound.localPort)
                        Thread({ receive(active) }, "wsjtcb-udp").apply { isDaemon = true }.start()
                        BackendResult.Success
                    } catch (error: Exception) {
                        socket?.close()
                        session = null
                        val reason = "Could not start UDP listener: ${error.message.orEmpty()}"
                        mutableState.value = LanState(phase = LanPhase.ERROR, error = reason)
                        BackendResult.Unavailable(reason)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) { if (created != null && session === created) close() }
            throw cancelled
        }
    }

    override suspend fun stop() { close() }

    override fun close() {
        synchronized(lock) {
            val active = session
            session = null
            active?.socket?.close() // Unblocks receive immediately; never send desktop Close commands.
            mutableState.value = LanState()
        }
    }

    private class Session(val socket: DatagramSocket, val allowedAddress: InetAddress?) {
        var peer: DesktopPeer? = null
        var lastSeen = 0L
        var lastSent = 0L
    }

    private fun receive(active: Session) {
        val buffer = ByteArray(65_507)
        try {
            while (!active.socket.isClosed) {
                maintainPeer(active)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    active.socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (active.allowedAddress != null && packet.address != active.allowedAddress) continue
                val message = try {
                    WsjtCbProtocol.readMessage(buffer.copyOf(packet.length))
                } catch (_: IllegalArgumentException) {
                    continue // A malformed packet must not terminate the listener or establish a peer.
                }
                val id = message.header.instanceId?.takeIf(String::isNotEmpty) ?: continue
                val peer = active.peer
                if (peer != null && (peer.address != packet.address.hostAddress || peer.port != packet.port || peer.instanceId != id)) continue
                when (message) {
                    is WsjtCbProtocol.Message.Heartbeat -> {
                        active.peer = DesktopPeer(packet.address.hostAddress!!, packet.port, id,
                            message.maximumSchema, message.version, message.revision)
                        active.lastSeen = nowMillis()
                        sendHeartbeat(active)
                        publish(active) { it.copy(phase = LanPhase.CONNECTED, peer = active.peer) }
                    }
                    is WsjtCbProtocol.Message.Status -> if (peer != null) {
                        active.lastSeen = nowMillis()
                        publish(active) { it.copy(status = message.status) }
                    }
                    is WsjtCbProtocol.Message.Close -> if (peer != null) clearPeer(active)
                    is WsjtCbProtocol.Message.Unsupported -> Unit
                }
            }
        } catch (error: IOException) {
            fail(active, "UDP listener stopped: ${error.message.orEmpty()}")
        } catch (error: SecurityException) {
            fail(active, "Local network access was denied: ${error.message.orEmpty()}")
        } finally {
            active.socket.close()
        }
    }

    private fun maintainPeer(active: Session) {
        if (active.peer == null) return
        val now = nowMillis()
        if (now - active.lastSeen >= timeoutMillis) clearPeer(active)
        else if (now - active.lastSent >= heartbeatMillis) sendHeartbeat(active)
    }

    private fun sendHeartbeat(active: Session) {
        val peer = active.peer ?: return
        val bytes = WsjtCbProtocol.heartbeat(peer.instanceId, peer.schema)
        active.socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(peer.address), peer.port))
        active.lastSent = nowMillis()
    }

    private fun clearPeer(active: Session) {
        active.peer = null
        publish(active) { it.copy(phase = LanPhase.LISTENING, peer = null, status = null) }
    }

    private fun publish(active: Session, change: (LanState) -> LanState) {
        synchronized(lock) { if (session === active) mutableState.value = change(mutableState.value) }
    }

    private fun fail(active: Session, reason: String) {
        synchronized(lock) {
            if (session === active) {
                session = null
                mutableState.value = LanState(phase = LanPhase.ERROR, error = reason)
            }
        }
    }
}
