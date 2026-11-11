package net.vash.ft8cbdroid.core.qso

import kotlin.math.abs
import kotlin.math.roundToInt
import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.core.audio.LiveDecodeRow
import net.vash.ft8cbdroid.core.audio.SlotPeriod
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.recording.RecordedDecodeFlag

data class CbQsoSuggestion(val peer: String, val kind: ManualMessage, val text: String,
    val report: Int, val frequencyHz: Int, val period: SlotPeriod)
enum class CbQsoAdmission { ACCEPTED, IGNORED, DUPLICATE, STOP }
data class CbQsoDecision(val admission: CbQsoAdmission, val reason: String)

/** Serialized local-decode admission and message advice; never schedules output or arms TX. */
class CbQsoAdvisor(station: StationIdentity, report: Int = -7) {
    private var model = CbQsoProgression(station)
    val snapshot get() = model.snapshot
    private var ownReport = report.also { require(it in -50..99) }
    private var run: Long? = null
    private var runFloor = Long.MAX_VALUE
    private var exchangeFloor = Long.MIN_VALUE
    private var lastAcceptedSlot = Long.MIN_VALUE
    private var peerHz = 1500
    private var period = SlotPeriod.EVEN
    private val seen = linkedSetOf<Pair<Long, net.vash.ft8cbdroid.core.Ft8Payload77>>()

    fun bindReceiver(id: Long, firstSlotUtcMillis: Long) {
        require(id > 0 && firstSlotUtcMillis % 15_000 == 0L)
        if (run == id) return
        run = id; runFloor = firstSlotUtcMillis; seen.clear()
    }
    fun suspendReception() { run = null; runFloor = Long.MAX_VALUE; seen.clear() }
    fun report(value: Int) { require(value in -50..99); ownReport = value }
    fun selectPeer(call: String, frequencyHz: Int, txPeriod: SlotPeriod, firstSlotUtcMillis: Long): Boolean {
        if (!CbCallsign.isValid(call) || CbCallsign.normalize(call) == model.station.callsign || frequencyHz !in 200..2900) return false
        model.dispatch(CbQsoEvent.Reset)
        model.dispatch(CbQsoEvent.SelectPeer(call))
        peerHz = frequencyHz; period = txPeriod; exchangeFloor = firstSlotUtcMillis; lastAcceptedSlot = Long.MIN_VALUE
        seen.clear()
        return true
    }
    fun abort() { model.dispatch(CbQsoEvent.Abort); suspendReception() }
    fun reset() { model.dispatch(CbQsoEvent.Reset); exchangeFloor = Long.MIN_VALUE; lastAcceptedSlot = Long.MIN_VALUE; seen.clear() }
    fun cancelPending() { snapshot.pending?.let { model.dispatch(CbQsoEvent.Cancel(it)) } }

    /** A manual send may be outside this model; custom/lossy/out-of-order output never fabricates QSO facts. */
    fun prepare(kind: ManualMessage, report: Int?, text: String, payload: net.vash.ft8cbdroid.core.Ft8Payload77): CbQsoTransmission? {
        cancelPending()
        if (kind == ManualMessage.CQ) {
            val expected = CbMessageCodec.pack(manualMessages(model.station, "", ownReport).getValue(kind))
            if (expected !is PackResult.Encoded || expected.contentChanged || expected.transmittedText != text || expected.payload != payload) return null
            reset()
        }
        val update = model.dispatch(CbQsoEvent.Prepare(kind, report))
        if (update.disposition == CbQsoDisposition.REJECTED) return null
        val ticket = snapshot.pending ?: return null
        if (ticket.text != text || ticket.payload != payload) { cancelPending(); return null }
        return ticket
    }
    fun sent(ticket: CbQsoTransmission, slotStartUtcMillis: Long): Boolean {
        require(slotStartUtcMillis % 15_000 == 0L)
        val update = model.dispatch(CbQsoEvent.Sent(ticket))
        if (update.disposition != CbQsoDisposition.APPLIED) return false
        exchangeFloor = maxOf(exchangeFloor, slotStartUtcMillis + 15_000)
        return true
    }

