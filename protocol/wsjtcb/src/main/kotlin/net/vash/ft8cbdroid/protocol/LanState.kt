package net.vash.ft8cbdroid.protocol

enum class LanPhase { STOPPED, LISTENING, CONNECTED, ERROR }

data class DesktopStatus(
    val dialFrequencyHz: Long,
    val mode: String,
    val dxCall: String,
    val report: String,
    val txMode: String,
    val txEnabled: Boolean,
    val transmitting: Boolean,
    val decoding: Boolean,
)

data class DesktopPeer(
    val address: String,
    val port: Int,
    val instanceId: String,
    val schema: Int,
    val version: String,
    val revision: String,
)

data class LanState(
    val phase: LanPhase = LanPhase.STOPPED,
    val listenPort: Int? = null,
    val peer: DesktopPeer? = null,
    val status: DesktopStatus? = null,
    val error: String? = null,
)
