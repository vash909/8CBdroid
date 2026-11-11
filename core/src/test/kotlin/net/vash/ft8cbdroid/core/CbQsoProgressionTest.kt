package net.vash.ft8cbdroid.core

import net.vash.ft8cbdroid.core.message.CbMessageCodec
import net.vash.ft8cbdroid.core.message.ManualMessage
import net.vash.ft8cbdroid.core.message.PackResult
import net.vash.ft8cbdroid.core.qso.*
import net.vash.ft8cbdroid.reference.QsoProgressionReferenceChecks
import net.vash.ft8cbdroid.reference.QsoProgressionSafetyChecks
import org.junit.Assert.*
import org.junit.Test

class CbQsoProgressionTest {
    private val local = "1AT106"
    private val peer = "26AT101"
    private fun session(own: String = local, his: String = peer) = CbQsoProgression(StationIdentity(own, "JN45")).also {
        applied(it.dispatch(CbQsoEvent.SelectPeer(his)))
    }
    private fun applied(update: CbQsoUpdate) = assertEquals(update.reason, CbQsoDisposition.APPLIED, update.disposition)
    private fun prepare(qso: CbQsoProgression, kind: ManualMessage, report: Int? = null): CbQsoTransmission {
        applied(qso.dispatch(CbQsoEvent.Prepare(kind, report)))
        return requireNotNull(qso.snapshot.pending)
    }
    private fun sent(qso: CbQsoProgression, kind: ManualMessage, report: Int? = null) =
        applied(qso.dispatch(CbQsoEvent.Sent(prepare(qso, kind, report))))
    private fun reception(qso: CbQsoProgression, kind: CbQsoMessage, report: Int? = null,
        origin: CbQsoOrigin = CbQsoOrigin.SelectedPeerContext(requireNotNull(qso.snapshot.peer)),
        recipient: String? = qso.station.callsign) = CbQsoEvent.Received(CbQsoReception(origin, recipient, kind, report))
    private fun received(qso: CbQsoProgression, kind: CbQsoMessage, report: Int? = null) = applied(qso.dispatch(reception(qso, kind, report)))
    private fun rejected(qso: CbQsoProgression, event: CbQsoEvent) {
        val before = qso.snapshot
        val update = qso.dispatch(event)
        assertEquals(CbQsoDisposition.REJECTED, update.disposition)
        assertFalse(update.reason.isNullOrBlank())
        assertEquals(before, qso.snapshot)
    }
    private fun initiatorAtAcknowledgement(): CbQsoProgression = session().also {
        sent(it, ManualMessage.REPORT, -7)
        received(it, CbQsoMessage.ROGER, -12)
    }

    @Test fun pinnedDesktopRelaySeparatesObservedQueuedAndSentEvents() {
        val lines = javaClass.getResourceAsStream("/wsjtcb/qso-progression.tsv")!!.bufferedReader().use { it.readLines() }
        assertEquals(6, QsoProgressionReferenceChecks.verify(lines))
    }

    @Test fun sharedDeviceSafetyScenarios() { assertEquals(5, QsoProgressionSafetyChecks.verify()) }

    @Test fun callingNeedsSuccessfulCompletionAndExplicitPeerSelection() {
        val qso = CbQsoProgression(StationIdentity(local, "JN45"))
        val cq = prepare(qso, ManualMessage.CQ)
        assertEquals(CbQsoPhase.IDLE, qso.snapshot.phase)
        applied(qso.dispatch(CbQsoEvent.Cancel(cq)))
        rejected(qso, CbQsoEvent.Sent(cq))
        assertFalse(qso.snapshot.cqSent)
        sent(qso, ManualMessage.CQ)
        assertEquals(CbQsoPhase.CALLING, qso.snapshot.phase)
        rejected(qso, reception(qso, CbQsoMessage.REPLY, origin = CbQsoOrigin.NamedSender(peer)))
        applied(qso.dispatch(CbQsoEvent.SelectPeer(peer)))
        applied(qso.dispatch(reception(qso, CbQsoMessage.REPLY, origin = CbQsoOrigin.NamedSender(peer))))
        assertEquals(CbQsoPhase.REPLYING, qso.snapshot.phase)
        assertTrue(qso.snapshot.cqSent)
    }

