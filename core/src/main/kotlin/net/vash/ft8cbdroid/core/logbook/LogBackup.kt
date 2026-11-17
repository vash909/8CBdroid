package net.vash.ft8cbdroid.core.logbook

import java.io.*
import java.security.MessageDigest

/** Versioned full log, including pending reviews. The checksum detects damage, not authenticity. */
object LogBackup {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_RECORDS = 5000
    private const val MAGIC = 0x3843424c
    fun validate(value: QsoLogSnapshot) {
        require(value.records.size + value.pending.size <= MAX_RECORDS) { "A transfer supports at most 5,000 entries." }
        value.records.forEach { it.validate() }; value.pending.forEach { it.validate() }
        require((value.records.map { it.id } + value.pending.map { it.id }).distinct().size == value.records.size + value.pending.size) { "Duplicate entry identities in log data." }
        val completions = value.records.mapNotNull { it.completionId }
        require(completions.distinct().size == completions.size && value.pending.none { it.id in completions }) { "A completed exchange cannot also be pending." }
    }
    fun encode(value: QsoLogSnapshot): ByteArray {
        validate(value)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(1); out.writeInt(value.records.size)
            for (r in value.records.sortedBy { it.id }) {
                out.writeUTF(r.id); out.writeBoolean(r.completionId != null); r.completionId?.let(out::writeUTF)
                out.writeInt(r.version); out.writeUTF(r.origin.name); out.writeUTF(r.timeBasis.name)
                out.details(r.details); out.nullable(r.txAudioHz)
            }
            out.writeInt(value.pending.size)
            for (p in value.pending.sortedBy { it.id }) { out.writeUTF(p.id); out.details(p.details); out.writeInt(p.txAudioHz) }
        }
        val body = bytes.toByteArray(); require(body.size + 32 <= MAX_BYTES) { "The log backup exceeds 4 MiB." }
        return body + MessageDigest.getInstance("SHA-256").digest(body)
    }
    fun decode(bytes: ByteArray): QsoLogSnapshot {
        require(bytes.size in 48..MAX_BYTES) { "Invalid or oversized log backup." }
        val body = bytes.copyOfRange(0, bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(body), bytes.takeLast(32).toByteArray())) { "Backup checksum failed. Existing log was kept." }
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == MAGIC) { "This is not an 8CBdroid log backup." }
            require(input.readInt() == 1) { "Unsupported log backup version." }
            fun count() = input.readInt().also { require(it in 0..MAX_RECORDS) { "Invalid backup entry count." } }
            val records = List(count()) {
                val id = input.readUTF(); val completion = if (input.readBoolean()) input.readUTF() else null
                val version = input.readInt(); val origin = QsoOrigin.valueOf(input.readUTF()); val time = QsoTimeBasis.valueOf(input.readUTF())
                QsoRecord(id, completion, version, origin, input.details(), input.nullable(), time)
            }
            val pending = List(count()) { QsoCompletion(input.readUTF(), input.details(), input.readInt()) }
            require(input.available() == 0) { "Unexpected trailing backup data." }
            QsoLogSnapshot(records, pending).also(::validate)
        }
    }
    private fun DataOutputStream.nullable(v: Int?) { writeBoolean(v != null); v?.let(::writeInt) }
    private fun DataInputStream.nullable(): Int? = if (readBoolean()) readInt() else null
    private fun DataOutputStream.details(d: QsoDetails) {
        writeUTF(d.ownCall); writeUTF(d.peerCall); writeLong(d.startedUtcMillis); writeLong(d.endedUtcMillis)
        writeUTF(d.ownGrid); writeUTF(d.peerGrid); writeLong(d.dialHz); nullable(d.sentReport); nullable(d.receivedReport); writeUTF(d.notes)
    }
    private fun DataInputStream.details() = QsoDetails(readUTF(), readUTF(), readLong(), readLong(), readUTF(), readUTF(), readLong(), nullable(), nullable(), readUTF())
}
