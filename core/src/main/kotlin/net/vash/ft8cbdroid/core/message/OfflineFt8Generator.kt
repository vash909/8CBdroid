package net.vash.ft8cbdroid.core.message

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import net.vash.ft8cbdroid.core.Ft8Engine
import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.core.WsjtCbFt8Profile

data class Ft8GenerationSettings(val audioFrequencyHz: Float = 1000f, val peakAmplitude: Float = .4f) {
    init {
        require(audioFrequencyHz.isFinite() && audioFrequencyHz in 200f..2900f) {
            "Base audio frequency must be between 200 and 2900 Hz."
        }
        require(peakAmplitude.isFinite() && peakAmplitude in .01f..1f) {
            "Peak level must be between 1 and 100 percent."
        }
    }
    val sampleRateHz: Int get() = WsjtCbFt8Profile.sampleRateHz
}

data class GeneratedFt8Waveform(
    val message: PackResult.Encoded,
    val settings: Ft8GenerationSettings,
    val pcm: Ft8PcmSlot,
) {
    /** Canonical mono PCM16 RIFF/WAVE; independent of Android document APIs. */
    fun wavBytes(): ByteArray {
        val samples = pcm.toFloatArray()
        val size = samples.size * 2
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(settings.sampleRateHz)
            putInt(settings.sampleRateHz * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(size)
            for (sample in samples) putShort((sample * 32767f).roundToInt().toShort())
        }.array()
    }
}

sealed interface WaveformGenerationResult {
    data class Generated(val waveform: GeneratedFt8Waveform) : WaveformGenerationResult
    data class ConfirmationRequired(val encodedText: String) : WaveformGenerationResult
    data class Unsupported(val reason: String) : WaveformGenerationResult
}

/** Offline message-to-PCM path. No hash learning, device audio, radio or TX scheduling. */
class OfflineFt8Generator(private val engine: Ft8Engine) {
    suspend fun generate(
        text: String,
        mode: EncodingMode,
        settings: Ft8GenerationSettings,
        acceptContentChanges: Boolean = false,
    ): WaveformGenerationResult {
        val message = when (val packed = CbMessageCodec.pack(text, mode)) {
            is PackResult.Unsupported -> return WaveformGenerationResult.Unsupported(packed.reason)
            is PackResult.Encoded -> packed
        }
        if (message.contentChanged && !acceptContentChanges)
            return WaveformGenerationResult.ConfirmationRequired(message.transmittedText)
        val pcm = engine.encode(message.payload, settings.audioFrequencyHz, settings.peakAmplitude)
        return WaveformGenerationResult.Generated(GeneratedFt8Waveform(message, settings, pcm))
    }
}
