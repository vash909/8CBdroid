package net.vash.ft8cbdroid.core.audio

import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.core.Ft8ReceiveWindow
import net.vash.ft8cbdroid.core.time.*
import kotlin.math.abs

private const val MAX_TIMELINE_ERROR_NANOS = 10_000_000L

data class CaptureTimestamp(val framePosition: Long, val monotonicNanos: Long)
data class TimedCaptureChunk(val firstOutputSample: Long, val firstSampleNanos: Long, val samples: FloatArray, val driftPpm: Double?)

interface TimestampedMicrophoneCapture : MicrophoneCapture {
    suspend fun captureTimed(inputId: Int, update: suspend (CaptureMetrics) -> Unit, chunk: (TimedCaptureChunk) -> Unit)
}

/** Fail closed on unavailable/stale timestamps, overruns, resets or >10 ms departure from nominal rate.
 * The 64-frame causal filter delay is subtracted before assigning DSP sample times.
 */
class CaptureTimeline(private val rate: Int, private val bufferFrames: Int) {
    private var anchor: CaptureTimestamp? = null
    private var previous: CaptureTimestamp? = null
    var driftPpm: Double? = null
        private set
    fun stamp(value: CaptureTimestamp, readFrames: Long, nowNanos: Long): Long {
        require(rate == 48000 || rate == 44100)
        if (value.framePosition < 0 || value.monotonicNanos <= 0 || value.monotonicNanos > nowNanos + 5_000_000 ||
            nowNanos - value.monotonicNanos > 500_000_000) throw CaptureException("Audio timestamps are unavailable or stale. Restart capture.")
        val old = previous
        if (old != null && (value.framePosition < old.framePosition || value.monotonicNanos < old.monotonicNanos))
            throw CaptureException("The audio timestamp reset. Incomplete UTC audio was discarded; restart capture.")
        val backlog = value.framePosition + (nowNanos - value.monotonicNanos) * rate / 1_000_000_000L - readFrames
        if (backlog > bufferFrames + rate / 20 || backlog < -rate / 20)
            throw CaptureException("Audio continuity could not be confirmed. Incomplete UTC audio was discarded; restart capture.")
        val first = anchor ?: value.also { anchor = it }
        val duration = value.monotonicNanos - first.monotonicNanos
        val expected = (value.framePosition - first.framePosition) * 1_000_000_000.0 / rate
        if (abs(duration - expected) > MAX_TIMELINE_ERROR_NANOS)
            throw CaptureException("Audio timing departure exceeded 10 ms. Incomplete UTC audio was discarded; restart capture.",
                "rate=$rate readFrames=$readFrames frame=${value.framePosition} anchorFrame=${first.framePosition} " +
                    "elapsedMs=${duration / 1e6} errorMs=${(duration - expected) / 1e6}")
        if (duration >= 2_000_000_000L) driftPpm = (expected / duration - 1) * 1e6
        previous = value
        return first.monotonicNanos - (first.framePosition + 64) * 1_000_000_000L / rate
    }
}

/** Discard startup audio until timestamps remain consistent for 500 ms and at least three observations. */
class CaptureTimestampSettler(private val rate: Int, private val bufferFrames: Int) {
    private var timeline = CaptureTimeline(rate, bufferFrames)
    private var first: CaptureTimestamp? = null
    private var last: CaptureTimestamp? = null
    private var observations = 0
    var ready = false
        private set
    val driftPpm get() = timeline.driftPpm
    fun reset() {
        check(!ready)
        timeline = CaptureTimeline(rate, bufferFrames); first = null; last = null; observations = 0
    }
    fun stamp(value: CaptureTimestamp, readFrames: Long, nowNanos: Long): Long? {
        val zero = try { timeline.stamp(value, readFrames, nowNanos) }
        catch (error: CaptureException) { if (ready) throw error; reset(); return null }
        if (!ready) {
            if (first == null) first = value
            if (value != last) observations++
            last = value
            ready = observations >= 3 && value.monotonicNanos - requireNotNull(first).monotonicNanos >= 500_000_000
        }
        return zero.takeIf { ready }
    }
}

data class CompletedUtcSlot(val utcStartMillis: Long, val pcm: Ft8PcmSlot, val source: String, val uncertaintyMillis: Double?)
data class UtcSlotProgress(val completed: Long, val collected: Int, val discarded: Long, val lastUtcMillis: Long?, val driftPpm: Double?)

