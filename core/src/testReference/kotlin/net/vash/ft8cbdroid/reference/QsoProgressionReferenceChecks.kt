package net.vash.ft8cbdroid.reference

import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.core.message.CbMessageCodec
import net.vash.ft8cbdroid.core.message.ManualMessage
import net.vash.ft8cbdroid.core.message.PackResult
import net.vash.ft8cbdroid.core.qso.*

/** Shared JVM/phone assertions. Wire expectations are independent; phase/completion rules are Android policy. */
object QsoProgressionReferenceChecks {
    fun verify(lines: List<String>): Int {
        val local = CbQsoProgression(StationIdentity("1AT106", "JN45"))
        val remote = CbQsoProgression(StationIdentity("26AT101", "IO91"))
        val rows = lines.filter { it.isNotEmpty() && !it.startsWith('#') }
        check(rows.size == 6)
        for (row in rows) {
            val f = row.split('\t')
            val index = f[0].toInt()
            val sender = if (f[1] == "local") local else remote
            val receiver = if (f[1] == "local") remote else local
            val text = f[3].chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            val kind = CbQsoMessage.valueOf(f[2])
            val wire = CbMessageCodec.pack(text) as PackResult.Encoded
            check(wire.payload.toBitString() == f[4]) { "Desktop relay payload $index" }
            val observed = receiver.snapshot
            check(receiver.dispatch(CbQsoEvent.Observed(wire.message)).disposition == CbQsoDisposition.OBSERVED)
            check(receiver.snapshot == observed) { "Observation advanced QSO $index" }
            check(f[7] == if (index == 0) "CQ" else receiver.station.callsign) { "Desktop destination $index" }
            val parserSender = when (index) {
                0, 1 -> sender.station.callsign
                2 -> ""
                3 -> "R"
                4 -> "RR73"
                else -> "73"
            }
            check(f[8] == parserSender.ifEmpty { "-" } && f[9] == "-") { "Desktop parser fields $index" }
            // In the long-call flow these parser tokens are not a complete sender identity.
            val report = if (kind in setOf(CbQsoMessage.REPORT, CbQsoMessage.ROGER)) -12 else null
            val before = sender.snapshot
            val prepared = sender.dispatch(CbQsoEvent.Prepare(ManualMessage.valueOf(f[2]), report))
            check(prepared.disposition == CbQsoDisposition.APPLIED) { "Prepare $index: ${prepared.reason}" }
            val ticket = checkNotNull(sender.snapshot.pending)
            check(ticket.text == text && ticket.payload == wire.payload) { "Desktop relay TX $index" }
            check(sender.snapshot.copy(pending = null) == before) { "Queued text counted as sent $index" }
            check(sender.dispatch(CbQsoEvent.Sent(ticket)).disposition == CbQsoDisposition.APPLIED)
            if (index < 2) receiver.dispatch(CbQsoEvent.SelectPeer(sender.station.callsign)).also {
                check(it.disposition == CbQsoDisposition.APPLIED)
            }
            val origin = if (index < 2) CbQsoOrigin.NamedSender(sender.station.callsign)
                else CbQsoOrigin.SelectedPeerContext(sender.station.callsign)
            val received = CbQsoReception(origin, if (index == 0) null else receiver.station.callsign, kind, report)
            val update = receiver.dispatch(CbQsoEvent.Received(received))
            check(update.disposition != CbQsoDisposition.REJECTED) { "Receive $index: ${update.reason}" }
            check(local.snapshot.phase.name == f[5] && remote.snapshot.phase.name == f[6]) { "Domain phase $index" }
        }
        check(local.snapshot.sentReport == -12 && local.snapshot.receivedReport == -12)
        check(remote.snapshot.sentReport == -12 && remote.snapshot.receivedReport == -12)
        for (session in listOf(local, remote)) {
            val completed = session.snapshot
            val late = CbQsoReception(CbQsoOrigin.SelectedPeerContext(checkNotNull(completed.peer)),
                session.station.callsign, CbQsoMessage.REPORT, -7)
            check(session.dispatch(CbQsoEvent.Received(late)).disposition == CbQsoDisposition.REJECTED)
            check(session.dispatch(CbQsoEvent.Prepare(ManualMessage.SIGNOFF)).disposition == CbQsoDisposition.REJECTED)
            check(session.snapshot == completed)
        }
        return rows.size
    }
}
