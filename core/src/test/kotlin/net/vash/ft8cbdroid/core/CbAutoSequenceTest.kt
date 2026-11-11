package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.qso.*
import net.vash.ft8cbdroid.core.audio.SlotPeriod
import net.vash.ft8cbdroid.core.message.ManualMessage
import org.junit.Assert.*
import org.junit.Test

class CbAutoSequenceTest {
    private val bits = Ft8Payload77.fromBits("0".repeat(77))
    private fun policy() = CbAutoSequence(0, 30_000, true, SlotPeriod.EVEN)
    @Test fun callerWaitsForTheFixedBoundaryAndNeverQueuesTwice() {
        val p = policy(); val qso = CbQsoSnapshot()
        assertNull(p.tick(28_499, qso, null, null, false))
        val send = requireNotNull(p.tick(28_500, qso, null, null, false))
        assertEquals(ManualMessage.CQ, send.kind); assertEquals(30_000, send.utcMillis)
        assertNull(p.tick(29_100, qso, null, null, false))
        p.sent(send, bits); assertEquals(1, p.state.transmissions)
        assertEquals(60_000L, p.state.nextTxUtcMillis)
    }
    @Test fun anEmptyButCompletedReceiveWindowAllowsBoundedRepeats() {
        val p = policy(); val qso = CbQsoSnapshot(cqSent = true)
        for (target in listOf(30_000L, 60_000L, 90_000L)) {
            val action = requireNotNull(p.tick(target - 1000, qso, null, target - 15_000, false))
            p.sent(action, bits)
        }
        assertNull(p.tick(119_000, qso, null, 105_000, false))
        assertFalse(p.state.active); assertEquals(3, p.state.transmissions); assertFalse(p.state.completed)
    }
    @Test fun missingOrLateDecodeNeverCreatesALateTransmission() {
        val p = policy(); p.sent(requireNotNull(p.tick(29_000, CbQsoSnapshot(), null, null, false)), bits)
        assertNull(p.tick(59_000, CbQsoSnapshot(cqSent = true), null, 30_000, false))
        assertTrue(p.state.active)
        assertNull(p.tick(59_600, CbQsoSnapshot(cqSent = true), null, 45_000, false))
        assertFalse(p.state.active); assertFalse(p.state.completed)
    }
    @Test fun completionRequiresTheQualifiedModelAndStopInvalidatesOutstandingWork() {
        val p = policy(); val action = requireNotNull(p.tick(29_000, CbQsoSnapshot(), null, null, false))
        p.stop("Operator stopped")
        assertTrue(runCatching { p.sent(action, bits) }.isFailure)
        assertNull(p.tick(29_200, CbQsoSnapshot(), null, null, false))
        val complete = CbQsoSnapshot(peer = "26AT101", sentReport = -12, receivedReport = -7,
            localAcknowledgedReport = true, peerAcknowledgedReport = true, localFinalSent = true, peerFinalReceived = true)
        val done = policy(); assertNull(done.tick(2000, complete, null, null, false))
        assertTrue(done.state.completed); assertFalse(done.state.active)
    }
    @Test fun busyDoesNotSendAndNeitherRepeatedInputNorChangedFactsBypassTimeouts() {
        val p = policy()
        assertNull(p.tick(29_000, CbQsoSnapshot(), null, null, true))
        assertNull(p.tick(149_000, CbQsoSnapshot(), null, null, true))
        assertFalse(p.state.active)
        val session = policy()
        session.tick(119_000, CbQsoSnapshot(cqSent = true), null, null, true)
        session.tick(238_000, CbQsoSnapshot(peer = "26AT101", replyReceived = true), null, null, true)
        session.tick(240_000, CbQsoSnapshot(peer = "26AT101", sentReport = -7), null, null, true)
        assertFalse(session.state.active); assertFalse(session.state.completed)
    }
    @Test fun answeringRequiresAdviceInTheExplicitlySelectedPeriod() {
        val p = CbAutoSequence(0, 30_000, false, SlotPeriod.EVEN)
        val qso = CbQsoSnapshot(peer = "26AT101")
        assertNull(p.tick(28_500, qso, null, null, false))
        val wrong = CbQsoSuggestion("26AT101", ManualMessage.REPLY, "<26AT101> 1AT106", -7, 1000, SlotPeriod.ODD)
        assertNull(p.tick(29_100, qso, wrong, null, false)); assertFalse(p.state.active)
    }
}
