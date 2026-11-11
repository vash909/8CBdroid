package net.vash.ft8cbdroid.reference

import java.time.Instant
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.qso.*
import net.vash.ft8cbdroid.core.recording.*

/** Original desktop wire/decision expectations plus explicit Android safety policy, shared with the phone. */
object QsoAdviceReferenceChecks {
    const val BASE = 1_800_000_000_000L
    fun rows(lines: List<String>) = lines.filter { it.isNotEmpty() && !it.startsWith('#') }.map { it.split('\t') }
    fun verify(lines: List<String>): Int {
        val seeds = lines.filter { it.startsWith("# seed\t") }.associate {
            val f = it.split('\t'); ascii(f[1]) to Ft8Payload77.fromBits(f[2])
        }
        val groups = rows(lines).groupBy { it[0] }
        for ((id, steps) in groups) {
            val first = steps.first()
            val advisor = CbQsoAdvisor(StationIdentity("1AT106", "JN45"))
            advisor.bindReceiver(1, BASE - 300_000)
            if (first[2] != "CALLING") check(advisor.selectPeer(first[3], 1500, SlotPeriod.EVEN, BASE - 300_000))
            fun sent(kind: ManualMessage, slot: Long) {
                val peer = advisor.snapshot.peer.orEmpty()
                val text = manualMessages(StationIdentity("1AT106", "JN45"), peer, -7).getValue(kind)
                val encoded = CbMessageCodec.pack(text) as PackResult.Encoded
                val ticket = checkNotNull(advisor.prepare(kind,
                    (-7).takeIf { kind in setOf(ManualMessage.REPORT, ManualMessage.ROGER) }, text, encoded.payload)) { "$id: seed TX $kind" }
                check(advisor.sent(ticket, slot))
            }
            fun seedRx(text: String, slot: Long) {
                val d = advisor.receive(row(seeds.getValue(text), slot), 1, slot + 16_000, 1500)
                check(d.admission == CbQsoAdmission.ACCEPTED) { "$id: seed $text: $d" }
            }
            when (first[2]) {
                "CALLING" -> sent(ManualMessage.CQ, BASE - 90_000)
                "REPLYING" -> sent(ManualMessage.REPLY, BASE - 90_000)
                "REPORT", "ROGERS" -> {
                    sent(ManualMessage.REPORT, BASE - 90_000)
                    if (first[2] == "ROGERS") seedRx("1AT106 R-07", BASE - 75_000)
                }
                "ROGER_REPORT", "SIGNOFF", "COMPLETE" -> {
                    sent(ManualMessage.REPLY, BASE - 120_000)
                    seedRx("1AT106 -07", BASE - 105_000)
                    sent(ManualMessage.ROGER, BASE - 90_000)
                    if (first[2] != "ROGER_REPORT") seedRx("1AT106 RR73", BASE - 75_000)
                    if (first[2] == "COMPLETE") sent(ManualMessage.SIGNOFF, BASE - 60_000)
                }
                else -> error("Unknown seed stage")
            }
            for (f in steps) {
                val slot = BASE + f[1].toLong() * 30_000
                val payload = Ft8Payload77.fromBits(f[6])
                val row = row(payload, slot, f[7].toFloat(), first[4] == "1")
                val before = advisor.snapshot
                val decision = advisor.receive(row, 1, slot + 16_000, 1500)
                check(decision.admission.name == f[8]) { "$id: $decision != ${f[8]}" }
                check((advisor.suggestion?.kind?.name ?: "-") == f[9]) { "$id: next ${advisor.suggestion} != ${f[9]}" }
                if (decision.admission == CbQsoAdmission.IGNORED) check(advisor.snapshot == before) { "$id: ignored input changed QSO" }
                if (ascii(f[13]) == "matches") {
                    val desktop = mapOf("REPLY" to 1, "REPORT" to 2, "ROGER" to 3, "RR73" to 4, "SIGNOFF" to 5)
                    check(desktop[f[9]] == f[10].toInt()) { "$id: desktop next-message mismatch" }
                    check(advisor.snapshot.peer == f[11]) { "$id: desktop peer mismatch" }
                    check(f[12] == "0") { "$id: unexpected desktop stop" }
                }
                if (decision.admission == CbQsoAdmission.STOP) check(f[12] == "1") { "$id: desktop did not stop" }
            }
        }
        return groups.size
    }

    fun row(payload: Ft8Payload77, slot: Long = BASE, hz: Float = 1500f, warm: Boolean = true, own: String = "1AT106"): LiveDecodeRow {
        val session = CbMessageSession(own)
        if (warm) { session.learnCall("26AT101"); session.learnCall("161XZ085"); session.commitPending() }
        val message = session.unpack(payload)
        val parts = (message as? UnpackResult.Decoded)?.message?.parts.orEmpty()
        val flags = mutableSetOf(RecordedDecodeFlag.CRC_LDPC_VALID, RecordedDecodeFlag.SNR_ESTIMATED)
        if (parts.any { it is MessagePart.UnresolvedCall }) flags += RecordedDecodeFlag.UNKNOWN_HASH
        if (parts.any { it is MessagePart.AmbiguousCall }) flags += RecordedDecodeFlag.AMBIGUOUS_HASH
        return LiveDecodeRow(RecordedDecode(Ft8DecodedFrame(payload, .5f, hz, 10, -12f), message,
            Instant.ofEpochMilli(slot), Instant.ofEpochMilli(slot + 500), 0f, flags), "NTP/test", 1.0)
    }
    fun verifyExchange(lines: List<String>): Int {
        val stations = listOf(StationIdentity("1AT106", "JN45"), StationIdentity("26AT101", "IO91"))
        val sessions = stations.map { CbQsoAdvisor(it, -12).also { q -> q.bindReceiver(1, BASE) } }
        val rows = rows(lines)
        for (f in rows) {
            val index = f[0].toInt(); val from = if (f[1] == "local") 0 else 1; val to = 1 - from
            val sender = sessions[from]; val receiver = sessions[to]
            val slot = BASE + index * 15_000
            val kind = ManualMessage.valueOf(f[2]); val text = ascii(f[3]); val payload = Ft8Payload77.fromBits(f[4])
            val report = (-12).takeIf { kind in setOf(ManualMessage.REPORT, ManualMessage.ROGER) }
            val before = sender.snapshot
            val ticket = checkNotNull(sender.prepare(kind, report, text, payload)) { "Exchange TX $index" }
            check(sender.snapshot.copy(pending = null) == before) { "Prepared counted as sent" }
            check(sender.sent(ticket, slot))
            val decision = receiver.receive(row(payload, slot, own = stations[to].callsign), 1, slot + 16_000, 1500)
            if (index == 0) {
                check(decision.admission == CbQsoAdmission.IGNORED)
                check(receiver.selectPeer(stations[from].callsign, 1500, SlotPeriod.ODD, slot + 15_000))
            } else check(decision.admission == CbQsoAdmission.ACCEPTED) { "Exchange RX $index: $decision" }
            check(sessions[0].snapshot.phase.name == f[5] && sessions[1].snapshot.phase.name == f[6]) { "Exchange phase $index" }
        }
        check(sessions.all { it.snapshot.phase == CbQsoPhase.COMPLETE && it.suggestion == null })
        return rows.size
    }
    private fun ascii(hex: String) = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
}
