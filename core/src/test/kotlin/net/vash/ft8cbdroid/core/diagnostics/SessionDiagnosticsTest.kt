package net.vash.ft8cbdroid.core.diagnostics

import org.junit.Assert.*
import org.junit.Test

class SessionDiagnosticsTest {
    @Test fun explicitConsentAndRestartDiscardOldSession() {
        val recorder = SessionDiagnostics()
        recorder.event(0, ReceiveDiagnosticEvent.INPUT_STARTED)
        recorder.sample(0, DiagnosticObservation(), DiagnosticResources())
        assertTrue(recorder.snapshot().events.isEmpty()); assertTrue(recorder.snapshot().samples.isEmpty())
        recorder.start(10); recorder.event(11, ReceiveDiagnosticEvent.DECODE_COMPLETE, 20)
        val frozen = recorder.stop(40)
        recorder.event(41, ReceiveDiagnosticEvent.DECODE_COMPLETE, 99)
        assertEquals(frozen, recorder.snapshot()); assertEquals(DiagnosticEnd.OPERATOR, frozen.end)
        recorder.start(50); assertTrue(recorder.snapshot().events.isEmpty()); assertEquals(0L, recorder.snapshot().decodeCount)
    }
    @Test fun boundSamplesAndDurationEvenWithExcessiveSamplingAndEvents() {
        val recorder = SessionDiagnostics(); recorder.start(0)
        repeat(4001) { n ->
            recorder.sample(n * 1000L, DiagnosticObservation(), DiagnosticResources())
            recorder.event(n * 1000L, ReceiveDiagnosticEvent.WINDOW_COMPLETE)
        }
        val report = recorder.snapshot()
        assertEquals(DiagnosticEnd.LIMIT, report.end); assertEquals(3_600_000L, report.elapsedMillis)
        assertEquals(121, report.samples.size); assertEquals(3600L, report.events[ReceiveDiagnosticEvent.WINDOW_COMPLETE])
        assertEquals(3_600_000L, report.samples.last().elapsedMillis)
    }
    @Test fun expiredEventsCannotExtendSessionAndElapsedCannotMoveBackwards() {
        val recorder = SessionDiagnostics(100); recorder.start(10)
        recorder.event(80, ReceiveDiagnosticEvent.DECODE_COMPLETE, 9)
        recorder.event(30, ReceiveDiagnosticEvent.DECODE_COMPLETE, 5)
        assertEquals(70L, recorder.snapshot().elapsedMillis)
        assertEquals(14L, recorder.snapshot().decodeTotalMillis); assertEquals(9L, recorder.snapshot().decodeMaxMillis)
        recorder.event(110, ReceiveDiagnosticEvent.DECODE_COMPLETE, 6)
        assertEquals(2L, recorder.snapshot().decodeCount); assertEquals(DiagnosticEnd.LIMIT, recorder.stop(150).end)
    }
    @Test fun immutableSnapshotsAndInvalidDecodeDurations() {
        val recorder = SessionDiagnostics(); recorder.start(0)
        recorder.event(1, ReceiveDiagnosticEvent.DECODE_COMPLETE, -1)
        val old = recorder.snapshot()
        recorder.event(2, ReceiveDiagnosticEvent.DECODE_COMPLETE, 65_000)
        recorder.sample(3, DiagnosticObservation(), DiagnosticResources())
        assertEquals(1L, old.events[ReceiveDiagnosticEvent.DECODE_COMPLETE]); assertTrue(old.samples.isEmpty())
        assertEquals(0L, recorder.snapshot().decodeCount); assertNull(recorder.snapshot().decodeMaxMillis)
    }
}
