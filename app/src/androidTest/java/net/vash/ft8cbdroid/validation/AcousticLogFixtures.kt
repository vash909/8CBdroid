package net.vash.ft8cbdroid.validation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.logbook.SqliteQsoLogRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Removes only newly staged bench exchanges; never replaces an existing operator database. */
class AcousticLogFixtures(private val context: Context, private val run: String) {
    private val repository = SqliteQsoLogRepository.get(context)
    private val original = runBlocking { repository.read() }
    private val started = System.currentTimeMillis() - 15_000
    fun cleanup(): Int {
        val before = runBlocking { repository.read() }
        val ids = before.pending.filter { q ->
            original.pending.none { it.id == q.id } && q.details.ownCall == "1AT106" &&
                q.details.peerCall == "26AT101" && q.details.startedUtcMillis >= started
        }.map { it.id }
        File(context.cacheDir, "acoustic-log-fixtures-$run.json").writeText(
            JSONObject().put("run", run).put("ids", JSONArray(ids)).toString())
        if (ids.isNotEmpty()) SQLiteDatabase.openDatabase(context.getDatabasePath(SqliteQsoLogRepository.DATABASE_NAME).path,
            null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.beginTransaction()
            try {
                ids.forEach { check(db.delete("pending_qsos", "id=?", arrayOf(it)) == 1) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        val after = runBlocking { repository.read() }
        check(after.records == before.records && after.pending == before.pending.filterNot { it.id in ids })
        check(original.records.all { it in after.records } && original.pending.all { it in after.pending })
        return ids.size
    }
}
