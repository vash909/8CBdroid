package net.vash.ft8cbdroid.protocol

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.vash.ft8cbdroid.core.BackendResult

/** One unicast desktop per listener. Outbound messages are limited to heartbeat and explicit Replay. */
class UdpWsjtCbBridge internal constructor(
    private val heartbeatMillis: Long,
    private val timeoutMillis: Long,
    private val pollMillis: Int,
    private val nowMillis: () -> Long,
) : WsjtCbBridge {
    constructor() : this(15_000, 45_000, 250, { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) })

    private val lock = Any()
    private val startLock = Any()
    private val mutableState = MutableStateFlow(LanState())
    override val state = mutableState.asStateFlow()
    private var session: Session? = null
    private var retiring: Session? = null

    override suspend fun listen(settings: LanSettings): BackendResult {
        var created: Session? = null
        try {
            return withContext(Dispatchers.IO) {
                synchronized(startLock) start@{
                    val previous = synchronized(lock) {
                        if (session != null) return@start BackendResult.Unavailable("Listener is already running")
                        retiring
                    }
                    // A closed socket can retain its native receive reference until the worker unwinds.
                    // Wait outside the state lock so its final publication/cleanup cannot deadlock.
                    previous?.finished?.await()
                    synchronized(lock) {
                        retiring = null
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
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) { if (created != null && session === created) close() }
            throw cancelled
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("UDP restart was interrupted").apply { initCause(interrupted) }
        }
    }

    override suspend fun stop() { close() }

    override suspend fun requestReplay(): BackendResult = withContext(Dispatchers.IO) {
        val target = synchronized(lock) {
            val active = session
            val peer = mutableState.value.peer
            if (active == null || peer == null) null else active.socket to peer
        } ?: return@withContext BackendResult.Unavailable("Connect a desktop before requesting replay")
        val (socket, peer) = target
        try {
            val bytes = WsjtCbProtocol.replay(peer.instanceId, peer.schema)
            socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(peer.address), peer.port))
            BackendResult.Success
        } catch (error: IOException) {
            BackendResult.Unavailable("Could not request replay: ${error.message.orEmpty()}")
        } catch (_: SecurityException) {
            BackendResult.Unavailable("Local network access was denied")
        }
    }

    override fun clearDecodes() {
        synchronized(lock) { mutableState.value = mutableState.value.copy(decodes = emptyList()) }
    }

    override fun close() {
        synchronized(lock) {
            val active = session
            session = null
            active?.socket?.close() // Unblocks receive immediately; never send desktop Close commands.
            if (active != null) retiring = active
            mutableState.value = LanState()
        }
    }

    private class Session(val socket: DatagramSocket, val allowedAddress: InetAddress?) {
        val finished = CountDownLatch(1)
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
                    is WsjtCbProtocol.Message.Decode -> if (peer != null) {
                        active.lastSeen = nowMillis()
                        publish(active) { it.copy(decodes = DecodeHistory.append(it.decodes, message.decode)) }
                    }
                    is WsjtCbProtocol.Message.Clear -> if (peer != null) {
                        active.lastSeen = nowMillis()
                        publish(active) { it.copy(decodes = emptyList()) }
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
            try { active.socket.close() } finally { active.finished.countDown() }
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
        publish(active) { it.copy(phase = LanPhase.LISTENING, peer = null, status = null, decodes = emptyList()) }
    }

    private fun publish(active: Session, change: (LanState) -> LanState) {
        synchronized(lock) { if (session === active) mutableState.value = change(mutableState.value) }
    }

    private fun fail(active: Session, reason: String) {
        synchronized(lock) {
            if (session === active) {
                session = null
                retiring = active
                mutableState.value = LanState(phase = LanPhase.ERROR, error = reason)
            }
        }
    }
}
