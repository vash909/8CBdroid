package net.vash.ft8cbdroid.protocol

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Offline header codec only. No socket is opened and no radio command is sent. */
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
            output.writeUtf8("skeleton")
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}

/** Phone listens here; the desktop sends to the phone and uses an ephemeral source port. */
data class LanSettings(
    val listenPort: Int = WsjtCbProtocol.DEFAULT_LISTEN_PORT,
    val expectedDesktopAddress: String = "",
) {
    init { require(listenPort in 1..65535) }
}

interface WsjtCbBridge {
    suspend fun listen(settings: LanSettings): net.vash.ft8cbdroid.core.BackendResult
    suspend fun stop()
}