/** Bounded single-slot builder. Initial/late partials are discarded, never zero-filled or stitched. */
class UtcSlotAssembler(clock: ReceiveClock, completed: (CompletedUtcSlot) -> Unit) {
    private val builder = UtcWindowBuilder(clock, Ft8PcmSlot.sampleCount) { utc, samples, source, uncertainty ->
        completed(CompletedUtcSlot(utc, Ft8PcmSlot.fromSamples(samples), source, uncertainty))
    }
    fun push(chunk: TimedCaptureChunk) = builder.push(chunk)
    fun progress() = builder.progress()
    fun clear() = builder.clear()
}

data class CompletedReceiveWindow(val utcStartMillis: Long, val pcm: Ft8ReceiveWindow,
    val source: String, val uncertaintyMillis: Double?)

/** An early decision uses 14 seconds of genuine input; the unused final second is never fabricated. */
class UtcReceiveWindowAssembler(clock: ReceiveClock, completed: (CompletedReceiveWindow) -> Unit) {
    private val builder = UtcWindowBuilder(clock, Ft8ReceiveWindow.sampleCount) { utc, samples, source, uncertainty ->
        completed(CompletedReceiveWindow(utc, Ft8ReceiveWindow.fromSamples(samples), source, uncertainty))
    }
    fun push(chunk: TimedCaptureChunk) = builder.push(chunk)
    fun progress() = builder.progress()
    fun clear() = builder.clear()
}

private class UtcWindowBuilder(private val clock: ReceiveClock, samples: Int,
    private val completed: (Long, FloatArray, String, Double?) -> Unit) {
    private val mapping = clock.snapshot()
    private val buffer = FloatArray(samples)
    private var expectedIndex: Long? = null
    private var anchorIndex = 0L
    private var anchorNanos = 0L
    private var anchorGrid = 0L
    private var targetGrid: Long? = null
    private var size = 0
    private var count = 0L
    private var discarded = 0L
    private var lastUtc: Long? = null
    private var drift: Double? = null
    fun progress() = UtcSlotProgress(count, size, discarded, lastUtc, drift)
    fun push(chunk: TimedCaptureChunk) {
        mapping.validate(clock.source.read(), clock.revision)
        require(chunk.samples.size in 1..12000 && chunk.firstOutputSample >= 0)
        require(chunk.samples.all { it.isFinite() })
        if (expectedIndex == null || expectedIndex != chunk.firstOutputSample) {
            if (size > 0) discarded++
            size = 0
            anchorIndex = chunk.firstOutputSample; anchorNanos = chunk.firstSampleNanos
            val utc = mapping.at(anchorNanos)
            // Ceil to the next 12 kHz grid point; quantization is less than one sample.
            anchorGrid = Math.floorDiv(utc, 1_000_000_000L) * 12000 +
                (Math.floorMod(utc, 1_000_000_000L) * 12000 + 999_999_999L) / 1_000_000_000L
            targetGrid = Math.floorDiv(anchorGrid + 179999, 180000) * 180000
            if (anchorGrid != targetGrid) discarded++
        } else if (abs(chunk.firstSampleNanos - anchorNanos - (chunk.firstOutputSample - anchorIndex) * 1_000_000_000L / 12000) > MAX_TIMELINE_ERROR_NANOS) {
            clear(); throw TimeException("The audio timeline changed. Restart microphone capture.")
        }
        drift = chunk.driftPpm
        var grid = anchorGrid + chunk.firstOutputSample - anchorIndex
        for (sample in chunk.samples) {
            val target = requireNotNull(targetGrid)
            if (grid >= target) {
                buffer[size++] = sample.coerceIn(-1f, 1f)
                if (size == buffer.size) {
                    val utc = target / 12000 * 1000
                    completed(utc, buffer, mapping.source,
                        clock.snapshot().uncertaintyMillis?.plus(MAX_TIMELINE_ERROR_NANOS / 1e6 + 0.1))
                    count++; lastUtc = utc; size = 0; targetGrid = target + Ft8PcmSlot.sampleCount
                }
            }
            grid++
        }
        expectedIndex = chunk.firstOutputSample + chunk.samples.size
    }
    fun clear() { buffer.fill(0f); size = 0; expectedIndex = null; targetGrid = null }
}
