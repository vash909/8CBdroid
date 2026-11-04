package net.vash.ft8cbdroid.core

import java.util.Locale

/** Validation matches WSJT-CB's complete CB callsign grammar; not an FT8 encoder. */
object CbCallsign {
    private val numeric = Regex("(?:[0-9]{1,3}[A-Z]{1,2}[0-9]{1,3}|[0-9][A-Z]{1,2}[0-9]{4})")
    private val compound = Regex("[0-9]{1,3}[A-Z]{1,2}/[A-Z]{2}")

    fun normalize(value: String): String = value.trim().uppercase(Locale.ROOT)
    fun isValid(value: String): Boolean = normalize(value).let {
        numeric.matches(it) || compound.matches(it)
    }
}

object WsjtCbFt8Profile {
    const val sampleRateHz = 12_000
    const val slotSeconds = 15
    const val symbolCount = 79
    const val samplesPerSymbol = 1_920
    val workingFrequenciesHz = listOf(27_265_000L, 27_045_000L)
}

enum class ConnectionMode { USB_CAT, PHONE_VOX, WSJT_CB_LAN }

data class StationSettings(
    val callsign: String = "",
    val grid: String = "",
    val frequencyHz: Long = WsjtCbFt8Profile.workingFrequenciesHz.first(),
    val mode: ConnectionMode = ConnectionMode.PHONE_VOX,
)

sealed interface BackendResult {
    data object Success : BackendResult
    data class Unavailable(val reason: String) : BackendResult
}

data class RadioCapabilities(val frequencyControl: Boolean, val ptt: Boolean)
data class UsbRadioConfig(val vendorId: Int, val productId: Int, val baudRate: Int = 9_600)

/** Hamlib/JNI and native Android CAT drivers implement this same boundary. */
interface RadioController {
    val capabilities: RadioCapabilities
    suspend fun connect(config: UsbRadioConfig): BackendResult
    suspend fun setFrequency(frequencyHz: Long): BackendResult
    suspend fun setPtt(enabled: Boolean): BackendResult
    suspend fun disconnect()
}

enum class AudioRoute { PHONE_MIC_SPEAKER, USB_AUDIO }

/** Android audio runs at its hardware rate; the DSP adapter must resample to 12 kHz. */
interface AudioEngine {
    suspend fun startReceive(route: AudioRoute): BackendResult
    suspend fun playTransmit(pcm: ShortArray, sampleRateHz: Int): BackendResult
    suspend fun stop()
}

interface Ft8Engine {
    suspend fun decode(pcm12kHz: ShortArray): List<String>
    suspend fun encode(message: String): ShortArray
}
