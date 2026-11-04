package net.vash.ft8cbdroid.protocol

enum class LanPhase { STOPPED, LISTENING, CONNECTED, ERROR }

data class LanDecode(
    val isNew: Boolean,
    val utcMillisOfDay: Long?,
    val snr: Int,
    val deltaTimeSeconds: Double,
    val deltaFrequencyHz: Long,
    val mode: String,
    val message: String,
    val lowConfidence: Boolean = false,
    val offAir: Boolean = false,
)

object DecodeHistory {
    const val LIMIT = 200

    fun append(history: List<LanDecode>, decode: LanDecode): List<LanDecode> {
        val key = decode.copy(isNew = true)
        val existing = history.indexOfFirst { it.copy(isNew = true) == key }
        if (existing >= 0) {
            return if (decode.isNew && !history[existing].isNew) {
                history.mapIndexed { index, item -> if (index == existing) decode else item }
            } else history
        }
        return (history + decode).takeLast(LIMIT)
    }
}

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
    val decodes: List<LanDecode> = emptyList(),
)
