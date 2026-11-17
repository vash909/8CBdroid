package net.vash.ft8cbdroid.core.time

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class Ft8PeriodProgressTest {
    @Test fun boundariesResetRatherThanCountDownAndAlternateUtcParity() {
        assertEquals(Ft8PeriodProgress(0, false), Ft8PeriodProgress.atUtcMillis(0))
        assertEquals(Ft8PeriodProgress(14999, false), Ft8PeriodProgress.atUtcMillis(14999))
        assertEquals(Ft8PeriodProgress(0, true), Ft8PeriodProgress.atUtcMillis(15000))
        assertEquals(Ft8PeriodProgress(0, false), Ft8PeriodProgress.atUtcMillis(30000))
        assertEquals(0.5f, Ft8PeriodProgress.atUtcMillis(37500).fraction, 0f)
    }
    @Test fun midnightAndPreEpochTimesKeepUtcAlignment() {
        assertEquals(Ft8PeriodProgress(14999, true), Ft8PeriodProgress.atUtcMillis(Instant.parse("2026-11-17T23:59:59.999Z").toEpochMilli()))
        assertEquals(Ft8PeriodProgress(0, false), Ft8PeriodProgress.atUtcMillis(Instant.parse("2026-11-18T00:00:00Z").toEpochMilli()))
        assertEquals(Ft8PeriodProgress(14999, true), Ft8PeriodProgress.atUtcMillis(-1))
    }
}