    @Test fun bothRolesCompleteWithNumericAndSlashCbCalls() {
        for ((own, his) in listOf(local to peer, peer to local, "001AB123" to "161XZ085", "1AT1000" to "999ZZ/ZZ", "999ZZ/ZZ" to "001AB123")) {
            val caller = session(own, his)
            val answer = session(his, own)
            sent(answer, ManualMessage.REPLY)
            applied(caller.dispatch(reception(caller, CbQsoMessage.REPLY, origin = CbQsoOrigin.NamedSender(his))))
            sent(caller, ManualMessage.REPORT, -7)
            received(answer, CbQsoMessage.REPORT, -7)
            sent(answer, ManualMessage.ROGER, -12)
            received(caller, CbQsoMessage.ROGER, -12)
            val final = prepare(caller, ManualMessage.RR73)
            assertEquals(CbQsoPhase.ACKNOWLEDGED, caller.snapshot.phase)
            applied(caller.dispatch(CbQsoEvent.Sent(final)))
            received(answer, CbQsoMessage.RR73)
            assertEquals(CbQsoPhase.SIGNOFF, answer.snapshot.phase)
            assertFalse(answer.snapshot.localFinalSent)
            sent(answer, ManualMessage.SIGNOFF)
            assertEquals(CbQsoPhase.COMPLETE, answer.snapshot.phase)
            received(caller, CbQsoMessage.SIGNOFF)
            assertEquals(CbQsoPhase.COMPLETE, caller.snapshot.phase)
            assertEquals(-7, caller.snapshot.sentReport)
            assertEquals(-12, caller.snapshot.receivedReport)
            assertEquals(-12, answer.snapshot.sentReport)
            assertEquals(-7, answer.snapshot.receivedReport)
        }
    }

    @Test fun receivedFinalAloneAndQueuedSignoffNeverComplete() {
        val qso = session()
        for (kind in listOf(CbQsoMessage.ROGER, CbQsoMessage.RRR, CbQsoMessage.RR73, CbQsoMessage.SIGNOFF))
            rejected(qso, reception(qso, kind, if (kind == CbQsoMessage.ROGER) -7 else null))
        received(qso, CbQsoMessage.REPORT, -7)
        rejected(qso, reception(qso, CbQsoMessage.RR73))
        sent(qso, ManualMessage.ROGER, -12)
        received(qso, CbQsoMessage.RR73)
        val before = qso.snapshot
        val final = prepare(qso, ManualMessage.SIGNOFF)
        assertEquals(CbQsoPhase.SIGNOFF, qso.snapshot.phase)
        applied(qso.dispatch(CbQsoEvent.Cancel(final)))
        assertEquals(before, qso.snapshot)
        rejected(qso, CbQsoEvent.Sent(final))
        sent(qso, ManualMessage.SIGNOFF)
        assertEquals(CbQsoPhase.COMPLETE, qso.snapshot.phase)
    }

    @Test fun pendingOrFailedReportCannotBeAcknowledgedAsSent() {
        val qso = session()
        val report = prepare(qso, ManualMessage.REPORT, -7)
        rejected(qso, reception(qso, CbQsoMessage.ROGER, -12))
        assertNull(qso.snapshot.sentReport)
        applied(qso.dispatch(CbQsoEvent.Cancel(report)))
        rejected(qso, CbQsoEvent.Sent(report))
        rejected(qso, reception(qso, CbQsoMessage.ROGER, -12))
        sent(qso, ManualMessage.REPORT, -7)
        received(qso, CbQsoMessage.ROGER, -12)
    }

    @Test fun observedValidPayloadDoesNotSelectPeerOrAdvanceReports() {
        val qso = session()
        sent(qso, ManualMessage.REPORT, -7)
        for (text in listOf("CQ $peer", "<$local> $peer", "$local R-12", "$local RR73", "$local 73")) {
            val wire = CbMessageCodec.pack(text) as PackResult.Encoded
            val before = qso.snapshot
            assertEquals(CbQsoDisposition.OBSERVED, qso.dispatch(CbQsoEvent.Observed(wire.message)).disposition)
            assertEquals(before, qso.snapshot)
        }
    }

    @Test fun wrongPeerSelfUnknownAndWrongDestinationAreRejected() {
        val qso = initiatorAtAcknowledgement()
        for (origin in listOf(CbQsoOrigin.NamedSender("161XZ085"), CbQsoOrigin.SelectedPeerContext("161XZ085"),
            CbQsoOrigin.NamedSender(local), CbQsoOrigin.NamedSender("<...>"), CbQsoOrigin.SelectedPeerContext("K1ABC")))
            rejected(qso, reception(qso, CbQsoMessage.ROGER, -12, origin))
        for (destination in listOf(null, peer, "161XZ085", "<...>"))
            rejected(qso, reception(qso, CbQsoMessage.SIGNOFF, recipient = destination))
        for (call in listOf(local, " <...> ", "K1ABC", "")) rejected(qso, CbQsoEvent.SelectPeer(call))
    }

