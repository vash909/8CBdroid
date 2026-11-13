package net.vash.ft8cbdroid.core.logbook

import java.util.UUID
import net.vash.ft8cbdroid.core.CbCallsign
import net.vash.ft8cbdroid.core.MaidenheadLocator
import net.vash.ft8cbdroid.core.StationIdentity
import net.vash.ft8cbdroid.core.qso.CbQsoPhase
import net.vash.ft8cbdroid.core.qso.CbQsoSnapshot

enum class QsoOrigin { LOCAL_AUDIO, MANUAL }
enum class QsoTimeBasis { CORRECTED_APP_UTC, OPERATOR_UTC }

/** Dial frequency is operator-confirmed, never CAT readback or a measured RF frequency. */
data class QsoDetails(
    val ownCall: String, val peerCall: String, val startedUtcMillis: Long, val endedUtcMillis: Long,
    val ownGrid: String = "", val peerGrid: String = "", val dialHz: Long,
    val sentReport: Int? = null, val receivedReport: Int? = null, val notes: String = "",
) {
    fun normalized() = copy(ownCall = CbCallsign.normalize(ownCall), peerCall = CbCallsign.normalize(peerCall),
        ownGrid = ownGrid.trim().uppercase(java.util.Locale.ROOT), peerGrid = peerGrid.trim().uppercase(java.util.Locale.ROOT), notes = notes.trim())
    fun validate() {
        require(ownCall == CbCallsign.normalize(ownCall) && CbCallsign.isValid(ownCall)) { "Enter a complete station CB callsign." }
        require(peerCall == CbCallsign.normalize(peerCall) && CbCallsign.isValid(peerCall) && peerCall != ownCall) { "Enter a complete, different peer CB callsign." }
        require(startedUtcMillis in 0..253402300799999L && endedUtcMillis in startedUtcMillis..253402300799999L) { "End UTC must be at or after start UTC." }
        require(ownGrid.isEmpty() || MaidenheadLocator.isValid(ownGrid)) { "Station locator must contain 4 or 6 valid characters." }
        require(peerGrid.isEmpty() || MaidenheadLocator.isValid(peerGrid)) { "Peer locator must contain 4 or 6 valid characters." }
        require(dialHz in 100_000..1_300_000_000) { "Enter a radio dial frequency between 0.1 and 1300 MHz." }
        require(listOf(sentReport, receivedReport).all { it == null || it in -50..99 }) { "Reports must be between -50 and +99 dB, or blank." }
        require(notes.length <= 500) { "Notes must contain at most 500 characters." }
    }
}

/** A durable review candidate; its dial is only a reference until explicitly confirmed. */
data class QsoCompletion(val id: String, val details: QsoDetails, val txAudioHz: Int) {
    fun validate() {
        require(id.isNotBlank() && id.length <= 80)
        details.validate()
        require(details.sentReport != null && details.receivedReport != null)
        require(txAudioHz in 200..2900)
    }
}

data class QsoRecord(val id: String, val completionId: String?, val version: Int, val origin: QsoOrigin,
    val details: QsoDetails, val txAudioHz: Int? = null,
    val timeBasis: QsoTimeBasis = QsoTimeBasis.OPERATOR_UTC) {
    val mode get() = "FT8"
    val routeDescription get() = if (origin == QsoOrigin.LOCAL_AUDIO) "Built-in phone microphone / speaker" else "Operator entry; route unverified"
    fun validate() {
        require(id.isNotBlank() && id.length <= 80 && version > 0)
        details.validate()
        require((origin == QsoOrigin.LOCAL_AUDIO) == (completionId != null))
        require(if (origin == QsoOrigin.LOCAL_AUDIO) txAudioHz?.let { it in 200..2900 } == true else txAudioHz == null)
        require(origin != QsoOrigin.MANUAL || timeBasis == QsoTimeBasis.OPERATOR_UTC)
    }
}

data class QsoLogSnapshot(val records: List<QsoRecord> = emptyList(), val pending: List<QsoCompletion> = emptyList()) {
    fun workedCalls(ownCall: String) = records.filter { it.details.ownCall == CbCallsign.normalize(ownCall) }
        .map { it.details.peerCall }.toSet()
    fun search(query: String): List<QsoRecord> {
        val q = query.trim()
        return records.filter { q.isEmpty() || listOf(it.details.ownCall, it.details.peerCall, it.details.notes)
            .any { value -> value.contains(q, ignoreCase = true) } }
    }
}

data class SaveQso(val id: String, val details: QsoDetails, val completionId: String? = null,
    val expectedVersion: Int? = null, val dialConfirmed: Boolean = false)

interface QsoLogRepository {
    suspend fun read(): QsoLogSnapshot
    suspend fun stage(completion: QsoCompletion)
    /** Atomic candidate removal + record insertion, idempotent by completion/id; edits use optimistic versions. */
    suspend fun save(command: SaveQso): QsoRecord
}

/** Consumes only qualified receive/confirmed-output events. Observations/aborts alone produce nothing. */
class QsoCompletionTracker(private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private var id = newId()
    private var firstUtc: Long? = null
    private var lastUtc: Long? = null
    private var previous: CbQsoSnapshot? = null
    private var emitted = false
    fun reset() { id = newId(); firstUtc = null; lastUtc = null; previous = null; emitted = false }
    fun observe(station: StationIdentity, snapshot: CbQsoSnapshot, slotUtcMillis: Long,
        referenceDialHz: Long, txAudioHz: Int): QsoCompletion? {
        val old = previous
        if (snapshot.phase !in setOf(CbQsoPhase.COMPLETE, CbQsoPhase.ABORTED) &&
            (old?.phase in setOf(CbQsoPhase.COMPLETE, CbQsoPhase.ABORTED) || old?.peer != null && snapshot.peer == null)) reset()
        previous = snapshot
        if (snapshot.phase in setOf(CbQsoPhase.IDLE, CbQsoPhase.SELECTED, CbQsoPhase.ABORTED)) return null
        firstUtc = minOf(firstUtc ?: slotUtcMillis, slotUtcMillis)
        lastUtc = maxOf(lastUtc ?: slotUtcMillis, slotUtcMillis + 15_000)
        if (snapshot.phase != CbQsoPhase.COMPLETE || emitted) return null
        val result = QsoCompletion(id, QsoDetails(station.callsign, requireNotNull(snapshot.peer), requireNotNull(firstUtc),
            requireNotNull(lastUtc), station.grid, dialHz = referenceDialHz, sentReport = snapshot.sentReport,
            receivedReport = snapshot.receivedReport).normalized(), txAudioHz)
        result.validate(); emitted = true
        return result
    }
}
