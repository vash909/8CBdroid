package net.vash.ft8cbdroid.core.audio

import net.vash.ft8cbdroid.core.time.*
import org.junit.Assert.*
import org.junit.Test

class UtcSlotsTest {
    private class Source(var now: ClockReading = ClockReading(10_000_000_000, 1_800_000_000_000)) : TimeSource { override fun read() = now }
    private fun setup(): Pair<Source, ReceiveClock> {
        val s = Source(); return s to ReceiveClock(s).also { it.apply(TimeMeasurement("NTP: test", s.now.monotonicNanos, s.now.unixMillis * 1_000_000, 2.0, s.now)) }
    }
    @Test fun arbitraryChunksProduceExactUtcWindowsAndDiscardTheInitialPartial() {
        val (source, clock) = setup(); val slots = mutableListOf<CompletedUtcSlot>(); val assembler = UtcSlotAssembler(clock, slots::add)
        // Capture begins two seconds into a UTC period and spans two complete periods.
        var index = 0L
        while (index < 43 * 12000) {
            val size = minOf(317, (43 * 12000 - index).toInt())
            val boot = 12_000_000_000L + index * 1_000_000_000 / 12000
            source.now = ClockReading(boot + 30_000_000, 1_800_000_000_000 + (boot + 30_000_000 - 10_000_000_000) / 1_000_000)
            assembler.push(TimedCaptureChunk(index, boot, FloatArray(size) { ((index + it) % 1000) / 1000f }, 12.0))
            index += size
        }
        assertEquals(2, slots.size); assertEquals(1_800_000_015_000, slots[0].utcStartMillis)
        assertEquals(1_800_000_030_000, slots[1].utcStartMillis)
        assertEquals(180000, slots[0].pcm.toFloatArray().size)
        assertEquals((13 * 12000 % 1000) / 1000f, slots[0].pcm.toFloatArray()[0], 0f)
        assertEquals(1L, assembler.progress().discarded)
    }
    @Test fun droppedSamplesNeverCreateAPaddedOrStitchedSlot() {
        val (s, clock) = setup(); val slots = mutableListOf<CompletedUtcSlot>(); val a = UtcSlotAssembler(clock, slots::add)
        for (second in 0 until 30) {
            if (second == 5) continue
            val boot = 10_000_000_000L + second * 1_000_000_000L
            s.now = ClockReading(boot, 1_800_000_000_000 + second * 1000L)
            a.push(TimedCaptureChunk(second * 12000L, boot, FloatArray(12000) { .25f }, 0.0))
        }
        assertEquals(1, slots.size); assertEquals(1_800_000_015_000, slots.single().utcStartMillis)
        assertTrue(a.progress().discarded > 0)
        a.clear(); assertEquals(0, a.progress().collected)
    }
    @Test fun staleClockAndDiscontinuousTimeCannotCompleteAWindow() {
        val (s, clock) = setup(); val slots = mutableListOf<CompletedUtcSlot>(); val a = UtcSlotAssembler(clock, slots::add)
        a.push(TimedCaptureChunk(0, s.now.monotonicNanos, FloatArray(12000), null))
        clock.reset()
        try { a.push(TimedCaptureChunk(12000, s.now.monotonicNanos + 1_000_000_000, FloatArray(12000), null)); fail() } catch (_: TimeException) { }
        assertTrue(slots.isEmpty())
    }
    @Test fun hardwareRateAndFilterDelayAreAccountedForAtBothInputRates() {
        for (rate in listOf(44100, 48000)) {
            val timeline = CaptureTimeline(rate, rate / 5)
            val now = 10_000_000_000L
            val zero = timeline.stamp(CaptureTimestamp(rate.toLong(), now), rate.toLong(), now)
            assertTrue(kotlin.math.abs(now - 1_000_000_000L - 64 * 1_000_000_000L / rate - zero) <= 1)
            timeline.stamp(CaptureTimestamp(rate * 3L, now + 2_000_000_000), rate * 3L, now + 2_000_000_000)
            assertEquals(0.0, timeline.driftPpm!!, .0001)
        }
    }
    @Test fun timestampResetOverrunStalenessAndExcessDriftFailClosed() {
        for (kind in 0..3) {
            val t = CaptureTimeline(48000, 9600); t.stamp(CaptureTimestamp(48000, 10_000_000_000), 48000, 10_000_000_000)
            try {
                when (kind) {
                    0 -> t.stamp(CaptureTimestamp(0, 11_000_000_000), 96000, 11_000_000_000)
                    1 -> t.stamp(CaptureTimestamp(96000, 11_000_000_000), 48000, 11_000_000_000)
                    2 -> t.stamp(CaptureTimestamp(96000, 11_000_000_000), 96000, 12_000_000_000)
                    else -> t.stamp(CaptureTimestamp(96000, 11_004_000_000), 96000, 11_004_000_000)
                }
                fail("Bad timeline accepted")
            } catch (_: CaptureException) { }
        }
    }
}
