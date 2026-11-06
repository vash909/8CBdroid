package net.vash.ft8cbdroid.core.audio

import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.core.time.*
import kotlin.math.abs

data class CaptureTimestamp(val framePosition: Long, val monotonicNanos: Long)
data class TimedCaptureChunk(val firstOutputSample: Long, val firstSampleNanos: Long, val samples: FloatArray, val driftPpm: Double?)

interface TimestampedMicrophoneCapture : MicrophoneCapture {
    suspend fun captureTimed(inputId: Int, update: suspend (CaptureMetrics) -> Unit, chunk: (TimedCaptureChunk) -> Unit)
}

/** Fail closed on unavailable/stale timestamps, overruns, resets or >3 ms departure from nominal rate.
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
        if (abs(duration - expected) > 3_000_000)
            throw CaptureException("Audio clock drift exceeded 3 ms. Incomplete UTC audio was discarded; restart capture.")
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
class UtcSlotAssembler(private val clock: ReceiveClock, private val completed: (CompletedUtcSlot) -> Unit) {
    private val mapping = clock.snapshot()
    private val buffer = FloatArray(Ft8PcmSlot.sampleCount)
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
        } else if (abs(chunk.firstSampleNanos - anchorNanos - (chunk.firstOutputSample - anchorIndex) * 1_000_000_000L / 12000) > 3_000_000) {
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
                    completed(CompletedUtcSlot(utc, Ft8PcmSlot.fromSamples(buffer), mapping.source,
                        clock.snapshot().uncertaintyMillis?.plus(3.1)))
                    count++; lastUtc = utc; size = 0; targetGrid = target + buffer.size
                }
            }
            grid++
        }
        expectedIndex = chunk.firstOutputSample + chunk.samples.size
    }
    fun clear() { buffer.fill(0f); size = 0; expectedIndex = null; targetGrid = null }
}
