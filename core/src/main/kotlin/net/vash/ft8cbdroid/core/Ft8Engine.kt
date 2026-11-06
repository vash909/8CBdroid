package net.vash.ft8cbdroid.core

/** Raw channel payload; text interpretation and CB hash policy belong above DSP. */
class Ft8Payload77 private constructor(private val packed: ByteArray) {
    fun toByteArray(): ByteArray = packed.copyOf()
    fun toBitString(): String = (0 until 77).joinToString("") {
        ((packed[it / 8].toInt() ushr (7 - it % 8)) and 1).toString()
    }
    override fun equals(other: Any?): Boolean = other is Ft8Payload77 && packed.contentEquals(other.packed)
    override fun hashCode(): Int = packed.contentHashCode()

    companion object {
        fun fromBytes(bytes: ByteArray): Ft8Payload77 {
            require(bytes.size == 10 && (bytes[9].toInt() and 7) == 0) {
                "Expected 10 payload bytes with three zero padding bits"
            }
            return Ft8Payload77(bytes.copyOf())
        }
        fun fromBits(bits: String): Ft8Payload77 {
            require(bits.length == 77 && bits.all { it == '0' || it == '1' }) { "Expected exactly 77 binary digits" }
            val bytes = ByteArray(10)
            bits.forEachIndexed { i, value ->
                if (value == '1') bytes[i / 8] = (bytes[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
            }
            return fromBytes(bytes)
        }
    }
}

/** A complete mono slot, independent of Android's capture rate and UTC clock. */
class Ft8PcmSlot private constructor(private val samples: FloatArray) {
    fun toFloatArray(): FloatArray = samples.copyOf()

    companion object {
        const val sampleCount = 180_000
        fun fromSamples(samples: FloatArray): Ft8PcmSlot {
            require(samples.size == sampleCount) { "Expected one 15-second 12 kHz PCM slot" }
            require(samples.all { it.isFinite() && it in -1f..1f }) { "PCM must be finite and normalized to [-1, 1]" }
            return Ft8PcmSlot(samples.copyOf())
        }
    }
}

/** CRC/LDPC-valid channel frame; this does not establish a valid CB peer. */
data class Ft8DecodedFrame(
    val payload: Ft8Payload77,
    val startSecondsInSlot: Float,
    val audioFrequencyHz: Float,
    val syncScore: Int,
    /** Experimental 2500 Hz estimate; null when the noise floor cannot be measured. */
    val estimatedSnrDb: Float? = null,
)

/** Budgets are mirrored and checked in the native profile table; Standard preserves R08 limits. */
enum class Ft8DecodeProfile(val label: String, val maxCandidates: Int, val maxIterations: Int, val deadlineMillis: Long) {
    FAST("Fast", 24, 12, 2_000),
    STANDARD("Standard", 60, 25, 4_000),
}

data class Ft8DecodeResult(
    val frames: List<Ft8DecodedFrame>,
    val candidateCount: Int,
    val profile: Ft8DecodeProfile = Ft8DecodeProfile.STANDARD,
    val decodeMillis: Long? = null,
    val processedCandidates: Int? = null,
)

/** Complete-slot channel operations; no text codec, device audio or RF access. */
interface Ft8Engine {
    suspend fun decode(slot: Ft8PcmSlot, profile: Ft8DecodeProfile = Ft8DecodeProfile.STANDARD): Ft8DecodeResult
    suspend fun encode(payload: Ft8Payload77, audioFrequencyHz: Float, peakAmplitude: Float = .4f): Ft8PcmSlot
}
