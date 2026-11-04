package net.vash.ft8cbdroid

import net.vash.ft8cbdroid.core.*

/** Radio and audio placeholders; neither opens hardware. */
class PendingRadioController : RadioController {
    override val capabilities = RadioCapabilities(frequencyControl = false, ptt = false)
    override suspend fun connect(config: UsbRadioConfig) = pending()
    override suspend fun setFrequency(frequencyHz: Long) = pending()
    override suspend fun setPtt(enabled: Boolean) = pending()
    override suspend fun disconnect() = Unit
    private fun pending() = BackendResult.Unavailable("Hamlib/JNI or Android CAT backend is not implemented")
}

class PendingAudioEngine : AudioEngine {
    override suspend fun startReceive(route: AudioRoute) = pending()
    override suspend fun playTransmit(pcm: ShortArray, sampleRateHz: Int) = pending()
    override suspend fun stop() = Unit
    private fun pending() = BackendResult.Unavailable("AudioRecord/AudioTrack and routing are not implemented")
}
