package net.vash.ft8cbdroid.core.time

import kotlin.math.abs

data class ClockReading(val monotonicNanos: Long, val unixMillis: Long)
fun interface TimeSource { fun read(): ClockReading }
class TimeException(message: String) : IllegalStateException(message)

/** A UTC observation tied to the Android boot clock, never to callback arrival time. */
data class TimeMeasurement(
    val source: String,
    val monotonicNanos: Long,
    val utcNanos: Long,
    val uncertaintyMillis: Double,
    val observed: ClockReading,
    val roundTripMillis: Double? = null,
) {
    init {
        require(uncertaintyMillis.isFinite() && uncertaintyMillis in 0.0..100.0)
        require(monotonicNanos >= 0 && abs(observed.monotonicNanos - monotonicNanos) <= 2_000_000_000L)
    }
    fun ageMillis(now: ClockReading) = (now.monotonicNanos - monotonicNanos) / 1_000_000
    fun offsetMillis() = utcNanos / 1e6 - observed.unixMillis - (monotonicNanos - observed.monotonicNanos) / 1e6
}

data class UtcMapping(
    val revision: Long,
    val anchor: ClockReading,
    val utcNanos: Long,
    val source: String,
    val uncertaintyMillis: Double?,
    val expiresAtNanos: Long?,
) {
    fun at(monotonicNanos: Long) = utcNanos + monotonicNanos - anchor.monotonicNanos
    fun validate(now: ClockReading, revision: Long) {
        if (this.revision != revision) throw TimeException("Time correction changed. Restart microphone capture.")
        if (now.monotonicNanos < anchor.monotonicNanos) throw TimeException("The monotonic clock moved backwards. Restart capture.")
        if (abs((now.unixMillis - anchor.unixMillis) * 1_000_000L - (now.monotonicNanos - anchor.monotonicNanos)) > 50_000_000L)
            throw TimeException("Android's clock changed. Check time correction and restart capture.")
        if (expiresAtNanos != null && now.monotonicNanos >= expiresAtNanos)
            throw TimeException("Time correction expired. Synchronize with NTP or GPS again.")
    }
}

/** One process-local time correction. Persist preferences separately; never persist measurements. */
class ReceiveClock(val source: TimeSource) {
    @Volatile var revision = 0L
        private set
    private var measurement: TimeMeasurement? = null
    @Synchronized fun reset() { measurement = null; revision++ }
    @Synchronized fun apply(value: TimeMeasurement) {
        val now = source.read()
        require(now.monotonicNanos >= value.monotonicNanos) { "This time measurement is from the future. Measure again." }
        require(value.ageMillis(now) in 0 until 600_000) { "This time measurement has expired. Measure again." }
        val mapping = UtcMapping(revision, value.observed,
            value.utcNanos + value.observed.monotonicNanos - value.monotonicNanos,
            value.source, value.uncertaintyMillis, value.monotonicNanos + 600_000_000_000L)
        mapping.validate(now, revision)
        measurement = value; revision++
    }
    @Synchronized fun snapshot(): UtcMapping {
        val now = source.read()
        val value = measurement ?: throw TimeException("Not synchronized. Synchronize with NTP or GPS before assembling UTC slots.")
        val result = UtcMapping(revision, value.observed,
            value.utcNanos + value.observed.monotonicNanos - value.monotonicNanos, value.source,
            value.uncertaintyMillis + value.ageMillis(now).coerceAtLeast(0) * 0.00005,
            value.monotonicNanos + 600_000_000_000L)
        result.validate(now, revision)
        return result
    }
}

/** Reported raw GNSS clock fields. No latitude, longitude or saved location is needed. */
data class GnssTimeReading(
    val timeNanos: Long, val fullBiasNanos: Long, val biasNanos: Double,
    val leapSeconds: Int, val elapsedRealtimeNanos: Long,
    val uncertaintyNanos: Double, val discontinuityCount: Int,
)

class GnssTimeValidator {
    private var previous: GnssTimeReading? = null
    private var previousUtc = 0L
    private var consecutive = 0
    fun accept(value: GnssTimeReading, now: ClockReading): TimeMeasurement? {
        require(value.leapSeconds in 0..64 && value.biasNanos.isFinite() && abs(value.biasNanos) < 1e9)
        require(value.uncertaintyNanos.isFinite() && value.uncertaintyNanos in 0.0..5_000_000.0)
        require(value.elapsedRealtimeNanos in (now.monotonicNanos - 2_000_000_000L)..now.monotonicNanos)
        val utc = Math.subtractExact(Math.addExact(Math.subtractExact(value.timeNanos, value.fullBiasNanos),
            315_964_800_000_000_000L), value.leapSeconds * 1_000_000_000L + value.biasNanos.toLong())
        require(utc in 946_684_800_000_000_000L..4_102_444_800_000_000_000L)
        val old = previous
        if (old != null && value.elapsedRealtimeNanos == old.elapsedRealtimeNanos && utc == previousUtc &&
            old.discontinuityCount == value.discontinuityCount) return null
        consecutive = if (old != null && old.discontinuityCount == value.discontinuityCount &&
            old.leapSeconds == value.leapSeconds && value.elapsedRealtimeNanos - old.elapsedRealtimeNanos in 1..2_500_000_000L &&
            abs((utc - previousUtc) - (value.elapsedRealtimeNanos - old.elapsedRealtimeNanos)) <= 1_000_000L) consecutive + 1 else 1
        previous = value; previousUtc = utc
        return if (consecutive >= 3) TimeMeasurement("GPS/GNSS", value.elapsedRealtimeNanos, utc,
            value.uncertaintyNanos * 3 / 1e6 + 1, now) else null
    }
}
