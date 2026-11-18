package net.vash.ft8cbdroid.core.qso

import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.audio.SlotPeriod
import net.vash.ft8cbdroid.core.message.ManualMessage

data class CbAutoSequenceState(val active: Boolean = false, val calling: Boolean = false,
    val period: SlotPeriod = SlotPeriod.EVEN, val transmissions: Int = 0, val repeatCount: Int = 0,
    val nextTxUtcMillis: Long? = null, val completed: Boolean = false, val reason: String = "AutoSeq off",
    val cqTransmissions: Int = 0)
data class CbAutoSend(val utcMillis: Long, val kind: ManualMessage)

/** Pure bounded execution policy. The caller supplies only qualified R22 facts and real output confirmations. */
class CbAutoSequence(private val startedUtcMillis: Long, firstTxUtcMillis: Long,
    calling: Boolean, period: SlotPeriod) {
    var state = CbAutoSequenceState(true, calling, period, nextTxUtcMillis = firstTxUtcMillis, reason = "AutoSeq active")
        private set
    private var pending: CbAutoSend? = null
    private var lastPayload: Ft8Payload77? = null
    private var lastKind: ManualMessage? = null
    private var progress: List<Any?>? = null
    private var progressedAt = startedUtcMillis
    private var exchangeStartedAt: Long? = if (calling) null else startedUtcMillis
    init { require(firstTxUtcMillis % 15_000 == 0L && firstTxUtcMillis > startedUtcMillis) }
    fun stop(reason: String) { pending = null; state = state.copy(active = false, nextTxUtcMillis = null, reason = reason) }
    fun tick(now: Long, qso: CbQsoSnapshot, advice: CbQsoSuggestion?, decodedSlot: Long?, busy: Boolean): CbAutoSend? {
        if (!state.active) return null
        val facts = listOf(qso.peer, qso.cqSent, qso.replyReceived, qso.sentReport, qso.receivedReport,
            qso.localAcknowledgedReport, qso.peerAcknowledgedReport, qso.localFinalSent, qso.peerFinalReceived, qso.peerRogerReceived)
        if (facts != progress) { progress = facts; progressedAt = now }
        if (qso.phase == CbQsoPhase.COMPLETE) {
            stop("QSO protocol exchange complete. AutoSeq off."); state = state.copy(completed = true); return null
        }
        if (qso.phase == CbQsoPhase.ABORTED) { stop("QSO tracking ended. Start AutoSeq explicitly again."); return null }
        if (qso.peer != null && exchangeStartedAt == null) exchangeStartedAt = now
        val calling = state.calling && qso.peer == null
        val timedOut = if (calling) now - startedUtcMillis >= CALLING_TIMEOUT_MILLIS
            else now - (exchangeStartedAt ?: startedUtcMillis) >= 240_000 || now - progressedAt >= 120_000
        if (now < startedUtcMillis || timedOut) {
            stop("AutoSeq timed out without a completed exchange."); return null
        }
        if (busy || pending != null) return null
        val target = requireNotNull(state.nextTxUtcMillis)
        if (now < target - 1500) return null
        if (now >= target - 400) { stop("The automatic response deadline was missed. AutoSeq off."); return null }
        if (state.transmissions > 0 && (decodedSlot == null || decodedSlot < target - 15_000)) return null
        val kind = if (state.calling && qso.peer == null) ManualMessage.CQ else advice?.kind ?: return null
        if (!calling && kind == lastKind && state.repeatCount >= 3) { stop("AutoSeq stopped after three transmissions of the same message."); return null }
        if (advice != null && advice.period != state.period) { stop("Peer period changed. Select the period and restart explicitly."); return null }
        return CbAutoSend(target, kind).also { pending = it }
    }
    fun sent(action: CbAutoSend, payload: Ft8Payload77) {
        check(state.active && pending === action) { "Stale automatic output confirmation" }
        state = state.copy(transmissions = state.transmissions + 1,
            repeatCount = if (lastKind == action.kind && lastPayload == payload) state.repeatCount + 1 else 1,
            cqTransmissions = state.cqTransmissions + if (action.kind == ManualMessage.CQ) 1 else 0,
            nextTxUtcMillis = action.utcMillis + 30_000)
        lastPayload = payload; lastKind = action.kind; pending = null
        if (action.kind == ManualMessage.CQ && state.cqTransmissions >= CQ_LIMIT) stop("Auto CQ stopped after 20 calls without a reply.")
    }
    companion object {
        const val CQ_LIMIT = 20
        const val CALLING_TIMEOUT_MILLIS = 660_000L
    }
}
