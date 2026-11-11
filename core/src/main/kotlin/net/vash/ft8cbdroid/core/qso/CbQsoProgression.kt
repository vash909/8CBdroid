package net.vash.ft8cbdroid.core.qso

import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.core.message.CbMessageCodec
import net.vash.ft8cbdroid.core.message.DecodedMessage
import net.vash.ft8cbdroid.core.message.ManualMessage
import net.vash.ft8cbdroid.core.message.PackResult
import net.vash.ft8cbdroid.core.message.manualMessages

enum class CbQsoPhase { IDLE, CALLING, SELECTED, REPLYING, REPORT_EXCHANGE, ACKNOWLEDGED, SIGNOFF, COMPLETE, ABORTED }
enum class CbQsoMessage { CQ, REPLY, REPORT, ROGER, RRR, RR73, SIGNOFF }

/** A selected-peer binding is context supplied by an admission layer, never an on-air sender identity. */
sealed interface CbQsoOrigin {
    val callsign: String
    data class NamedSender(override val callsign: String) : CbQsoOrigin
    data class SelectedPeerContext(override val callsign: String) : CbQsoOrigin
}

/** Already-qualified message semantics. Raw decode parsing, hash admission and slot freshness belong to the caller. */
data class CbQsoReception(
    val origin: CbQsoOrigin,
    val recipient: String?,
    val kind: CbQsoMessage,
    val report: Int? = null,
)

/** Opaque, instance-scoped ticket. Only successful output completion may return it in Sent. */
class CbQsoTransmission internal constructor(
    val kind: ManualMessage,
    val report: Int?,
    val text: String,
    val payload: Ft8Payload77,
)

data class CbQsoSnapshot(
    val peer: String? = null,
    val cqSent: Boolean = false,
    val replySent: Boolean = false,
    val replyReceived: Boolean = false,
    val sentReport: Int? = null,
    val receivedReport: Int? = null,
    val localAcknowledgedReport: Boolean = false,
    val peerAcknowledgedReport: Boolean = false,
    val localFinalSent: Boolean = false,
    val peerFinalReceived: Boolean = false,
    val pending: CbQsoTransmission? = null,
    val aborted: Boolean = false,
) {
    /** Protocol completion only: this is neither RF verification nor a durable log entry. */
    val phase: CbQsoPhase get() = when {
        aborted -> CbQsoPhase.ABORTED
        localFinalSent && peerFinalReceived && localAcknowledgedReport && peerAcknowledgedReport &&
            sentReport != null && receivedReport != null -> CbQsoPhase.COMPLETE
        localFinalSent || peerFinalReceived -> CbQsoPhase.SIGNOFF
        localAcknowledgedReport || peerAcknowledgedReport -> CbQsoPhase.ACKNOWLEDGED
        sentReport != null || receivedReport != null -> CbQsoPhase.REPORT_EXCHANGE
        replySent || replyReceived -> CbQsoPhase.REPLYING
        peer != null -> CbQsoPhase.SELECTED
        cqSent -> CbQsoPhase.CALLING
        else -> CbQsoPhase.IDLE
    }
}

sealed interface CbQsoEvent {
    data class SelectPeer(val callsign: String) : CbQsoEvent
    data class Observed(val message: DecodedMessage) : CbQsoEvent
    data class Received(val message: CbQsoReception) : CbQsoEvent
    data class Prepare(val kind: ManualMessage, val report: Int? = null) : CbQsoEvent
    data class Sent(val transmission: CbQsoTransmission) : CbQsoEvent
    data class Cancel(val transmission: CbQsoTransmission) : CbQsoEvent
    data object Abort : CbQsoEvent
    data object Reset : CbQsoEvent
}

enum class CbQsoDisposition { APPLIED, UNCHANGED, OBSERVED, REJECTED }
data class CbQsoUpdate(val snapshot: CbQsoSnapshot, val disposition: CbQsoDisposition, val reason: String? = null)

/**
 * In-memory JVM domain model; confine dispatch to one serialized owner.
 * No timers, I/O, next-message selection, audio execution or automatic peer selection.
 * Reset, abort and peer replacement invalidate outstanding tickets, including late callbacks for the same peer.
 */