    val suggestion: CbQsoSuggestion? get() {
        if (run == null || snapshot.phase in setOf(CbQsoPhase.COMPLETE, CbQsoPhase.ABORTED)) return null
        val s = snapshot
        val peer = s.peer ?: return null
        val kind = when {
            s.peerFinalReceived || s.peerRogerReceived -> ManualMessage.SIGNOFF
            s.peerAcknowledgedReport -> ManualMessage.RR73
            s.localAcknowledgedReport -> ManualMessage.ROGER
            s.receivedReport != null -> ManualMessage.ROGER
            s.replyReceived || s.sentReport != null -> ManualMessage.REPORT
            else -> ManualMessage.REPLY
        }
        val report = s.sentReport ?: ownReport
        val text = manualMessages(model.station, peer, report)[kind] ?: return null
        val packed = CbMessageCodec.pack(text)
        if (packed !is PackResult.Encoded || packed.contentChanged) return null
        return CbQsoSuggestion(peer, kind, text, report, peerHz, period)
    }

    fun receive(row: LiveDecodeRow, receiverId: Long, nowUtcMillis: Long, txFrequencyHz: Int): CbQsoDecision {
        fun ignored(reason: String) = CbQsoDecision(CbQsoAdmission.IGNORED, reason)
        if (run != receiverId || run == null) return ignored("Reception belongs to another receiver session.")
        if (snapshot.phase in setOf(CbQsoPhase.COMPLETE, CbQsoPhase.ABORTED)) return ignored("The exchange has ended.")
        val d = row.decode
        val slot = d.slotStartUtc.toEpochMilli()
        if (row.capturedSamples !in setOf(168_000, 180_000)) return ignored("Unsupported receive window.")
        val windowMillis = row.capturedSamples / 12L
        if (d.slotStartUtc.nano != 0 || slot % 15_000 != 0L || slot < maxOf(runFloor, exchangeFloor) ||
            nowUtcMillis < slot + windowMillis || nowUtcMillis - slot - windowMillis > 15_000)
            return ignored("The decode is outside the current complete receive window.")
        if (RecordedDecodeFlag.CRC_LDPC_VALID !in d.flags || d.flags.any { it in setOf(
                RecordedDecodeFlag.UNKNOWN_HASH, RecordedDecodeFlag.AMBIGUOUS_HASH, RecordedDecodeFlag.UNSUPPORTED_MESSAGE) })
            return ignored("A CRC/LDPC-valid message with resolved identities is required.")
        val hz = d.frame.audioFrequencyHz
        if (!hz.isFinite() || hz !in 200f..2900f || !d.deltaTimeSeconds.isFinite() || txFrequencyHz !in 200..2900)
            return ignored("The decode has invalid signal metadata.")
        val message = (d.message as? UnpackResult.Decoded)?.message ?: return ignored("Unsupported decoded message.")
        if (message.parts.any { it is MessagePart.UnresolvedCall || it is MessagePart.AmbiguousCall }) return ignored("Unresolved identity.")
        val encoded = CbMessageCodec.pack(message.text)
        if (encoded !is PackResult.Encoded || encoded.contentChanged || encoded.payload != d.frame.payload)
            return ignored("Decoded text does not match a complete supported wire message.")
        val key = slot to d.frame.payload
        if (!seen.add(key)) return CbQsoDecision(CbQsoAdmission.DUPLICATE, "This payload was already considered in this slot.")
        while (seen.size > 120) seen.remove(seen.first())
        fun call(part: MessagePart?) = when (part) {
            is MessagePart.Text -> part.value.takeIf(CbCallsign::isValid)
            is MessagePart.ResolvedCall -> part.callsign.takeIf(CbCallsign::isValid)
            else -> null
        }
        val words = message.text.split(' ')
        val parts = if (message.format == MessageFormat.FREE_TEXT) words.map(MessagePart::Text) else message.parts
        val own = model.station.callsign
        val first = call(parts.getOrNull(0)); val second = call(parts.getOrNull(1))
        val token: String?
        val sender: String?
        val recipient: String?
        if (message.format == MessageFormat.NONSTANDARD_CALL && parts.getOrNull(0) is MessagePart.Text &&
            first != null && parts.getOrNull(1) is MessagePart.ResolvedCall) {
            // Type 4 trailing hash: full call is the peer; the hash is our destination.
            sender = first; recipient = second; token = words.getOrNull(2)
        } else {
            recipient = first; sender = second; token = words.getOrNull(2)
        }
        val cq = words.firstOrNull() == "CQ" && words.size in 2..3 && second != null &&
            (words.size == 2 || words[2].matches(Regex("[A-R]{2}[0-9]{2}")))
        if (cq) return ignored("CQ is observed; select its peer explicitly to answer.")
        if (sender == own) return ignored("An own-call echo cannot select or advance a peer.")
        if (sender != null && sender == snapshot.peer && recipient != null && recipient != own &&
            snapshot.replySent && snapshot.sentReport == null && abs(hz - txFrequencyHz) <= 50f) {
            abort()
            return CbQsoDecision(CbQsoAdmission.STOP, "Selected peer is replying to another station. TX disabled; start a new QSO explicitly.")
        }
        if (slot <= lastAcceptedSlot) return ignored("An older or already accepted receive slot cannot advance the exchange.")
        val named = sender != null && recipient == own
        val kind: CbQsoMessage
        val origin: CbQsoOrigin
        val report: Int?
        if (named) {
            if (words.size !in 2..3) return ignored("Unsupported directed exchange.")
            if (snapshot.peer == null && snapshot.cqSent && token == null) {
                model.dispatch(CbQsoEvent.SelectPeer(requireNotNull(sender)))
                ownReport = d.frame.estimatedSnrDb?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(-50, 99) ?: ownReport
            }
            if (sender != snapshot.peer) return ignored("The named sender is not the selected peer.")
            origin = CbQsoOrigin.NamedSender(requireNotNull(sender))
        } else {
            if (words.size != 2 || first != own || second != null) return ignored("The message is not addressed to this station and peer.")
            if (snapshot.peer == null || !(snapshot.replySent || snapshot.sentReport != null))
                return ignored("A destination-only message needs a selected peer and confirmed directed output.")
            if (minOf(abs(hz - peerHz), abs(hz - txFrequencyHz)) > 25f)
                return ignored("A destination-only message is outside the selected peer's frequency context.")
            origin = CbQsoOrigin.SelectedPeerContext(requireNotNull(snapshot.peer))
        }
        val suffix = if (named) token else words[1]
        kind = when {
            suffix == "RRR" -> CbQsoMessage.RRR
            suffix == "RR73" -> CbQsoMessage.RR73
            suffix == "73" -> CbQsoMessage.SIGNOFF
            suffix == null || suffix.matches(Regex("[A-R]{2}[0-9]{2}")) -> CbQsoMessage.REPLY
            suffix.matches(Regex("[+-][0-9]{2}")) -> CbQsoMessage.REPORT
            suffix.matches(Regex("R[+-][0-9]{2}")) -> CbQsoMessage.ROGER
            else -> return ignored("Unsupported report or final message.")
        }
        report = if (kind in setOf(CbQsoMessage.REPORT, CbQsoMessage.ROGER)) suffix?.removePrefix("R")?.toIntOrNull() else null
        val update = model.dispatch(CbQsoEvent.Received(CbQsoReception(origin, own, kind, report)))
        if (update.disposition == CbQsoDisposition.REJECTED) return ignored(update.reason ?: "The exchange cannot advance.")
        lastAcceptedSlot = slot
        if (snapshot.sentReport == null) ownReport = d.frame.estimatedSnrDb?.takeIf { it.isFinite() }?.roundToInt()?.coerceIn(-50, 99) ?: ownReport
        if (named) peerHz = hz.roundToInt()
        period = if (Math.floorMod(slot / 15_000, 2L) == 0L) SlotPeriod.ODD else SlotPeriod.EVEN
        return CbQsoDecision(CbQsoAdmission.ACCEPTED, "Qualified peer message received; review the suggested message before enabling TX.")
    }
}
