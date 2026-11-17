package net.vash.ft8cbdroid.core.logbook

/** Same second, station pair, FT8 and exact dial Hz: a possible duplicate, never an automatic overwrite. */
fun QsoRecord.duplicateKey() = listOf(details.ownCall, details.peerCall, Math.floorDiv(details.startedUtcMillis, 1000), details.dialHz, mode).joinToString("|")
enum class LogEntryStatus { NEW, DUPLICATE, EXISTING, CONFLICT }
data class LogMergeRow(val record: QsoRecord? = null, val pending: QsoCompletion? = null, val status: LogEntryStatus) {
    val id get() = record?.id ?: requireNotNull(pending).id
    val details get() = record?.details ?: requireNotNull(pending).details
}
fun logMergeRows(current: QsoLogSnapshot, incoming: QsoLogSnapshot, checkDuplicates: Boolean = true): List<LogMergeRow> {
    LogBackup.validate(incoming)
    val records = current.records.associateBy { it.id }; val pending = current.pending.associateBy { it.id }
    val keys = current.records.map { it.duplicateKey() }.toMutableSet()
    val rows = incoming.records.map { r ->
        val existing = records[r.id]
        val status = when {
            existing == r -> LogEntryStatus.EXISTING
            existing != null || pending.containsKey(r.id) || r.completionId != null &&
                (current.records.any { it.completionId == r.completionId } || current.pending.any { it.id == r.completionId }) -> LogEntryStatus.CONFLICT
            checkDuplicates && !keys.add(r.duplicateKey()) -> LogEntryStatus.DUPLICATE
            else -> LogEntryStatus.NEW
        }
        LogMergeRow(record = r, status = status)
    }
    return rows + incoming.pending.map { p ->
        val status = when {
            pending[p.id] == p -> LogEntryStatus.EXISTING
            pending.containsKey(p.id) || records.containsKey(p.id) -> LogEntryStatus.CONFLICT
            current.records.any { it.completionId == p.id } -> LogEntryStatus.EXISTING
            else -> LogEntryStatus.NEW
        }
        LogMergeRow(pending = p, status = status)
    }
}
data class LogMergeCommand(val before: QsoLogSnapshot, val incoming: QsoLogSnapshot, val allowDuplicateIds: Set<String> = emptySet(), val checkDuplicates: Boolean = true)
fun mergeLog(command: LogMergeCommand, current: QsoLogSnapshot): QsoLogSnapshot {
    val incoming = command.incoming; LogBackup.validate(incoming)
    require(incoming.records.isNotEmpty() || incoming.pending.isNotEmpty()) { "Select at least one entry." }
    // A committed import whose read-back failed is safe to retry with the same identities.
    if (incoming.records.all { it in current.records } && incoming.pending.all { p -> p in current.pending || current.records.any { it.completionId == p.id } }) return current
    require(LogBackup.encode(command.before).contentEquals(LogBackup.encode(current))) { "The log changed after preview. Refresh the preview and review it again." }
    val rows = logMergeRows(current, incoming, command.checkDuplicates)
    require(rows.none { it.status == LogEntryStatus.CONFLICT }) { "A conflicting identity was kept. Refresh the preview." }
    require(rows.none { it.status == LogEntryStatus.DUPLICATE && it.id !in command.allowDuplicateIds }) { "Review each possible duplicate explicitly." }
    return QsoLogSnapshot(current.records + rows.filter { it.record != null && it.status !in setOf(LogEntryStatus.EXISTING) }.map { requireNotNull(it.record) },
        current.pending + rows.filter { it.pending != null && it.status == LogEntryStatus.NEW }.map { requireNotNull(it.pending) }).also(LogBackup::validate)
}