class CbQsoProgression(station: StationIdentity) {
    val station = station.normalized().also { require(it.isValid) { "A complete CB station identity is required." } }
    var snapshot = CbQsoSnapshot()
        private set

    fun dispatch(event: CbQsoEvent): CbQsoUpdate {
        val before = snapshot
        fun reject(reason: String) = CbQsoUpdate(snapshot, CbQsoDisposition.REJECTED, reason)
        when (event) {
            is CbQsoEvent.Observed -> return CbQsoUpdate(snapshot, CbQsoDisposition.OBSERVED)
            CbQsoEvent.Reset -> snapshot = CbQsoSnapshot()
            CbQsoEvent.Abort -> if (snapshot.phase != CbQsoPhase.COMPLETE) snapshot = snapshot.copy(aborted = true, pending = null)
            is CbQsoEvent.SelectPeer -> {
                val peer = CbCallsign.normalize(event.callsign)
                if (!CbCallsign.isValid(peer) || peer == station.callsign) return reject("Select a complete CB peer different from your own callsign.")
                if (peer != snapshot.peer || terminal()) {
                    snapshot = CbQsoSnapshot(peer = peer, cqSent = snapshot.peer == null && !terminal() && snapshot.cqSent)
                }
            }
            is CbQsoEvent.Cancel -> {
                if (event.transmission !== snapshot.pending) return reject("Transmission ticket is no longer current.")
                snapshot = snapshot.copy(pending = null)
            }
            is CbQsoEvent.Prepare -> {
                if (terminal()) return reject("The exchange has ended. Start a new exchange explicitly.")
                if (snapshot.pending != null) return reject("Finish or cancel the pending transmission first.")
                val error = sendError(event.kind, event.report)
                if (error != null) return reject(error)
                val text = manualMessages(station, snapshot.peer.orEmpty(), event.report ?: snapshot.sentReport ?: -10)[event.kind]
                    ?: return reject("This message is unavailable for the selected peer.")
                val packed = CbMessageCodec.pack(text)
                if (packed !is PackResult.Encoded || packed.contentChanged) return reject("The complete message cannot be encoded without changes.")
                snapshot = snapshot.copy(pending = CbQsoTransmission(event.kind, event.report, text, packed.payload))
            }
            is CbQsoEvent.Sent -> {
                if (event.transmission !== snapshot.pending) return reject("Transmission ticket is no longer current.")
                val tx = event.transmission
                val s = snapshot.copy(pending = null)
                snapshot = when (tx.kind) {
                    ManualMessage.CQ -> s.copy(cqSent = true)
                    ManualMessage.REPLY -> s.copy(replySent = true)
                    ManualMessage.REPORT -> s.copy(sentReport = tx.report)
                    ManualMessage.ROGER -> s.copy(sentReport = tx.report, localAcknowledgedReport = true)
                    ManualMessage.RR73 -> s.copy(localAcknowledgedReport = true, localFinalSent = true)
                    ManualMessage.SIGNOFF -> s.copy(localFinalSent = true)
                    ManualMessage.CUSTOM -> error("Custom messages cannot create a QSO ticket.")
                }
            }
            is CbQsoEvent.Received -> {
                if (terminal()) return reject("The exchange has ended. Start a new exchange explicitly.")
                val m = event.message
                val error = receiveError(m)
                if (error != null) return reject(error)
                snapshot = when (m.kind) {
                    CbQsoMessage.CQ -> snapshot
                    CbQsoMessage.REPLY -> snapshot.copy(replyReceived = true)
                    CbQsoMessage.REPORT -> snapshot.copy(receivedReport = m.report)
                    CbQsoMessage.ROGER -> snapshot.copy(receivedReport = m.report, peerAcknowledgedReport = true)
                    CbQsoMessage.RRR -> snapshot.copy(peerAcknowledgedReport = true)
                    CbQsoMessage.RR73 -> snapshot.copy(peerAcknowledgedReport = true, peerFinalReceived = true)
                    CbQsoMessage.SIGNOFF -> snapshot.copy(peerFinalReceived = true)
                }
            }
        }
        if (snapshot.phase == CbQsoPhase.COMPLETE) snapshot = snapshot.copy(pending = null)
        return CbQsoUpdate(snapshot, if (snapshot == before) CbQsoDisposition.UNCHANGED else CbQsoDisposition.APPLIED)
    }

