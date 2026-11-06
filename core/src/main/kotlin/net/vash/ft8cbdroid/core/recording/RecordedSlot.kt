package net.vash.ft8cbdroid.core.recording

import java.io.InputStream
import java.time.Instant
import kotlin.math.roundToLong
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.message.*

/** A supplied UTC boundary, never inferred from a document's name or modification time. */
data class RecordedSlotContext(val slotStartUtc: Instant) {
    init {
        require(slotStartUtc.nano == 0 && slotStartUtc.epochSecond % 15 == 0L) {
            "Enter a UTC slot boundary at 00, 15, 30 or 45 seconds."
        }
    }
    companion object {
        fun parse(text: String): RecordedSlotContext {
            require(text.endsWith("Z")) { "Use UTC, for example 2026-11-06T12:00:00Z." }
            return try { RecordedSlotContext(Instant.parse(text)) }
            catch (error: java.time.format.DateTimeParseException) {
                throw IllegalArgumentException("Use UTC, for example 2026-11-06T12:00:00Z.", error)
            }
        }
    }
}

/** Strict RIFF/PCM reader for one complete slot. No resampling, downmixing or truncation. */
object RecordedSlotWav {
    const val maxBytes = 1_048_576
    fun read(input: InputStream): Ft8PcmSlot {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - out.size()))
            if (count < 0) break
            require(count > 0) { "Could not read the WAV document." }
            out.write(buffer, 0, count)
            require(out.size() <= maxBytes) { "WAV documents must be no larger than 1 MiB." }
        }
        return parse(out.toByteArray())
    }
    fun parse(bytes: ByteArray): Ft8PcmSlot {
        require(bytes.size in 12..maxBytes) { "Invalid or oversized WAV document." }
        fun tag(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun u16(offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun u32(offset: Int): Long = (0..3).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255) shl (8 * i)) }
        require(tag(0) == "RIFF" && tag(8) == "WAVE" && u32(4) == bytes.size - 8L) {
            "Expected a complete RIFF/WAVE document."
        }
        var position = 12
        var formatSeen = false
        var dataOffset: Int? = null
        while (position < bytes.size) {
            require(bytes.size - position >= 8) { "Truncated WAV chunk header." }
            val kind = tag(position)
            val size = u32(position + 4)
            val start = position + 8
            val end = start.toLong() + size
            val paddedEnd = end + (size and 1)
            require(paddedEnd <= bytes.size) { "Truncated WAV chunk." }
            when (kind) {
                "fmt " -> {
                    require(!formatSeen && size >= 16) { "Invalid or duplicate WAV format chunk." }
                    require(u16(start) == 1 && u16(start + 2) == 1 && u32(start + 4) == 12000L &&
                        u32(start + 8) == 24000L && u16(start + 12) == 2 && u16(start + 14) == 16 &&
                        (size == 16L || (size == 18L && u16(start + 16) == 0))) {
                        "Use mono PCM16 WAV at 12,000 Hz; conversion is not available yet."
                    }
                    formatSeen = true
                }
                "data" -> {
                    require(dataOffset == null && size == Ft8PcmSlot.sampleCount * 2L) {
                        "Use exactly one 15-second slot (180,000 mono samples)."
                    }
                    dataOffset = start
                }
            }
            position = paddedEnd.toInt()
        }
        require(formatSeen && dataOffset != null) { "WAV format or audio data is missing." }
        val start = dataOffset
        return Ft8PcmSlot.fromSamples(FloatArray(Ft8PcmSlot.sampleCount) { i -> u16(start + i * 2).toShort() / 32768f })
    }
}

enum class RecordedDecodeFlag { CRC_LDPC_VALID, SNR_ESTIMATED, SNR_UNAVAILABLE, UNKNOWN_HASH, AMBIGUOUS_HASH, UNSUPPORTED_MESSAGE }

data class RecordedDecode(
    val frame: Ft8DecodedFrame,
    val message: UnpackResult,
    val slotStartUtc: Instant,
    val signalStartUtc: Instant,
    /** FT8's nominal signal onset is 0.5 seconds after the UTC boundary. */
    val deltaTimeSeconds: Float,
    val flags: Set<RecordedDecodeFlag>,
)
data class RecordedSlotResult(val context: RecordedSlotContext, val decodes: List<RecordedDecode>, val candidateCount: Int)

/** Isolated file reception; it cannot teach the live/LAN or preview hash sessions. */
class RecordedSlotDecoder(private val engine: Ft8Engine) {
    suspend fun decode(pcm: Ft8PcmSlot, context: RecordedSlotContext, stationCall: String? = null): RecordedSlotResult {
        val channel = engine.decode(pcm)
        val session = CbMessageSession(stationCall)
        val rows = channel.frames.map { frame ->
            val message = session.unpack(frame.payload)
            val parts = (message as? UnpackResult.Decoded)?.message?.parts.orEmpty()
            val flags = mutableSetOf(RecordedDecodeFlag.CRC_LDPC_VALID)
            flags += if (frame.estimatedSnrDb != null) RecordedDecodeFlag.SNR_ESTIMATED else RecordedDecodeFlag.SNR_UNAVAILABLE
            if (message is UnpackResult.Unsupported) flags += RecordedDecodeFlag.UNSUPPORTED_MESSAGE
            if (parts.any { it is MessagePart.UnresolvedCall }) flags += RecordedDecodeFlag.UNKNOWN_HASH
            if (parts.any { it is MessagePart.AmbiguousCall }) flags += RecordedDecodeFlag.AMBIGUOUS_HASH
            RecordedDecode(frame, message, context.slotStartUtc,
                context.slotStartUtc.plusMillis((frame.startSecondsInSlot.toDouble() * 1000).roundToLong()),
                frame.startSecondsInSlot - .5f, flags.toSet())
        }
        return RecordedSlotResult(context, rows, channel.candidateCount)
    }
}
