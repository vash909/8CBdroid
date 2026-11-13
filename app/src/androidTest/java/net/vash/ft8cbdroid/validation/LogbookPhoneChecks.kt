package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.core.logbook.*
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.core.qso.CbQsoPhase
import net.vash.ft8cbdroid.logbook.*
import net.vash.ft8cbdroid.ntp.UdpNtpClient
import net.vash.ft8cbdroid.operate.ManualOperatingViewModel
import net.vash.ft8cbdroid.time.AndroidTimeSource
import net.vash.ft8cbdroid.time.TimeViewModel
import org.json.JSONArray
import org.json.JSONObject

/** One actual acoustic answer, real SQLite/UI failures and a separate cold-process reopen. Fixtures are removed by ID. */
class LogbookPhoneChecks(private val instrumentation: Instrumentation, private val run: String, private val scenario: String) {
    private val context get() = instrumentation.targetContext
    private val repository get() = SqliteQsoLogRepository.get(context)
    private val trigger get() = "logbook_test_$run"
    private fun <T> main(block: () -> T): T {
        var r: Result<T>? = null
        instrumentation.runOnMainSync { r = runCatching(block) }; return requireNotNull(r).getOrThrow()
    }
    private fun await(label: String, millis: Long = 12000, test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + millis
        while (!test()) { check(SystemClock.elapsedRealtime() < until) { "Timed out: $label" }; SystemClock.sleep(40) }
    }
    private fun write(name: String, value: JSONObject) {
        val f = File(context.cacheDir, "$name.tmp"); f.writeText(value.toString())
        check(f.renameTo(File(context.cacheDir, "$name.json")))
    }
    private fun readManifest() = JSONObject(File(context.cacheDir, "logbook-fixtures.json").readText()).also { check(it.getString("run") == run) }
    private fun snapshot() = runBlocking { repository.read() }
    private fun hash(s: QsoLogSnapshot) = MessageDigest.getInstance("SHA-256").digest(s.toString().toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(SqliteQsoLogRepository.DATABASE_NAME).path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun fixture(manifest: JSONObject, id: String) {
        val ids = manifest.getJSONArray("ids")
        if ((0 until ids.length()).none { ids.getString(it) == id }) ids.put(id)
        write("logbook-fixtures", manifest)
    }
    private fun cleanup(manifest: JSONObject) {
        val ids = manifest.getJSONArray("ids")
        raw { db ->
            db.beginTransaction()
            try {
                db.execSQL("DROP TRIGGER IF EXISTS \"$trigger\"")
                repeat(ids.length()) { index ->
                    val id = ids.getString(index)
                    check(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
                    db.delete("qsos", "id=?", arrayOf(id)); db.delete("pending_qsos", "id=?", arrayOf(id))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        check(hash(snapshot()) == manifest.getString("initial_hash")) { "Original log differs after fixture cleanup; keep evidence for review." }
    }
    private fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString() == text) return true
        repeat(node.childCount) { if (contains(node.getChild(it), text)) return true }; return false
    }
    private fun visible(text: String) = contains(PhoneNavigation.root(instrumentation), text)
    fun run() {
        val report = JSONObject().put("run", run); val output = Bundle(); var code = Activity.RESULT_CANCELED
        var activity: MainActivity? = null; var operating: ManualOperatingViewModel? = null; var capture: CaptureViewModel? = null
        var manifest: JSONObject? = null; var preserveForRestart = false
        try {
            require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            if (scenario == "logbook-store") {
                report.put("store", runBlocking { LogbookStoreChecks.run(context, run) }).put("result", "PASS")
            } else if (scenario == "logbook-cleanup") {
                manifest = readManifest(); cleanup(manifest); report.put("result", "PASS")
            } else {
                var current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
                activity = current
                val model = main { ViewModelProvider(current)[ManualOperatingViewModel::class.java] }; operating = model
                val input = main { current.receiveRuntime.capture }; capture = input
                val log = main { ViewModelProvider(current)[LogbookViewModel::class.java] }
                val time = main { current.receiveRuntime.time }
                await("saved setup and real log") { main { model.uiState.value.available && log.uiState.value.loaded && !log.uiState.value.busy && time.uiState.value.saved != null } }
                if (scenario == "logbook-reopen") {
                    manifest = readManifest()
                    val ids = manifest.getJSONArray("saved_ids").let { a -> (0 until a.length()).map(a::getString).toSet() }
                    check(snapshot().records.count { it.id in ids } == 2)
                    check(main { !model.uiState.value.armed && !model.uiState.value.autoSequence.active && input.uiState.value.phase == CapturePhase.IDLE })
                    PhoneNavigation.open(instrumentation, "Logbook")
                    PhoneNavigation.replace(instrumentation, "Search callsign or notes", manifest.getString("marker"))
                    await("persisted fixture entries") { main { log.uiState.value.visible.size == 2 } }
                    check(main { log.uiState.value.visible.map { it.origin }.toSet() == setOf(QsoOrigin.LOCAL_AUDIO, QsoOrigin.MANUAL) })
                    cleanup(manifest); main { log.reload() }
                    await("fixture removal reflected") { main { !log.uiState.value.busy && hash(log.uiState.value.snapshot) == manifest.getString("initial_hash") } }
                    report.put("cold_process_reopen_and_no_rearm", true).put("original_log_restored", true).put("result", "PASS")
                } else {
                    check(scenario == "logbook")
                    report.put("store", runBlocking { LogbookStoreChecks.run(context, run) })
                    val initial = snapshot(); val marker = "R25 $run"
                    manifest = JSONObject().put("run", run).put("initial_hash", hash(initial)).put("ids", JSONArray()).put("marker", marker)
                    write("logbook-fixtures", manifest)
                    check(main { model.uiState.value.station?.callsign == "1AT106" })
                    val server = main { requireNotNull(time.uiState.value.saved).host }
                    fun measure() { val m = runBlocking { UdpNtpClient(AndroidTimeSource).measure(server) }; main { time.clock.apply(m) } }
                    measure()
                    PhoneNavigation.click(instrumentation, main { model.uiState.value.outputs.single().label })
                    PhoneNavigation.click(instrumentation, "Calibrate speaker")
                    await("actual speaker calibration") { main { !model.uiState.value.busy && model.uiState.value.latency != null } }
                    PhoneNavigation.click(instrumentation, "Start RX")
                    await("real microphone") { main { input.uiState.value.phase == CapturePhase.CAPTURING && input.uiState.value.decodeLive } }
                    val start = System.currentTimeMillis()
                    write("logbook-ready", JSONObject().put("run", run).put("stage", "answering"))
                    await("actual CQ", 45000) { main { input.uiState.value.live.rows.any { (it.decode.message as? UnpackResult.Decoded)?.message?.text == "CQ 26AT101" } } }
                    PhoneNavigation.click(instrumentation, "Use peer")
                    PhoneNavigation.click(instrumentation, "Auto answer")
                    await("AutoSeq started") { main { model.uiState.value.autoSequence.active } }
                    fun progress(): JSONObject = main {
                        val value = model.uiState.value; val sends = JSONArray()
                        value.sent.filter { it.utcMillis >= start }.sortedBy { it.utcMillis }.forEach { sent ->
                            sends.put(JSONObject().put("utc_millis", sent.utcMillis).put("text", sent.text).put("payload77", sent.payload.toBitString())
                                .put("frequency_hz", sent.frequencyHz).put("frames", sent.frames))
                        }
                        JSONObject().put("run", run).put("sent", sends).put("active", value.autoSequence.active)
                    }
                    val until = SystemClock.elapsedRealtime() + 210000
                    do {
                        write("logbook-progress", progress())
                        check(main { model.uiState.value.error == null && input.uiState.value.error == null }) { main { model.uiState.value.error ?: input.uiState.value.error.orEmpty() } }
                        check(SystemClock.elapsedRealtime() < until) { "Acoustic answer exceeded deadline" }; SystemClock.sleep(50)
                    } while (main { model.uiState.value.autoSequence.active })
                    val completed = progress(); write("logbook-progress", completed)
                    check(completed.getJSONArray("sent").length() == 3)
                    check(main { model.uiState.value.qso?.phase == CbQsoPhase.COMPLETE && !model.uiState.value.armed && !model.uiState.value.busy })
                    report.put("roles", JSONArray().put(JSONObject().put("role", "answering").put("sent", completed.getJSONArray("sent"))))
                    await("durable completion candidate") { main { !log.uiState.value.busy && log.uiState.value.snapshot.pending.any { it.id !in initial.pending.map(QsoCompletion::id) } } }
                    val pending = snapshot().pending.filter { it.id !in initial.pending.map(QsoCompletion::id) }.single()
                    val reviewLabel = "Review ${pending.details.peerCall} exchange at ${java.time.Instant.ofEpochMilli(pending.details.endedUtcMillis)}"
                    fixture(manifest, pending.id)
                    check(snapshot().records == initial.records)
                    PhoneNavigation.open(instrumentation, "Logbook")
                    PhoneNavigation.click(instrumentation, reviewLabel); PhoneNavigation.click(instrumentation, "Cancel")
                    check(snapshot().pending.any { it.id == pending.id })
                    PhoneNavigation.click(instrumentation, reviewLabel); PhoneNavigation.click(instrumentation, "Save QSO")
                    check(main { log.uiState.value.error?.contains("Confirm") == true })
                    PhoneNavigation.replace(instrumentation, "Notes", "$marker automatic")
                    PhoneNavigation.click(instrumentation, "Confirm actual radio dial frequency")
                    raw { it.execSQL("CREATE TRIGGER \"$trigger\" BEFORE INSERT ON qsos BEGIN SELECT RAISE(ABORT,'controlled test failure'); END") }
                    PhoneNavigation.click(instrumentation, "Save QSO")
                    await("real failed write") { main { !log.uiState.value.busy && log.uiState.value.error != null } }
                    check(snapshot().records == initial.records && snapshot().pending.any { it.id == pending.id })
                    raw { it.execSQL("DROP TRIGGER \"$trigger\"") }
                    PhoneNavigation.click(instrumentation, "Save QSO")
                    await("one saved completion") { main { !log.uiState.value.busy && log.uiState.value.draft == null } }
                    check(snapshot().records.count { it.completionId == pending.id } == 1)
                    PhoneNavigation.replace(instrumentation, "Search callsign or notes", "$marker automatic")
                    PhoneNavigation.click(instrumentation, "Edit QSO")
                    PhoneNavigation.replace(instrumentation, "Notes", "$marker automatic edited")
                    PhoneNavigation.click(instrumentation, "Confirm actual radio dial frequency"); PhoneNavigation.click(instrumentation, "Save QSO")
                    await("edited in place") { main { !log.uiState.value.busy && log.uiState.value.draft == null } }
                    check(snapshot().records.single { it.id == pending.id }.version == 2)
                    PhoneNavigation.replace(instrumentation, "Search callsign or notes", "missing-$run")
                    await("empty search") { visible("No QSOs match this search.") }
                    PhoneNavigation.click(instrumentation, "Add manual QSO")
                    PhoneNavigation.replace(instrumentation, "Peer CB callsign", "14AT101"); PhoneNavigation.click(instrumentation, "Cancel")
                    check(snapshot().records.size == initial.records.size + 1)
                    PhoneNavigation.click(instrumentation, "Add manual QSO")
                    val manualId = main { requireNotNull(log.uiState.value.draft).id }; fixture(manifest, manualId)
                    PhoneNavigation.replace(instrumentation, "Peer CB callsign", "14AT101")
                    PhoneNavigation.replace(instrumentation, "Notes", "$marker manual")
                    val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                    main { current.recreate() }; current = requireNotNull(monitor.waitForActivityWithTimeout(6000)) as MainActivity
                    activity = current; instrumentation.removeMonitor(monitor)
                    check(main { log.uiState.value.draft?.id == manualId && log.uiState.value.draft?.peerCall == "14AT101" })
                    PhoneNavigation.click(instrumentation, "Confirm actual radio dial frequency"); PhoneNavigation.click(instrumentation, "Save QSO")
                    await("manual saved") { main { !log.uiState.value.busy && log.uiState.value.draft == null } }
                    check(snapshot().records.single { it.id == manualId }.origin == QsoOrigin.MANUAL)
                    manifest.put("saved_ids", JSONArray(listOf(pending.id, manualId))); write("logbook-fixtures", manifest)
                    PhoneNavigation.click(instrumentation, "Operate"); measure()
                    PhoneNavigation.click(instrumentation, "Worked"); PhoneNavigation.click(instrumentation, "Start RX")
                    await("Worked microphone") { main { input.uiState.value.phase == CapturePhase.CAPTURING && input.uiState.value.decodeLive } }
                    val unworked = (999 downTo 100).map { "999ZZ$it" }.first { it !in snapshot().workedCalls("1AT106") }
                    write("logbook-ready", JSONObject().put("run", run).put("stage", "worked").put("unworked_call", unworked))
                    await("two actual CQ rows", 105000) { main { input.uiState.value.live.rows.size == 2 } }
                    await("Worked display count") { visible("Received 1/2") && visible("From 26AT101 · CB 026 · England") }
                    check(!visible("From $unworked · CB 999 · Country unknown"))
                    PhoneNavigation.click(instrumentation, "To me")
                    await("empty filtered result") { visible("Received 0/2") && visible("No messages match this filter") }
                    PhoneNavigation.click(instrumentation, "All")
                    check(main { input.uiState.value.live.rows.size == 2 && !model.uiState.value.armed && !model.uiState.value.autoSequence.active })
                    PhoneNavigation.click(instrumentation, "Stop")
                    await("microphone released") { main { input.uiState.value.phase == CapturePhase.IDLE } }
                    report.put("single_actual_completion", true).put("review_cancel_and_dial_validation", true)
                        .put("real_write_failure_retry", true).put("edit_search_manual_and_recreation", true)
                        .put("actual_worked_reception_and_backend_unchanged", true).put("result", "RESTART_READY")
                    preserveForRestart = true
                }
            }
            output.putString("logbook_result", report.getString("result")); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("failure", "${error.javaClass.simpleName}: ${error.message}").put("trace", error.stackTraceToString())
            output.putString("logbook_failure", error.message)
        } finally {
            operating?.let { main { it.stop() } }; capture?.let { main { it.stop(clear = true) } }
            if (!preserveForRestart && report.optString("result") == "FAIL" && manifest != null) {
                runCatching { cleanup(requireNotNull(manifest)) }.exceptionOrNull()?.let { report.put("cleanup_failure", it.message) }
            }
            activity?.let { main { it.finish() } }
            write(if (scenario == "logbook-reopen") "logbook-reopen-report" else "logbook-report", report)
        }
        instrumentation.finish(code, output)
    }
}
