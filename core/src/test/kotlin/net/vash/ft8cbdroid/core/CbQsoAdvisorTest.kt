package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.qso.*
import net.vash.ft8cbdroid.core.recording.RecordedDecodeFlag
import net.vash.ft8cbdroid.reference.QsoAdviceReferenceChecks as Reference
import org.junit.Assert.*
import org.junit.Test

class CbQsoAdvisorTest {
    @Test fun anEarlyReceptionRequiresAllFourteenSecondsAndTheSameQualifiedWireContext() {
        val a = advisor(); tx(a, ManualMessage.REPLY)
        val early = row("1AT106 -07").copy(capturedSamples = 168000)
        assertEquals(CbQsoAdmission.IGNORED, a.receive(early, 1, base + 13_999, 1500).admission)
        assertEquals(CbQsoAdmission.IGNORED, a.receive(early.copy(capturedSamples = 167999), 1, base + 14_100, 1500).admission)
        assertEquals(CbQsoAdmission.ACCEPTED, a.receive(early, 1, base + 14_100, 1500).admission)
        assertEquals(ManualMessage.ROGER, a.suggestion?.kind)
    }
    private val base = Reference.BASE
    private fun advisor() = CbQsoAdvisor(StationIdentity("1AT106", "JN45")).also {
        it.bindReceiver(1, base - 300_000)
        it.selectPeer("26AT101", 1500, SlotPeriod.EVEN, base - 300_000)
    }
    private fun row(text: String, slot: Long = base, hz: Float = 1500f) =
        Reference.row((CbMessageCodec.pack(text) as PackResult.Encoded).payload, slot, hz)
    private fun tx(a: CbQsoAdvisor, kind: ManualMessage, slot: Long = base - 30_000): CbQsoTransmission {
        val text = manualMessages(StationIdentity("1AT106", "JN45"), a.snapshot.peer.orEmpty(), -7).getValue(kind)
        val packed = CbMessageCodec.pack(text) as PackResult.Encoded
        return requireNotNull(a.prepare(kind, (-7).takeIf { kind in setOf(ManualMessage.REPORT, ManualMessage.ROGER) }, text, packed.payload)).also {
            assertTrue(a.sent(it, slot))
        }
    }
    @Test fun independentDesktopAdmissionMatrix() {
        val lines = javaClass.getResourceAsStream("/wsjtcb/qso-admission.tsv")!!.bufferedReader().use { it.readLines() }
        assertEquals(25, Reference.verify(lines))
    }
    @Test fun completeDesktopRelayUsesActualDecodedPayloadsInBothRoles() {
        val lines = javaClass.getResourceAsStream("/wsjtcb/qso-progression.tsv")!!.bufferedReader().use { it.readLines() }
        assertEquals(6, Reference.verifyExchange(lines))
    }
    @Test fun destinationOnlyNeedsActualDirectedOutputAndFreshWindow() {
        val a = advisor(); val r = row("1AT106 -07")
        assertEquals(CbQsoAdmission.IGNORED, a.receive(r, 1, base + 16_000, 1500).admission)
        tx(a, ManualMessage.REPLY)
        // A payload considered before directed output cannot be reused as a later response.
        assertEquals(CbQsoAdmission.DUPLICATE, a.receive(r, 1, base + 16_000, 1500).admission)
        val fresh = row("1AT106 -07", base + 30_000)
        assertEquals(CbQsoAdmission.ACCEPTED, a.receive(fresh, 1, base + 46_000, 1500).admission)
        assertEquals(ManualMessage.ROGER, a.suggestion?.kind)
    }
    @Test fun cancelledPreparedOrCustomAudioCannotAuthorizeAReport() {
        val a = advisor()
        val text = "<26AT101> 1AT106"; val p = CbMessageCodec.pack(text) as PackResult.Encoded
        assertNotNull(a.prepare(ManualMessage.REPLY, null, text, p.payload))
        a.cancelPending()
        assertEquals(CbQsoAdmission.IGNORED, a.receive(row("1AT106 -07"), 1, base + 16_000, 1500).admission)
        assertNull(a.prepare(ManualMessage.CUSTOM, null, text, p.payload))
        assertFalse(a.snapshot.replySent)
    }
    @Test fun receiverAndPeerReplacementDiscardLateRowsAndTickets() {
        val a = advisor(); val ticket = tx(a, ManualMessage.REPLY)
        a.suspendReception()
        assertNull(a.suggestion)
        assertEquals(CbQsoAdmission.IGNORED, a.receive(row("1AT106 -07"), 1, base + 16_000, 1500).admission)
        a.bindReceiver(2, base + 30_000)
        assertEquals(CbQsoAdmission.IGNORED, a.receive(row("1AT106 -07"), 2, base + 16_000, 1500).admission)
        a.selectPeer("161XZ085", 1500, SlotPeriod.ODD, base + 30_000)
        assertFalse(a.sent(ticket, base + 30_000))
        assertEquals(CbQsoAdmission.IGNORED, a.receive(row("1AT106 -07", base + 30_000), 2, base + 46_000, 1500).admission)
        assertNull(a.snapshot.receivedReport)
    }
    @Test fun duplicateAndOutOfOrderSlotsCannotChangeTheProposal() {
        val a = advisor(); tx(a, ManualMessage.REPORT, base - 60_000)
        val latest = row("1AT106 R-07", base + 30_000)
        assertEquals(CbQsoAdmission.ACCEPTED, a.receive(latest, 1, base + 46_000, 1500).admission)
        val before = a.snapshot; val suggestion = a.suggestion
        assertEquals(CbQsoAdmission.DUPLICATE, a.receive(latest, 1, base + 46_000, 1500).admission)
        assertEquals(CbQsoAdmission.IGNORED, a.receive(row("1AT106 -09"), 1, base + 16_000, 1500).admission)
        assertEquals(before, a.snapshot); assertEquals(suggestion, a.suggestion)
    }
    @Test fun integrityAmbiguityMetadataAndTextPayloadMismatchNeverAdvance() {
        val a = advisor(); tx(a, ManualMessage.REPLY)
        val good = row("1AT106 -07")
        val alternatives = listOf(
            good.copy(decode = good.decode.copy(flags = emptySet())),
            good.copy(decode = good.decode.copy(flags = good.decode.flags + RecordedDecodeFlag.AMBIGUOUS_HASH)),
            good.copy(decode = good.decode.copy(frame = good.decode.frame.copy(audioFrequencyHz = Float.NaN))),
            good.copy(decode = good.decode.copy(deltaTimeSeconds = Float.NaN)),
            good.copy(decode = good.decode.copy(message = (CbMessageCodec.pack("1AT106 R-07") as PackResult.Encoded).let { UnpackResult.Decoded(it.message) })),
        )
        for (bad in alternatives) {
            val before = a.snapshot
            assertEquals(CbQsoAdmission.IGNORED, a.receive(bad, 1, base + 16_000, 1500).admission)
            assertEquals(before, a.snapshot)
        }
        assertEquals(CbQsoAdmission.ACCEPTED, a.receive(good, 1, base + 16_000, 1500).admission)
    }
    @Test fun partialStaleAndFutureWindowsCannotAdvance() {
        for ((r, now) in listOf(row("1AT106 -07") to base + 14_999,
            row("1AT106 -07") to base + 30_001,
            row("1AT106 -07", base + 1) to base + 16_000)) {
            val a = advisor(); tx(a, ManualMessage.REPLY)
            assertEquals(CbQsoAdmission.IGNORED, a.receive(r, 1, now, 1500).admission)
            assertNull(a.snapshot.receivedReport)
        }
    }
    @Test fun partnerElsewhereStopsOnlyReplyStageWithinFiftyHz() {
        for ((hz, expected) in listOf(1450f to CbQsoAdmission.STOP, 1449f to CbQsoAdmission.IGNORED, 1550f to CbQsoAdmission.STOP, 1551f to CbQsoAdmission.IGNORED)) {
            val a = advisor(); tx(a, ManualMessage.REPLY)
            assertEquals(expected, a.receive(row("<161XZ085> 26AT101", hz = hz), 1, base + 16_000, 1500).admission)
            if (expected == CbQsoAdmission.STOP) { assertEquals(CbQsoPhase.ABORTED, a.snapshot.phase); assertNull(a.suggestion) }
        }
        val a = advisor(); tx(a, ManualMessage.REPORT)
        assertEquals(CbQsoAdmission.IGNORED, a.receive(row("<161XZ085> 26AT101"), 1, base + 16_000, 1500).admission)
        assertEquals(CbQsoPhase.REPORT_EXCHANGE, a.snapshot.phase)
    }
}
