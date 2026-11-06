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
    val uncertaintyMillis: Double?,
    val observed: ClockReading,
    val roundTripMillis: Double? = null,
) {
    init {
        require(uncertaintyMillis == null || (uncertaintyMillis.isFinite() && uncertaintyMillis in 0.0..100.0))
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
            value.uncertaintyMillis?.plus(value.ageMillis(now).coerceAtLeast(0) * 0.00005),
            value.monotonicNanos + 600_000_000_000L)
        result.validate(now, revision)
        return result
    }
}

/** Only GPS_PROVIDER fix times originate in the satellite clock. Coordinates are not needed. */
data class GpsTimeReading(val utcMillis: Long, val elapsedRealtimeNanos: Long, val isGps: Boolean, val isMock: Boolean)

class GpsTimeValidator {
    private var previous: GpsTimeReading? = null
    private var consecutive = 0
    fun accept(value: GpsTimeReading, now: ClockReading): TimeMeasurement? {
        require(value.isGps && !value.isMock)
        require(value.utcMillis in 946_684_800_000L..4_102_444_800_000L)
        require(value.elapsedRealtimeNanos > 0 && value.elapsedRealtimeNanos in
            (now.monotonicNanos - 2_000_000_000L)..now.monotonicNanos)
        val old = previous
        if (value == old) return null
        consecutive = if (old != null && value.elapsedRealtimeNanos - old.elapsedRealtimeNanos in 1..2_500_000_000L &&
            abs((value.utcMillis - old.utcMillis) * 1_000_000L -
                (value.elapsedRealtimeNanos - old.elapsedRealtimeNanos)) <= 10_000_000L) consecutive + 1 else 1
        previous = value
        // Location exposes no bound on UTC accuracy. Never turn position accuracy into time accuracy.
        return if (consecutive >= 3) TimeMeasurement("GPS/GNSS", value.elapsedRealtimeNanos,
            value.utcMillis * 1_000_000L, null, now) else null
    }
}
