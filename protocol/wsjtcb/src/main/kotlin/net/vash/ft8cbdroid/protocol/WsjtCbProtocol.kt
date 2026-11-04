package net.vash.ft8cbdroid.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Qt-compatible UDP wire codec. Unknown types and trailing fields are ignored. */
object WsjtCbProtocol {
    const val MAGIC: Int = 0xadbccbda.toInt()
    const val MAX_SCHEMA = 3
    const val INITIAL_SCHEMA = 2
    const val HEARTBEAT_SECONDS = 15
    const val DEFAULT_LISTEN_PORT = 2237

    enum class Type(val wireValue: Int) {
        HEARTBEAT(0), STATUS(1), DECODE(2), CLEAR(3), REPLY(4), QSO_LOGGED(5),
        CLOSE(6), REPLAY(7), HALT_TX(8), FREE_TEXT(9), WSPR_DECODE(10), LOCATION(11),
        LOGGED_ADIF(12), HIGHLIGHT_CALLSIGN(13), SWITCH_CONFIGURATION(14), CONFIGURE(15),
        ANNOTATION_INFO(16);
    }

    data class Header(val schema: Int, val typeCode: Int, val instanceId: String?, val payloadOffset: Int) {
        val type: Type? get() = Type.entries.firstOrNull { it.wireValue == typeCode }
    }

    /** Unknown message types remain readable; optional trailing fields are left untouched. */
    fun readHeader(datagram: ByteArray): Header {
        require(datagram.size >= 16) { "Truncated WSJT-CB header" }
        val input = ByteBuffer.wrap(datagram).order(ByteOrder.BIG_ENDIAN)
        require(input.int == MAGIC) { "Invalid WSJT-CB magic" }
        val schema = input.int
        require(schema in INITIAL_SCHEMA..MAX_SCHEMA) { "Unsupported schema: $schema" }
        val type = input.int
        val length = input.int
        val id = if (length == -1) null else {
            require(length >= 0 && length <= input.remaining()) { "Invalid instance ID length" }
            val bytes = ByteArray(length)
            input.get(bytes)
            bytes.toString(Charsets.UTF_8)
        }
        return Header(schema, type, id, input.position())
    }

    /** A server reply uses the desktop instance ID discovered in its incoming datagram. */
    fun heartbeat(instanceId: String, schema: Int = INITIAL_SCHEMA): ByteArray {
        require(schema in INITIAL_SCHEMA..MAX_SCHEMA)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(schema)
            output.writeInt(Type.HEARTBEAT.wireValue)
            output.writeUtf8(instanceId)
            output.writeInt(MAX_SCHEMA)
            output.writeUtf8("8CBdroid 0.1.0-dev")
            output.writeUtf8("udp-listener")
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    sealed interface Message {
        val header: Header
        data class Heartbeat(
            override val header: Header,
            val maximumSchema: Int,
            val version: String,
            val revision: String,
        ) : Message
        data class Status(override val header: Header, val status: DesktopStatus) : Message
        data class Close(override val header: Header) : Message
        data class Unsupported(override val header: Header) : Message
    }

    fun readMessage(datagram: ByteArray): Message {
        val header = readHeader(datagram)
        val input = WireReader(ByteBuffer.wrap(datagram).order(ByteOrder.BIG_ENDIAN).apply {
            position(header.payloadOffset)
        })
        return when (header.type) {
            Type.HEARTBEAT -> {
                val maximum = if (input.remaining == 0) INITIAL_SCHEMA else {
                    input.uint32().also { require(it >= INITIAL_SCHEMA) }.coerceAtMost(MAX_SCHEMA.toLong()).toInt()
                }
                Message.Heartbeat(header, maximum, input.optionalString(), input.optionalString())
            }
            Type.STATUS -> Message.Status(header, DesktopStatus(
                dialFrequencyHz = input.frequency(),
                mode = input.string().orEmpty(),
                dxCall = input.string().orEmpty(),
                report = input.string().orEmpty(),
                txMode = input.string().orEmpty(),
                txEnabled = input.boolean(),
                transmitting = input.boolean(),
                decoding = input.boolean(),
            ))
            Type.CLOSE -> Message.Close(header)
            else -> Message.Unsupported(header)
        }
    }

    private class WireReader(private val input: ByteBuffer) {
        val remaining: Int get() = input.remaining()
        fun uint32(): Long {
            require(remaining >= 4) { "Truncated integer" }
            return input.int.toLong() and 0xffffffffL
        }
        fun frequency(): Long {
            require(remaining >= 8) { "Truncated frequency" }
            return input.long.also { require(it >= 0) { "Frequency exceeds supported range" } }
        }
        fun boolean(): Boolean {
            require(remaining >= 1) { "Truncated boolean" }
            return when (input.get().toInt()) {
                0 -> false
                1 -> true
                else -> throw IllegalArgumentException("Invalid Qt boolean")
            }
        }
        fun string(): String? {
            val length = uint32()
            if (length == 0xffffffffL) return null
            require(length <= remaining.toLong()) { "Invalid string length" }
            return ByteArray(length.toInt()).also(input::get).toString(Charsets.UTF_8)
        }
        fun optionalString(): String = if (remaining == 0) "" else string().orEmpty()
    }
}

/** Phone listens here; the desktop sends to the phone and uses an ephemeral source port. */
data class LanSettings(
    val listenPort: Int = WsjtCbProtocol.DEFAULT_LISTEN_PORT,
    val expectedDesktopAddress: String = "",
) {
    init {
        require(listenPort in 1..65535) { "Port must be between 1 and 65535" }
        require(isDesktopAddressValid(expectedDesktopAddress)) { "Enter a valid desktop IPv4 address" }
    }

    companion object {
        fun isDesktopAddressValid(address: String): Boolean = address.isEmpty() ||
            (address.split('.').size == 4 && address.split('.').all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                    part.toInt() in 0..255
            })
    }
}

interface WsjtCbBridge : java.io.Closeable {
    val state: kotlinx.coroutines.flow.StateFlow<LanState>
    suspend fun listen(settings: LanSettings): net.vash.ft8cbdroid.core.BackendResult
    suspend fun stop()
}