    private fun terminal() = snapshot.phase in setOf(CbQsoPhase.COMPLETE, CbQsoPhase.ABORTED)
    private fun reportsKnown() = snapshot.sentReport != null && snapshot.receivedReport != null

    private fun sendError(kind: ManualMessage, report: Int?): String? {
        val reportMessage = kind == ManualMessage.REPORT || kind == ManualMessage.ROGER
        if (reportMessage && (report == null || report !in -50..99) || !reportMessage && report != null)
            return "Supply a report between -50 and +99 dB only for report messages."
        if (reportMessage && snapshot.sentReport != null && report != snapshot.sentReport)
            return "The sent report is fixed for this exchange."
        return when (kind) {
            ManualMessage.CUSTOM -> "Custom text does not establish QSO progression."
            ManualMessage.CQ -> if (snapshot.peer != null) "Reset the exchange before calling CQ." else null
            else -> when {
                snapshot.peer == null -> "Select a peer first."
                kind == ManualMessage.REPLY && (snapshot.sentReport != null || snapshot.receivedReport != null) -> "The report exchange has already started."
                kind == ManualMessage.REPORT && (snapshot.localAcknowledgedReport || snapshot.localFinalSent) -> "The report has already been acknowledged."
                kind == ManualMessage.ROGER && (snapshot.receivedReport == null || snapshot.localFinalSent) -> "A received report is required before sending an R-report."
                kind == ManualMessage.RR73 && (!reportsKnown() || !snapshot.peerAcknowledgedReport) -> "Both reports and the peer's acknowledgement are required before RR73."
                kind == ManualMessage.SIGNOFF && (!reportsKnown() || !snapshot.peerFinalReceived || !snapshot.localAcknowledgedReport || !snapshot.peerAcknowledgedReport) -> "The acknowledged report exchange and peer signoff are required before 73."
                else -> null
            }
        }
    }

    private fun receiveError(message: CbQsoReception): String? {
        val origin = CbCallsign.normalize(message.origin.callsign)
        if (snapshot.peer == null || origin != snapshot.peer || !CbCallsign.isValid(origin) || origin == station.callsign)
            return "The message does not belong to the selected peer."
        if (message.kind == CbQsoMessage.CQ) {
            if (message.recipient != null || message.origin !is CbQsoOrigin.NamedSender || message.report != null)
                return "CQ requires a named sender and no recipient or report."
            return null
        }
        if (message.recipient?.let(CbCallsign::normalize) != station.callsign) return "The message is addressed to another station."
        if (message.kind == CbQsoMessage.REPLY && message.origin !is CbQsoOrigin.NamedSender)
            return "A reply requires a named sender."
        val reportMessage = message.kind == CbQsoMessage.REPORT || message.kind == CbQsoMessage.ROGER
        if (reportMessage && (message.report == null || message.report !in -50..99) || !reportMessage && message.report != null)
            return "The message has an invalid report."
        if (reportMessage && snapshot.receivedReport != null && snapshot.receivedReport != message.report)
            return "A later report cannot replace the received report."
        return when {
            message.kind == CbQsoMessage.REPLY && (snapshot.sentReport != null || snapshot.receivedReport != null) -> "A late reply cannot restart the exchange."
            message.kind == CbQsoMessage.ROGER && snapshot.sentReport == null -> "An R-report cannot acknowledge a report that was not sent."
            message.kind == CbQsoMessage.RRR && (!reportsKnown() || !snapshot.localAcknowledgedReport) -> "Reports must be exchanged and locally acknowledged before RRR."
            message.kind == CbQsoMessage.RR73 && (!reportsKnown() || !snapshot.localAcknowledgedReport) -> "Reports must be exchanged and locally acknowledged before RR73."
            message.kind == CbQsoMessage.SIGNOFF && (!reportsKnown() || !snapshot.localAcknowledgedReport || !snapshot.peerAcknowledgedReport) -> "An acknowledged report exchange is required before 73."
            else -> null
        }
    }
}
