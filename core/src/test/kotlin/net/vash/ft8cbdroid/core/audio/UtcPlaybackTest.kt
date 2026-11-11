package net.vash.ft8cbdroid.core.audio

import net.vash.ft8cbdroid.core.time.*
import org.junit.Assert.*
import org.junit.Test

class UtcPlaybackTest {
    @Test fun acousticOutputRetainsEverySignalSampleAndRefusesAnActiveTail() {
        val samples = FloatArray(180000)
        samples[9600] = .2f; samples[161279] = -.3f
        val played = acousticWaveform(net.vash.ft8cbdroid.core.Ft8PcmSlot.fromSamples(samples))
        assertEquals(161280, played.size); assertEquals(.2f, played[9600], 0f); assertEquals(-.3f, played.last(), 0f)
        samples[161280] = .01f
        assertThrows(IllegalArgumentException::class.java) { acousticWaveform(net.vash.ft8cbdroid.core.Ft8PcmSlot.fromSamples(samples)) }
    }
    @Test fun automaticResponseKeepsItsBoundaryAndRejectsLatePlansOrMappingChanges() {
        val s = Source(); val c = clock(s); val l = latency(s)
        s.advance(13_100)
        val p = planResponseSlot(c, l, 7, 1_800_000_015_000)
        assertEquals(1_800_000_015_000, p.utcMillis)
        val command = p.commandNanos
        s.advance(500)
        assertThrows(SchedulingException::class.java) { planResponseSlot(c, l, 7, p.utcMillis) }
        c.reset(); assertThrows(SchedulingException::class.java) { p.validate(c) }
        assertEquals(command, p.commandNanos)
    }
    private class Source : TimeSource {
        var mono = 10_000_000_000L
        var utc = 1_800_000_001_000L
        override fun read() = ClockReading(mono, utc)
        fun advance(ms: Long) { mono += ms*1_000_000; utc += ms }
    }
    private fun clock(source: Source) = ReceiveClock(source).apply {
        apply(TimeMeasurement("NTP/test",source.mono,source.utc*1_000_000,1.0,source.read()))
    }
    private fun latency(s: Source) = OutputLatency(7,2,80_000_000,2_000_000,s.mono)
    @Test fun evenAndOddAreUtcBoundariesWithPreparationMarginAndMeasuredCompensation() {
        val s=Source();val c=clock(s)
        for (period in SlotPeriod.entries) {
            val p=planSlot(c,latency(s),7,period)
            assertEquals(0L,p.utcMillis%15000)
            assertEquals(if(period==SlotPeriod.EVEN) 0L else 1L,(p.utcMillis/15000)%2)
            assertTrue(p.commandNanos-s.mono>=2_000_000_000L)
            assertTrue(p.firstFrameNanos-s.mono<33_000_000_000L)
            assertEquals(80_000_000L,p.firstFrameNanos-p.commandNanos)
            assertEquals(p.utcMillis*1_000_000,p.mapping.at(p.firstFrameNanos) + 300_000_000L)
        }
    }
    @Test fun nearBoundarySkipsItRatherThanSendingLateIncludingDayRollover() {
        val s=Source().apply { utc=java.time.Instant.parse("2027-01-15T23:59:59.500Z").toEpochMilli() };val c=clock(s)
        val p=planSlot(c,latency(s),7,SlotPeriod.EVEN)
        assertTrue(p.utcMillis-s.utc>2000);assertEquals(0L,p.utcMillis%30000)
    }
    @Test fun missedCommandDeadlineNeverRollsForwardToAnotherSlot() {
        val s=Source();val c=clock(s);val p=planSlot(c,latency(s),7,SlotPeriod.EVEN)
        s.advance((p.commandNanos-s.mono)/1_000_000+21)
        assertThrows(SchedulingException::class.java) { p.remaining(c) }
    }
    @Test fun aChangedCorrectionCancelsEvenWhenTheNewUtcValueIsIdentical() {
        val s=Source();val c=clock(s);val p=planSlot(c,latency(s),7,SlotPeriod.ODD)
        c.apply(TimeMeasurement("GPS/GNSS",s.mono,s.utc*1_000_000,null,s.read()))
        assertThrows(SchedulingException::class.java) { p.validate(c) }
    }
    @Test fun wallJumpResetAndMonotonicRegressionInvalidateTheFrozenMapping() {
        for (change in listOf<(Source,ReceiveClock)->Unit>({s,_->s.utc+=100},{s,_->s.mono-=1},{_,c->c.reset()})) {
            val s=Source();val c=clock(s);val p=planSlot(c,latency(s),7,SlotPeriod.EVEN)
            change(s,c);assertThrows(SchedulingException::class.java) { p.validate(c) }
        }
    }
    @Test fun unsynchronizedAndExpiredClockCannotQueue() {
        val s=Source();assertThrows(SchedulingException::class.java) { planSlot(ReceiveClock(s),latency(s),7,SlotPeriod.EVEN) }
        val c=clock(s);s.advance(600001)
        assertThrows(SchedulingException::class.java) { planSlot(c,latency(s),7,SlotPeriod.EVEN) }
    }
    @Test fun timeCorrectionMustSurviveTheEntireSlot() {
        val s=Source();val c=clock(s);s.advance(585000)
        assertThrows(SchedulingException::class.java) { planSlot(c,latency(s),7,SlotPeriod.EVEN) }
    }
    @Test fun outputMeasurementsCannotCrossDevicesInventoryChangesOrFiveMinutes() {
        val s=Source();val l=latency(s)
        assertThrows(SchedulingException::class.java) { l.validate(s.mono,8) }
        assertThrows(SchedulingException::class.java) { l.validate(s.mono,7,3) }
        assertThrows(SchedulingException::class.java) { l.validate(s.mono+300_000_000_000,7) }
        assertThrows(SchedulingException::class.java) { l.validate(s.mono-1,7) }
    }
    @Test fun timestampMeasurementRejectsDuplicatesInsufficientAndUnstableObservations() {
        val e=PresentationEstimator(1_000_000_000)
        repeat(3) { e.observe(1200,1_180_000_000) }
        assertThrows(SchedulingException::class.java) { e.estimate(7,2,2_000_000_000) }
        e.observe(2400,1_280_000_000);e.observe(3600,1_380_000_000)
        assertEquals(80_000_000L,e.estimate(7,2,2_000_000_000).nanos)
        e.observe(4800,1_600_000_000)
        assertThrows(SchedulingException::class.java) { e.estimate(7,2,2_000_000_000) }
    }
}