    @Test fun contextCannotInventNamedSenderForCqOrReply() {
        val qso = session()
        rejected(qso, reception(qso, CbQsoMessage.CQ, recipient = null))
        rejected(qso, reception(qso, CbQsoMessage.REPLY))
        val before = qso.snapshot
        assertEquals(CbQsoDisposition.UNCHANGED, qso.dispatch(reception(qso, CbQsoMessage.CQ,
            origin = CbQsoOrigin.NamedSender(peer), recipient = null)).disposition)
        assertEquals(before, qso.snapshot)
        applied(qso.dispatch(reception(qso, CbQsoMessage.REPLY, origin = CbQsoOrigin.NamedSender(peer))))
        assertEquals(CbQsoPhase.REPLYING, qso.snapshot.phase)
    }

    @Test fun duplicateAndOlderReportsCannotRegressAcknowledgementOrReplaceReport() {
        val qso = initiatorAtAcknowledgement()
        val before = qso.snapshot
        assertEquals(CbQsoDisposition.UNCHANGED, qso.dispatch(reception(qso, CbQsoMessage.REPORT, -12)).disposition)
        assertEquals(CbQsoDisposition.UNCHANGED, qso.dispatch(reception(qso, CbQsoMessage.ROGER, -12)).disposition)
        assertEquals(before, qso.snapshot)
        rejected(qso, reception(qso, CbQsoMessage.REPORT, -9))
        rejected(qso, reception(qso, CbQsoMessage.REPLY, origin = CbQsoOrigin.NamedSender(peer)))
        rejected(qso, CbQsoEvent.Prepare(ManualMessage.REPORT, -9))
        sent(qso, ManualMessage.RR73)
        val signoff = qso.snapshot
        assertEquals(CbQsoDisposition.UNCHANGED, qso.dispatch(reception(qso, CbQsoMessage.REPORT, -12)).disposition)
        assertEquals(signoff, qso.snapshot)
    }

