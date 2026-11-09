package net.vash.ft8cbdroid.core.audio

import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.core.time.ReceiveClock
import net.vash.ft8cbdroid.core.time.UtcMapping

enum class SlotPeriod { EVEN, ODD }
class SchedulingException(message: String) : IllegalStateException(message)

/** Process-local measurement for one output inventory, never a manual timing offset. */
data class OutputLatency(val outputId: Int, val inventory: Long, val nanos: Long,
    val spreadNanos: Long, val measuredAtNanos: Long) {
    init { require(nanos in 0..500_000_000L && spreadNanos in 0..20_000_000L && measuredAtNanos >= 0) }
    fun validate(now: Long, id: Int, generation: Long = inventory) {
        if (id != outputId || generation != inventory || now - measuredAtNanos !in 0 until 300_000_000_000L)
            throw SchedulingException("Output latency is stale or the route changed. Measure speaker latency again.")
    }
}

data class ScheduledSlot(val utcMillis: Long, val firstFrameNanos: Long, val commandNanos: Long,
    val mapping: UtcMapping, val latency: OutputLatency) {
    /** Poll during both waiting and playback. Corrections never retarget a queued message. */
    fun validate(clock: ReceiveClock) {
        try { mapping.validate(clock.source.read(), clock.revision) }
        catch (_: IllegalStateException) { throw SchedulingException("Time correction changed or expired. Synchronize and queue again.") }
    }
    fun remaining(clock: ReceiveClock): Long {
        validate(clock)
        val remaining = commandNanos - clock.source.read().monotonicNanos
        if (remaining < -20_000_000L) throw SchedulingException("The slot deadline was missed. Nothing was started; queue again explicitly.")
        return remaining
    }
}

fun planSlot(clock: ReceiveClock, latency: OutputLatency, outputId: Int, period: SlotPeriod): ScheduledSlot {
    val mapping = try { clock.snapshot() } catch (_: IllegalStateException) {
        throw SchedulingException("Synchronize with NTP or GPS before queueing a UTC slot.")
    }
    val now = clock.source.read()
    latency.validate(now.monotonicNanos, outputId)
    val earliest = mapping.at(now.monotonicNanos) + 2_000_000_000L + latency.nanos + 300_000_000L
    var index = Math.floorDiv(earliest, 15_000_000_000L) + 1
    if (Math.floorMod(index, 2L) != if (period == SlotPeriod.EVEN) 0L else 1L) index++
    val utcNanos = index * 15_000_000_000L
    // Desktop FT8 starts at +500 ms; R07 PCM contains 800 ms of leading silence.
    val firstFrame = mapping.anchor.monotonicNanos + utcNanos - mapping.utcNanos - 300_000_000L
    if (mapping.expiresAtNanos != null && firstFrame + 15_000_000_000L >= mapping.expiresAtNanos)
        throw SchedulingException("Time correction will expire before this slot ends. Synchronize again.")
    return ScheduledSlot(utcNanos / 1_000_000L, firstFrame, firstFrame - latency.nanos, mapping, latency)
}

/** Timestamp observations must advance and agree before a latency estimate is usable. */
class PresentationEstimator(private val commandNanos: Long) {
    private val estimates = mutableListOf<Long>()
    private var lastFrame = -1L
    fun observe(frame: Long, presentationNanos: Long) {
        if (frame < 1200 || frame <= lastFrame) return
        lastFrame = frame
        estimates += presentationNanos - frame * 1_000_000_000L / 12_000 - commandNanos
    }
    fun estimate(outputId: Int, inventory: Long, now: Long): OutputLatency {
        if (estimates.size < 3) throw SchedulingException("No stable output timestamps. Measure latency again.")
        val values = estimates.takeLast(8).sorted()
        val spread = values.last() - values.first()
        val median = values[values.size / 2]
        if (median !in 0..500_000_000L || spread > 20_000_000L)
            throw SchedulingException("Output timing is unstable. Measure latency again.")
        return OutputLatency(outputId, inventory, median, spread, now)
    }
}

interface ScheduledAudioPreview : AudioPreview {
    /** Explicit bounded calibration output, never a sent FT8 message. */
    suspend fun testTone(outputId: Int, frequencyHz: Float, peakAmplitude: Float) {
        throw PreviewException("Test tone is unavailable on this output.")
    }
    suspend fun measureLatency(outputId: Int): OutputLatency
    suspend fun playScheduled(slot: Ft8PcmSlot, outputId: Int, settings: PreviewSettings,
        plan: ScheduledSlot, clock: ReceiveClock, update: (PreviewReport) -> Unit)
}
