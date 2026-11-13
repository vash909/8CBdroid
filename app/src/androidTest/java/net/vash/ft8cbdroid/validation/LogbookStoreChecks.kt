package net.vash.ft8cbdroid.validation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.core.logbook.*
import net.vash.ft8cbdroid.logbook.SqliteQsoLogRepository
import org.json.JSONObject

/** Actual Android SQLite transactions and files. Every fixture uses a separate, run-owned database. */
object LogbookStoreChecks {
    suspend fun run(context: Context, run: String): JSONObject {
        require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
        val name = "log-check-$run.db"; val futureName = "log-future-$run.db"; val corruptName = "log-corrupt-$run.db"
        var repo = SqliteQsoLogRepository(context, name)
        val detail = QsoDetails("1AT106", "26AT101", 15000, 90000, "JN44", dialHz = 27_265_000, sentReport = -7, receivedReport = -9)
        val completion = QsoCompletion("fixture-$run", detail, 1500)
        val command = SaveQso("record-$run", detail, completion.id, dialConfirmed = true)
        fun open(n: String) = SQLiteDatabase.openDatabase(context.getDatabasePath(n).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            check(repo.read() == QsoLogSnapshot())
            repeat(2) { repo.stage(completion) }; repo.close(); repo = SqliteQsoLogRepository(context, name)
            check(repo.read().pending == listOf(completion) && repo.read().records.isEmpty())
            check(runCatching { repo.save(command.copy(dialConfirmed = false)) }.isFailure)
            val first = repo.save(command)
            check(repo.save(command.copy(id = "duplicate-$run")) == first)
            check(runCatching { repo.save(command.copy(details = detail.copy(notes = "Changed create retry"))) }.isFailure)
            check(repo.read().records.single() == first)
            repo.stage(completion); check(repo.read().pending.isEmpty())
            repo.close(); repo = SqliteQsoLogRepository(context, name)
            check(repo.read().records == listOf(first) && first.timeBasis == QsoTimeBasis.CORRECTED_APP_UTC)
            val update = SaveQso(first.id, detail.copy(notes = "Edited test contact", dialHz = 27_045_000), completion.id, 1, true)
            val second = repo.save(update); check(second.version == 2 && second.details.notes == "Edited test contact")
            check(runCatching { repo.save(update.copy(details = detail.copy(notes = "Stale edit"))) }.isFailure)
            check(repo.read().records.single() == second)
            val third = repo.save(update.copy(details = second.details.copy(endedUtcMillis = 105000), expectedVersion = 2))
            check(third.timeBasis == QsoTimeBasis.OPERATOR_UTC)
            val manual = repo.save(SaveQso("manual-$run", detail.copy(ownCall = "161AT101", peerCall = "999ZZ999"), dialConfirmed = true))
            check(manual.origin == QsoOrigin.MANUAL && manual.txAudioHz == null)
            check(repo.read().workedCalls("1AT106") == setOf("26AT101"))
            check(repo.read().search("edited TEST").single() == third)
            val abort = completion.copy(id = "abort-$run", details = detail.copy(peerCall = "14AT101"))
            repo.stage(abort)
            open(name).use { db -> db.execSQL("CREATE TRIGGER test_abort BEFORE DELETE ON pending_qsos BEGIN SELECT RAISE(ABORT,'test rollback'); END") }
            check(runCatching { repo.save(command.copy(id = abort.id, completionId = abort.id, details = abort.details)) }.isFailure)
            repo.close(); repo = SqliteQsoLogRepository(context, name)
            check(repo.read().records.size == 2 && repo.read().pending == listOf(abort))
            open(name).use { it.execSQL("DROP TRIGGER test_abort") }
            coroutineScope {
                List(8) { async { repo.stage(abort) } }.awaitAll()
                val results = List(8) { index -> async { repo.save(command.copy(id = "concurrent-$index-$run", completionId = abort.id, details = abort.details)) } }.awaitAll()
                check(results.map { it.id }.distinct().size == 1)
            }
            check(repo.read().records.size == 3 && repo.read().pending.isEmpty())
            context.openOrCreateDatabase(futureName, Context.MODE_PRIVATE, null).use {
                it.execSQL("CREATE TABLE marker (value TEXT)"); it.execSQL("INSERT INTO marker VALUES ('preserve')"); it.version = 2
            }
            val future = SqliteQsoLogRepository(context, futureName)
            try { check(runCatching { future.read() }.isFailure) } finally { future.close() }
            open(futureName).use { db ->
                check(db.version == 2)
                db.rawQuery("SELECT value FROM marker", null).use { check(it.moveToFirst() && it.getString(0) == "preserve") }
            }
            val badFile = context.getDatabasePath(corruptName); val bad = byteArrayOf(1, 2, 3, 4, 5)
            badFile.writeBytes(bad)
            val corrupt = SqliteQsoLogRepository(context, corruptName)
            try {
                check(runCatching { corrupt.read() }.isFailure)
                check(runCatching { corrupt.stage(completion) }.isFailure)
                check(badFile.readBytes().contentEquals(bad))
            } finally { corrupt.close() }
            return JSONObject().put("result", "PASS").put("restart_and_duplicate_completion", true)
                .put("atomic_rollback_and_retry", true).put("concurrent_idempotence", true)
                .put("optimistic_edits_and_time_provenance", true).put("manual_origin_and_worked_scope", true)
                .put("future_schema_preserved", true).put("corrupt_bytes_preserved", true)
        } finally {
            repo.close()
            listOf(name, futureName, corruptName).forEach { context.deleteDatabase(it) }
        }
    }
}