    @Test fun reportsHaveCbBoundsAndCannotBeAttachedToUnrelatedKinds() {
        for (value in listOf(-50, 0, 49, 50, 99)) {
            val qso = session()
            sent(qso, ManualMessage.REPORT, value)
            received(qso, CbQsoMessage.ROGER, value)
            assertEquals(value, qso.snapshot.sentReport)
            assertEquals(value, qso.snapshot.receivedReport)
        }
        for (value in listOf(null, -51, 100, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val qso = session()
            rejected(qso, CbQsoEvent.Prepare(ManualMessage.REPORT, value))
            rejected(qso, reception(qso, CbQsoMessage.REPORT, value))
        }
        val qso = session()
        for (kind in listOf(ManualMessage.REPLY, ManualMessage.RR73, ManualMessage.SIGNOFF))
            rejected(qso, CbQsoEvent.Prepare(kind, -7))
        rejected(qso, reception(qso, CbQsoMessage.REPLY, -7, CbQsoOrigin.NamedSender(peer)))
        rejected(qso, CbQsoEvent.Prepare(ManualMessage.CUSTOM))
    }

    @Test fun lossyTemplatesCannotCreateATransmissionTicket() {
        // The desktop renderer treats this short CB call as standard, but type 4 drops its grid/report.
        val qso = session("1A1", peer)
        rejected(qso, CbQsoEvent.Prepare(ManualMessage.REPLY))
        rejected(qso, CbQsoEvent.Prepare(ManualMessage.REPORT, -7))
        assertNull(qso.snapshot.pending)
        assertNull(qso.snapshot.sentReport)
    }

    @Test fun ticketsAreFrozenAndScopedToOneExchangeAndOneInstance() {
        val qso = session()
        val other = session()
        val ticket = prepare(qso, ManualMessage.REPORT, -7)
        rejected(qso, CbQsoEvent.Prepare(ManualMessage.REPORT, -12))
        rejected(other, CbQsoEvent.Sent(ticket))
        rejected(other, CbQsoEvent.Cancel(ticket))
        assertEquals("$peer -07", ticket.text)
        applied(qso.dispatch(CbQsoEvent.SelectPeer("161XZ085")))
        assertNull(qso.snapshot.pending)
        rejected(qso, CbQsoEvent.Sent(ticket))
        applied(qso.dispatch(CbQsoEvent.SelectPeer(peer)))
        rejected(qso, CbQsoEvent.Sent(ticket))
        val current = prepare(qso, ManualMessage.REPORT, -7)
        assertNotSame(ticket, current)
        rejected(qso, CbQsoEvent.Sent(ticket))
        applied(qso.dispatch(CbQsoEvent.Sent(current)))
        rejected(qso, CbQsoEvent.Sent(current))
    }

    @Test fun abortInvalidatesPendingOutputAndRequiresExplicitNewExchange() {
        val qso = initiatorAtAcknowledgement()
        val ticket = prepare(qso, ManualMessage.RR73)
        applied(qso.dispatch(CbQsoEvent.Abort))
        assertEquals(CbQsoPhase.ABORTED, qso.snapshot.phase)
        assertNull(qso.snapshot.pending)
        rejected(qso, CbQsoEvent.Sent(ticket))
        rejected(qso, reception(qso, CbQsoMessage.SIGNOFF))
        rejected(qso, CbQsoEvent.Prepare(ManualMessage.RR73))
        assertEquals(CbQsoDisposition.UNCHANGED, qso.dispatch(CbQsoEvent.Abort).disposition)
        applied(qso.dispatch(CbQsoEvent.SelectPeer(peer)))
        assertEquals(CbQsoPhase.SELECTED, qso.snapshot.phase)
        assertNull(qso.snapshot.sentReport)
        rejected(qso, CbQsoEvent.Sent(ticket))
    }

    @Test fun resetAndRecreationDoNotRestoreArmedWorkOrOldCompletionTickets() {
        val qso = session()
        val ticket = prepare(qso, ManualMessage.REPORT, -7)
        applied(qso.dispatch(CbQsoEvent.Reset))
        assertEquals(CbQsoPhase.IDLE, qso.snapshot.phase)
        assertNull(qso.snapshot.peer)
        rejected(qso, CbQsoEvent.Sent(ticket))
        applied(qso.dispatch(CbQsoEvent.SelectPeer(peer)))
        rejected(qso, CbQsoEvent.Sent(ticket))
        rejected(session(), CbQsoEvent.Sent(ticket))
    }

    @Test fun rrrAcknowledgesButIsNotSignoff() {
        val qso = session()
        received(qso, CbQsoMessage.REPORT, -7)
        sent(qso, ManualMessage.ROGER, -12)
        received(qso, CbQsoMessage.RRR)
        assertEquals(CbQsoPhase.ACKNOWLEDGED, qso.snapshot.phase)
        assertFalse(qso.snapshot.peerFinalReceived)
        rejected(qso, CbQsoEvent.Prepare(ManualMessage.SIGNOFF))
        sent(qso, ManualMessage.RR73)
        received(qso, CbQsoMessage.SIGNOFF)
        assertEquals(CbQsoPhase.COMPLETE, qso.snapshot.phase)
    }

    @Test fun completedExchangeIgnoresLateMessagesAndInvalidatesQueuedRetry() {
        val qso = initiatorAtAcknowledgement()
        sent(qso, ManualMessage.RR73)
        val retry = prepare(qso, ManualMessage.RR73)
        received(qso, CbQsoMessage.SIGNOFF)
        assertEquals(CbQsoPhase.COMPLETE, qso.snapshot.phase)
        assertNull(qso.snapshot.pending)
        rejected(qso, CbQsoEvent.Sent(retry))
        for (kind in listOf(CbQsoMessage.REPORT, CbQsoMessage.ROGER, CbQsoMessage.RR73, CbQsoMessage.SIGNOFF))
            rejected(qso, reception(qso, kind, if (kind in setOf(CbQsoMessage.REPORT, CbQsoMessage.ROGER)) -12 else null))
        rejected(qso, CbQsoEvent.Prepare(ManualMessage.SIGNOFF))
        applied(qso.dispatch(CbQsoEvent.Reset))
        assertEquals(CbQsoPhase.IDLE, qso.snapshot.phase)
    }

    @Test fun stationAndPeerNormalizeButCannotUseAmateurOrUnresolvedIdentities() {
        val qso = CbQsoProgression(StationIdentity(" 1at106 ", "jn45"))
        applied(qso.dispatch(CbQsoEvent.SelectPeer(" 26at101 ")))
        assertEquals(local, qso.station.callsign)
        assertEquals(peer, qso.snapshot.peer)
        for (station in listOf(StationIdentity("K1ABC", "JN45"), StationIdentity("<...>", "JN45"), StationIdentity(local, "")))
            assertTrue(runCatching { CbQsoProgression(station) }.isFailure)
    }
}
