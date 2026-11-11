package net.vash.ft8cbdroid.reference

import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.core.message.ManualMessage
import net.vash.ft8cbdroid.core.qso.*

/** Domain acceptance scenarios shared by JVM and real Android; no simulated audio/RF evidence. */
object QsoProgressionSafetyChecks {
    fun verify(): Int {
        val local = "1AT106"
        val peer = "26AT101"
        fun session() = CbQsoProgression(StationIdentity(local, "JN45")).also { it.dispatch(CbQsoEvent.SelectPeer(peer)) }
        fun prepare(q: CbQsoProgression, kind: ManualMessage, report: Int? = null): CbQsoTransmission {
            val update = q.dispatch(CbQsoEvent.Prepare(kind, report))
            check(update.disposition == CbQsoDisposition.APPLIED) { update.reason.orEmpty() }
            return checkNotNull(q.snapshot.pending)
        }
        fun sent(q: CbQsoProgression, kind: ManualMessage, report: Int? = null) {
            check(q.dispatch(CbQsoEvent.Sent(prepare(q, kind, report))).disposition == CbQsoDisposition.APPLIED)
        }
        fun rx(kind: CbQsoMessage, report: Int? = null, his: String = peer, to: String = local) =
            CbQsoEvent.Received(CbQsoReception(CbQsoOrigin.SelectedPeerContext(his), to, kind, report))
        fun rejected(q: CbQsoProgression, event: CbQsoEvent) {
            val before = q.snapshot
            check(q.dispatch(event).disposition == CbQsoDisposition.REJECTED)
            check(q.snapshot == before)
        }
        // A failed/cancelled report is not a report sent, and a callback cannot cross exchanges or instances.
        run {
            val q = session()
            val report = prepare(q, ManualMessage.REPORT, -7)
            rejected(q, rx(CbQsoMessage.ROGER, -12))
            q.dispatch(CbQsoEvent.Cancel(report))
            rejected(q, CbQsoEvent.Sent(report))
            rejected(session(), CbQsoEvent.Sent(report))
            q.dispatch(CbQsoEvent.Reset)
            q.dispatch(CbQsoEvent.SelectPeer(peer))
            rejected(q, CbQsoEvent.Sent(report))
            check(q.snapshot.sentReport == null)
        }
        // Peer replacement and abort each invalidate queued work; late incoming finals cannot revive it.
        run {
            val q = session()
            val report = prepare(q, ManualMessage.REPORT, -7)
            q.dispatch(CbQsoEvent.SelectPeer("001AB123"))
            rejected(q, CbQsoEvent.Sent(report))
            q.dispatch(CbQsoEvent.SelectPeer(peer))
            val replacement = prepare(q, ManualMessage.REPORT, -7)
            q.dispatch(CbQsoEvent.Abort)
            rejected(q, CbQsoEvent.Sent(replacement))
            rejected(q, rx(CbQsoMessage.SIGNOFF))
            rejected(q, CbQsoEvent.Prepare(ManualMessage.REPLY))
            check(q.snapshot.phase == CbQsoPhase.ABORTED)
        }
        // Context is not an invented sender, and neither foreign nor early finals establish completion.
        run {
            val q = session()
            rejected(q, rx(CbQsoMessage.REPLY))
            rejected(q, rx(CbQsoMessage.REPORT, -7, his = "161XZ085"))
            rejected(q, rx(CbQsoMessage.REPORT, -7, to = "161XZ085"))
            rejected(q, rx(CbQsoMessage.RR73))
            rejected(q, rx(CbQsoMessage.SIGNOFF))
            rejected(q, rx(CbQsoMessage.REPORT, 100))
            check(q.snapshot.phase == CbQsoPhase.SELECTED)
        }
        // The original report is stable; duplicates never regress acknowledgement or signoff.
        run {
            val q = session()
            sent(q, ManualMessage.REPORT, -7)
            check(q.dispatch(rx(CbQsoMessage.ROGER, -12)).disposition == CbQsoDisposition.APPLIED)
            val acknowledged = q.snapshot
            check(q.dispatch(rx(CbQsoMessage.REPORT, -12)).disposition == CbQsoDisposition.UNCHANGED)
            check(q.snapshot == acknowledged)
            rejected(q, rx(CbQsoMessage.REPORT, -9))
            sent(q, ManualMessage.RR73)
            val retry = prepare(q, ManualMessage.RR73)
            check(q.dispatch(rx(CbQsoMessage.SIGNOFF)).disposition == CbQsoDisposition.APPLIED)
            check(q.snapshot.phase == CbQsoPhase.COMPLETE && q.snapshot.pending == null)
            rejected(q, CbQsoEvent.Sent(retry))
            rejected(q, rx(CbQsoMessage.ROGER, -12))
            check(q.dispatch(CbQsoEvent.Abort).disposition == CbQsoDisposition.UNCHANGED)
            check(q.snapshot.phase == CbQsoPhase.COMPLETE)
        }
        // In the answering role, received RR73 and a queued/cancelled 73 are not locally sent signoff.
        run {
            val q = session()
            check(q.dispatch(rx(CbQsoMessage.REPORT, -7)).disposition == CbQsoDisposition.APPLIED)
            sent(q, ManualMessage.ROGER, -12)
            check(q.dispatch(rx(CbQsoMessage.RR73)).disposition == CbQsoDisposition.APPLIED)
            val final = prepare(q, ManualMessage.SIGNOFF)
            check(q.snapshot.phase == CbQsoPhase.SIGNOFF && !q.snapshot.localFinalSent)
            q.dispatch(CbQsoEvent.Cancel(final))
            rejected(q, CbQsoEvent.Sent(final))
            sent(q, ManualMessage.SIGNOFF)
            check(q.snapshot.phase == CbQsoPhase.COMPLETE)
        }
        return 5
    }
}
