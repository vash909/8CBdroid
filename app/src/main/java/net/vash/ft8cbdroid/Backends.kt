package net.vash.ft8cbdroid

import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.protocol.LanSettings
import net.vash.ft8cbdroid.protocol.WsjtCbBridge

/** Explicit placeholders: selecting a backend never opens USB, audio or sockets. */
class PendingRadioController : RadioController {
    override val capabilities = RadioCapabilities(frequencyControl = false, ptt = false)
    override suspend fun connect(config: UsbRadioConfig) = pending()
    override suspend fun setFrequency(frequencyHz: Long) = pending()
    override suspend fun setPtt(enabled: Boolean) = pending()
    override suspend fun disconnect() = Unit
    private fun pending() = BackendResult.Unavailable("Backend Hamlib/JNI o CAT Android da implementare")
}

class PendingAudioEngine : AudioEngine {
    override suspend fun startReceive(route: AudioRoute) = pending()
    override suspend fun playTransmit(pcm: ShortArray, sampleRateHz: Int) = pending()
    override suspend fun stop() = Unit
    private fun pending() = BackendResult.Unavailable("AudioRecord/AudioTrack e routing da implementare")
}

class PendingWsjtCbBridge : WsjtCbBridge {
    override suspend fun listen(settings: LanSettings) =
        BackendResult.Unavailable("Trasporto UDP, negoziazione e payload da implementare")
    override suspend fun stop() = Unit
}
