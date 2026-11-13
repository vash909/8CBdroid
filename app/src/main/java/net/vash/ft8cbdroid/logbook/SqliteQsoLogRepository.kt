package net.vash.ft8cbdroid.logbook

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.vash.ft8cbdroid.core.logbook.*

/** One process-owned instance in production. No destructive fallback or database reset. */
class SqliteQsoLogRepository(context: Context, name: String = DATABASE_NAME) : QsoLogRepository {
    companion object {
        const val DATABASE_NAME = "qso-log.db"
        @Volatile private var instance: SqliteQsoLogRepository? = null
        fun get(context: Context): SqliteQsoLogRepository = instance ?: synchronized(this) {
            instance ?: SqliteQsoLogRepository(context.applicationContext).also { instance = it }
        }
    }
    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1,
        DatabaseErrorHandler { /* Preserve damaged data for recovery instead of Android's default deletion. */ }) {
        override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
        override fun onCreate(db: SQLiteDatabase) {
            val fields = "own_call TEXT NOT NULL,peer_call TEXT NOT NULL,start_utc INTEGER NOT NULL,end_utc INTEGER NOT NULL," +
                "own_grid TEXT NOT NULL,peer_grid TEXT NOT NULL,dial_hz INTEGER NOT NULL,sent_report INTEGER,received_report INTEGER,notes TEXT NOT NULL"
            db.execSQL("CREATE TABLE pending_qsos (id TEXT PRIMARY KEY NOT NULL,$fields,tx_audio_hz INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE qsos (id TEXT PRIMARY KEY NOT NULL,completion_id TEXT UNIQUE,version INTEGER NOT NULL CHECK(version>0)," +
                "origin TEXT NOT NULL,time_basis TEXT NOT NULL,mode TEXT NOT NULL CHECK(mode='FT8'),$fields,tx_audio_hz INTEGER)")
            db.execSQL("CREATE INDEX qsos_recent ON qsos(end_utc DESC,id)")
            db.execSQL("CREATE INDEX qsos_worked ON qsos(own_call,peer_call)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("Unsupported log schema upgrade. Existing data was kept.")
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw SQLiteException("This log needs a newer app. Existing data was kept.")
        }
    }
    private val mutex = Mutex()
    private suspend fun <T> transaction(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val db = helper.writableDatabase
            db.beginTransaction()
            try { block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
        }
    }
    override suspend fun read(): QsoLogSnapshot = transaction { db ->
        val entries = db.query("qsos", null, null, null, null, null, "end_utc DESC,id").use { c ->
            buildList { while (c.moveToNext()) add(c.record()) }
        }
        val pending = db.query("pending_qsos", null, null, null, null, null, "end_utc DESC,id").use { c ->
            buildList { while (c.moveToNext()) add(c.completion()) }
        }
        QsoLogSnapshot(entries, pending)
    }
    override suspend fun stage(completion: QsoCompletion) {
        completion.validate()
        transaction { db ->
            if (find(db, "completion_id", completion.id) != null) return@transaction
            val existing = db.query("pending_qsos", null, "id=?", arrayOf(completion.id), null, null, null).use { c ->
                if (c.moveToFirst()) c.completion() else null
            }
            check(existing == null || existing == completion) { "A different completion already uses this identity." }
            if (existing == null) db.insertOrThrow("pending_qsos", null, completion.details.values().apply {
                put("id", completion.id); put("tx_audio_hz", completion.txAudioHz)
            })
        }
    }
    override suspend fun save(command: SaveQso): QsoRecord {
        require(command.dialConfirmed) { "Confirm the actual radio dial frequency before saving." }
        require(command.id.isNotBlank() && command.id.length <= 80)
        val details = command.details.normalized().also { it.validate() }
        return transaction { db ->
            val existing = find(db, "id", command.id)
            if (command.expectedVersion != null) {
                check(existing != null && existing.version == command.expectedVersion) { "This entry changed. Reopen it before saving." }
                check(existing.completionId == command.completionId) { "Entry origin cannot be changed." }
                val basis = if (existing.details.startedUtcMillis != details.startedUtcMillis || existing.details.endedUtcMillis != details.endedUtcMillis)
                    QsoTimeBasis.OPERATOR_UTC else existing.timeBasis
                val updated = existing.copy(version = existing.version + 1, details = details, timeBasis = basis).also { it.validate() }
                check(db.update("qsos", updated.values(), "id=? AND version=?", arrayOf(command.id, command.expectedVersion.toString())) == 1)
                updated
            } else {
                command.completionId?.let { find(db, "completion_id", it) }?.let {
                    check(it.details == details) { "This QSO was already saved. Reload and reopen it to edit." }
                    return@transaction it
                }
                if (existing != null) {
                    check(existing.completionId == command.completionId) { "This identity belongs to a different entry." }
                    check(existing.details == details) { "This QSO was already saved. Reload and reopen it to edit." }
                    return@transaction existing
                }
                val candidate = command.completionId?.let { id ->
                    db.query("pending_qsos", null, "id=?", arrayOf(id), null, null, null).use { c ->
                        check(c.moveToFirst()) { "The pending QSO is unavailable. Reload the log." }; c.completion()
                    }
                }
                val basis = if (candidate != null && candidate.details.startedUtcMillis == details.startedUtcMillis && candidate.details.endedUtcMillis == details.endedUtcMillis)
                    QsoTimeBasis.CORRECTED_APP_UTC else QsoTimeBasis.OPERATOR_UTC
                val record = QsoRecord(command.id, candidate?.id, 1,
                    if (candidate == null) QsoOrigin.MANUAL else QsoOrigin.LOCAL_AUDIO, details, candidate?.txAudioHz, basis).also { it.validate() }
                db.insertOrThrow("qsos", null, record.values())
                candidate?.let { check(db.delete("pending_qsos", "id=?", arrayOf(it.id)) == 1) }
                record
            }
        }
    }
    private fun find(db: SQLiteDatabase, column: String, value: String): QsoRecord? =
        db.query("qsos", null, "$column=?", arrayOf(value), null, null, null).use { if (it.moveToFirst()) it.record() else null }
    private fun QsoDetails.values() = ContentValues().apply {
        put("own_call", ownCall); put("peer_call", peerCall); put("start_utc", startedUtcMillis); put("end_utc", endedUtcMillis)
        put("own_grid", ownGrid); put("peer_grid", peerGrid); put("dial_hz", dialHz); put("sent_report", sentReport)
        put("received_report", receivedReport); put("notes", notes)
    }
    private fun QsoRecord.values() = details.values().apply {
        put("id", id); put("completion_id", completionId); put("version", version); put("origin", origin.name)
        put("time_basis", timeBasis.name); put("mode", mode); put("tx_audio_hz", txAudioHz)
    }
    private fun Cursor.s(n: String) = getString(getColumnIndexOrThrow(n))
    private fun Cursor.l(n: String) = getLong(getColumnIndexOrThrow(n))
    private fun Cursor.i(n: String): Int? = getColumnIndexOrThrow(n).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.details() = QsoDetails(s("own_call"), s("peer_call"), l("start_utc"), l("end_utc"),
        s("own_grid"), s("peer_grid"), l("dial_hz"), i("sent_report"), i("received_report"), s("notes")).also { it.validate() }
    private fun Cursor.completion() = QsoCompletion(s("id"), details(), requireNotNull(i("tx_audio_hz"))).also { it.validate() }
    private fun Cursor.record() = QsoRecord(s("id"), getColumnIndexOrThrow("completion_id").let { if (isNull(it)) null else getString(it) }, requireNotNull(i("version")), QsoOrigin.valueOf(s("origin")),
        details(), i("tx_audio_hz"), QsoTimeBasis.valueOf(s("time_basis"))).also { require(s("mode") == it.mode); it.validate() }
    /** Test/standalone repository instances only; the process-owned instance remains open. */
    fun close() = helper.close()
}
