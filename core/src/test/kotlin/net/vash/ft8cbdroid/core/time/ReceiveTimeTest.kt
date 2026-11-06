package net.vash.ft8cbdroid.core.time

import org.junit.Assert.*
import org.junit.Test

class ReceiveTimeTest {
    private class Source(var now: ClockReading = ClockReading(10_000_000_000, 1_800_000_000_000)) : TimeSource {
        override fun read() = now
        fun advance(ms: Long) { now = ClockReading(now.monotonicNanos + ms * 1_000_000, now.unixMillis + ms) }
    }
    private fun sample(s: Source) = TimeMeasurement("GPS/GNSS", s.now.monotonicNanos, s.now.unixMillis * 1_000_000 + 40_000_000, 2.0, s.now)
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalStateException) { } }
    @Test fun synchronizationIsRequiredAndNeverFallsBackAfterExpiryOrReset() {
        val source = Source(); val clock = ReceiveClock(source)
        rejects { clock.snapshot() }; clock.apply(sample(source)); val mapping = clock.snapshot()
        assertEquals(40_000_000L, mapping.at(source.now.monotonicNanos) - source.now.unixMillis * 1_000_000)
        source.advance(599_999); assertTrue(clock.snapshot().uncertaintyMillis!! > 31)
        source.advance(1); rejects { clock.snapshot() }
        clock.reset(); rejects { mapping.validate(source.now, clock.revision) }; rejects { clock.snapshot() }
    }
    @Test fun changedSystemClockOrRevisionInvalidatesAnActiveMapping() {
        val source = Source(); val clock = ReceiveClock(source); clock.apply(sample(source)); val mapping = clock.snapshot()
        source.now = source.now.copy(unixMillis = source.now.unixMillis + 51)
        rejects { mapping.validate(source.now, clock.revision) }
        source.now = source.now.copy(unixMillis = source.now.unixMillis - 51)
        clock.apply(sample(source)); rejects { mapping.validate(source.now, clock.revision) }
    }
    @Test fun measurementDoesNotChangeTheDeviceReadingAndIsNotRestoredInANewClock() {
        val source = Source(); val original = source.now; val clock = ReceiveClock(source); clock.apply(sample(source))
        assertEquals(original, source.read()); rejects { ReceiveClock(source).snapshot() }
    }
    @Test fun threeFreshGpsFixesSynchronizeWithoutInventingAccuracy() {
        val validator = GpsTimeValidator(); val source = Source()
        fun fix() = GpsTimeReading(source.now.unixMillis + 40, source.now.monotonicNanos, true, false)
        val first = fix()
        assertNull(validator.accept(first, source.now)); assertNull(validator.accept(first, source.now))
        source.advance(1000); assertNull(validator.accept(fix(), source.now)); source.advance(1000)
        val measurement = validator.accept(fix(), source.now)!!
        assertEquals(source.now.unixMillis * 1_000_000 + 40_000_000, measurement.utcNanos)
        assertNull(measurement.uncertaintyMillis)
        val clock = ReceiveClock(source); clock.apply(measurement)
        assertEquals("GPS/GNSS", clock.snapshot().source); assertNull(clock.snapshot().uncertaintyMillis)
        source.advance(1000)
        assertNull(validator.accept(fix().copy(utcMillis = source.now.unixMillis + 500), source.now))
    }
    @Test fun staleMockFutureOrNonGpsFixesCannotSynchronize() {
        val now = ClockReading(10_000_000_000, 1_800_000_000_000)
        val fix = GpsTimeReading(now.unixMillis, now.monotonicNanos, true, false)
        for (bad in listOf(fix.copy(isMock = true), fix.copy(isGps = false), fix.copy(utcMillis = 0),
            fix.copy(elapsedRealtimeNanos = now.monotonicNanos - 2_000_000_001),
            fix.copy(elapsedRealtimeNanos = now.monotonicNanos + 1))) {
            try { GpsTimeValidator().accept(bad, now); fail("Bad GPS fix accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun customNamesAndIpAddressesAreNormalizedWithoutDns() {
        for ((input, expected) in listOf(" 192.168.001.2 " to "192.168.1.2", "[2001:DB8::1]" to "2001:db8::1",
            "NTP.Example.Org." to "ntp.example.org", "ntp" to "ntp", "::1" to "::1"))
            assertEquals(expected, NtpServers.normalizeHost(input))
        for (bad in listOf("", "https://ntp.example", "ntp.example:123", "999.1.2.3", "127.1", "abc..test", "-bad.example", "a@b", "::::", "a b", "fe80::1%wlan0")) {
            try { NtpServers.normalizeHost(bad); fail("Accepted $bad") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(7, NtpServers.presets.map { it.region }.distinct().size)
        NtpServers.presets.forEach { assertEquals(it.host, TimeSettings(it.id).validated().host) }
    }
}
