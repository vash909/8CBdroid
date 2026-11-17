package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.core.logbook.*
import net.vash.ft8cbdroid.logbook.*
import net.vash.ft8cbdroid.receive.EightCbApplication
import org.json.JSONArray
import org.json.JSONObject

/** Actual SQLite, SAF picker, separate-UID document provider and granted share receiver. No audio or RF. */
class LogTransferPhoneChecks(private val instrumentation: Instrumentation, private val run: String, private val scenario: String) {
    private val context get() = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as EightCbApplication).receiver
    private val repository get() = SqliteQsoLogRepository.get(context)
    private val vm get() = runtime.logTransfer
    private val documents by lazy { AndroidLogDocuments(context) }
    private val provider = "content://net.vash.ft8cbdroid.test.logfiles/"
    private val checkpoint get() = File(context.cacheDir, "log-transfer-checkpoint.json")
    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null; instrumentation.runOnMainSync { value = runCatching(block) }
        return requireNotNull(value).getOrThrow()
    }
    private fun await(label: String, millis: Long = 12000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (!predicate()) { check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }; SystemClock.sleep(50) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("timeout 5 $command")).use { it.readBytes() }
    private fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
    private fun snapshot() = runBlocking { repository.read() }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun own(manifest: JSONObject, id: String) {
        manifest.getJSONArray("ids").put(id); checkpoint.writeText(manifest.toString())
    }
    private fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(SqliteQsoLogRepository.DATABASE_NAME).path,
        null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun cleanup(manifest: JSONObject) {
        val ids = manifest.getJSONArray("ids")
        raw { db ->
            db.beginTransaction()
            try {
                repeat(ids.length()) { index ->
                    val id = ids.getString(index)
                    db.delete("qsos", "id=?", arrayOf(id)); db.delete("pending_qsos", "id=?", arrayOf(id))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        main { vm.cancel(); runtime.logbook.reload() }
        check(digest(LogBackup.encode(snapshot())) == manifest.getString("before")) { "Original logical log changed" }
        checkpoint.delete()
        context.contentResolver.call(Uri.parse(provider), "cleanup", null, null)
    }
    private fun visibleText(text: String) = PhoneNavigation.root(instrumentation)?.findAccessibilityNodeInfosByText(text)?.any { it.text?.toString()?.contains(text) == true } == true
    private fun click(text: String) = PhoneNavigation.click(instrumentation, text)
    private fun pickerCancel() {
        await("external document picker") { PhoneNavigation.root(instrumentation)?.packageName?.toString()?.let { it != context.packageName } == true }
        repeat(3) {
            if (!main { vm.uiState.value.choosing }) return@repeat
            shell("input keyevent KEYCODE_BACK"); SystemClock.sleep(300)
        }
        await("picker cancellation") { main { !vm.uiState.value.choosing } }
    }
    private fun pickerRoot() {
        await("external picker") { PhoneNavigation.root(instrumentation)?.packageName?.toString()?.let { it != context.packageName } == true }
        click("Show roots"); click("R26 test logs")
    }
    private fun pickerSaveName(name: String) {
        fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || node.isEditable) return node
            repeat(node.childCount) { editable(node.getChild(it))?.let { return it } }; return null
        }
        val field = requireNotNull(editable(PhoneNavigation.root(instrumentation))) { "Save filename field missing" }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        click("Save")
        await("successful SAF export") { main { !vm.uiState.value.choosing && !vm.uiState.value.busy && vm.uiState.value.notice == "Log file saved." } }
    }
    private fun readFile(kind: LogFileKind, uri: String) {
        main { vm.choose(kind); vm.document(uri) }
        await("file preview") { main { !vm.uiState.value.busy && !vm.uiState.value.choosing } }
    }
    private fun shareCheck(bytes: ByteArray, grant: Boolean) {
        val uri = Uri.parse(runBlocking { documents.share(bytes) })
        val intent = Intent(Intent.ACTION_SEND).setComponent(ComponentName("net.vash.ft8cbdroid.test", LogShareReceiver::class.java.name))
            .setType("application/x-adif").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (grant) intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).clipData = ClipData.newRawUri("ADIF log", uri)
        main { context.startActivity(intent) }
        val expected = if (grant) "read:${digest(bytes)}" else "denied"
        await("separate UID ${if (grant) "granted" else "denied"} share") {
            context.contentResolver.call(Uri.parse(provider), "shareResult", null, null)?.getString("result") == expected
        }
    }
    fun run() {
        val report = JSONObject().put("run", run).put("device", Build.MODEL).put("android", Build.VERSION.RELEASE)
        val result = Bundle(); var code = Activity.RESULT_CANCELED; var activity: MainActivity? = null
        try {
            require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            activity = launch()
            await("log loaded") { main { runtime.logbook.uiState.value.loaded } }
            if (scenario == "log-files-cleanup") {
                cleanup(JSONObject(checkpoint.readText()).also { check(it.getString("run") == run) })
            } else if (scenario == "log-files-cold") {
                val manifest = JSONObject(checkpoint.readText()); check(manifest.getString("run") == run)
                check(digest(LogBackup.encode(snapshot())) == manifest.getString("after"))
                check(main { vm.uiState.value.preview == null && !vm.uiState.value.committing && !runtime.manual.uiState.value.hasTxOperation })
                report.put("cold_process_exact_saved_pending_and_imported_provenance", true)
                cleanup(manifest); report.put("original_log_restored_by_owned_ids", true)
            } else {
                check(!checkpoint.exists()) { "Previous log-transfer fixtures need cleanup first" }
                val manifest = JSONObject().put("run", run).put("ids", JSONArray()).put("before", digest(LogBackup.encode(snapshot())))
                checkpoint.writeText(manifest.toString())
                report.put("sqlite", runBlocking { LogbookStoreChecks.run(context, run) })
                val detail = QsoDetails("1AT106", "26AT101", 1794873585123, 1794873630789, "JN44", "IO91", 27_265_125, -7, 3, "R26 $run café <EOR> 😎")
                val record = QsoRecord("adif-local-$run", "adif-complete-$run", 3, QsoOrigin.LOCAL_AUDIO, detail, 1500, QsoTimeBasis.CORRECTED_APP_UTC)
                val pending = QsoCompletion("adif-pending-$run", detail.copy(peerCall = "161AT101"), 1200)
                own(manifest, record.id); own(manifest, pending.id)
                runBlocking { val before = repository.read(); repository.merge(LogMergeCommand(before, QsoLogSnapshot(listOf(record), listOf(pending)), checkDuplicates = false)) }
                main { runtime.logbook.reload() }; await("seeded log") { main { runtime.logbook.uiState.value.snapshot.records.any { it.id == record.id } } }
                // Backend write/read and complete backup restoration preserve provenance and pending reviews.
                val desktop = Adif.decode(instrumentation.context.assets.open("desktop.adi").use { it.readBytes() })
                check(desktop.issues.isEmpty() && desktop.records.size == 2)
                check(desktop.records.first().details.dialHz == 27_265_125L && desktop.records.first().details.peerCall == "26AT101")
                report.put("compiled_pinned_desktop_adi_import_on_phone", true)
                val original = snapshot(); val backup = LogBackup.encode(original)
                runBlocking { documents.write(provider + "backup", backup) }
                check(runBlocking { documents.read(provider + "backup") }.contentEquals(backup))
                val restored = LogBackup.decode(backup)
                check(restored.records.toSet() == original.records.toSet() && restored.pending.toSet() == original.pending.toSet())
                report.put("real_provider_full_backup_bytes_and_metadata", true)
                val adi = Adif.encode(listOf(record), AdifBandPolicy.WSJT_CB_11M)
                runBlocking { documents.write(provider + "adif", adi) }
                check(runBlocking { documents.read(provider + "adif") }.contentEquals(adi))
                File(context.cacheDir, "log-transfer-export.adi").writeBytes(adi)
                // A blocked provider pipe must cancel promptly, without changing the log.
                runBlocking {
                    val job = async { documents.read(provider + "stall") }; delay(250)
                    val start = SystemClock.elapsedRealtime(); job.cancelAndJoin()
                    check(SystemClock.elapsedRealtime() - start < 2000)
                }
                check(snapshot() == original); report.put("blocked_pipe_cancellation_bounded", true)
                PhoneNavigation.open(instrumentation, "Logbook")
                click("Import ADIF"); pickerCancel(); check(snapshot() == original)
                click("Export ADIF"); pickerRoot(); pickerSaveName("export-$run.adi")
                report.put("actual_create_document_export", true)
                click("Import ADIF"); pickerRoot(); click("desktop.adi")
                await("actual open document preview") { main { vm.uiState.value.preview != null && !vm.uiState.value.busy } }
                check(main { vm.uiState.value.preview!!.rows.size == 2 && vm.uiState.value.preview!!.issues.isEmpty() })
                main { vm.cancel() }; check(snapshot() == original)
                report.put("actual_open_document_preview_and_cancel", true)
                // Parsing a document never mutates the log; duplicate selection is initially empty.
                readFile(LogFileKind.ADIF, provider + "adif")
                check(main { vm.uiState.value.preview!!.rows.single().status == LogEntryStatus.DUPLICATE && vm.uiState.value.selected.isEmpty() })
                val importedId = main { vm.uiState.value.preview!!.rows.single().id }; own(manifest, importedId)
                click("Confirm reviewed callsigns and frequencies")
                check(main { !vm.uiState.value.canApply }); check(snapshot() == original)
                click("Select 26AT101 at ${java.time.Instant.ofEpochMilli(detail.startedUtcMillis / 1000 * 1000)}")
                check(main { !vm.uiState.value.reviewed }); click("Confirm reviewed callsigns and frequencies")
                click("Import selected"); await("reviewed import saved") { main { !vm.uiState.value.busy && vm.uiState.value.preview == null } }
                var imported = snapshot().records.single { it.id == importedId }
                check(imported.origin == QsoOrigin.ADIF_IMPORT && imported.timeBasis == QsoTimeBasis.IMPORTED_UTC && imported.txAudioHz == null)
                check(imported.details.notes == detail.notes && imported.details.dialHz == detail.dialHz)
                report.put("actual_ui_duplicate_choice_review_and_import", true)
                await("imported row available for editing") { main { !runtime.logbook.uiState.value.busy && runtime.logbook.uiState.value.snapshot.records.any { it.id == importedId } } }
                main { runtime.logbook.editRecord(importedId) }
                PhoneNavigation.replace(instrumentation, "Notes", "Reviewed imported $run")
                click("Confirm actual radio dial frequency"); click("Save QSO")
                await("imported edit saved") { main { !runtime.logbook.uiState.value.busy && runtime.logbook.uiState.value.draft == null } }
                imported = snapshot().records.single { it.id == importedId }
                check(imported.origin == QsoOrigin.ADIF_IMPORT && imported.timeBasis == QsoTimeBasis.IMPORTED_UTC && imported.version == 2)
                report.put("imported_edit_keeps_source_and_time_basis", true)
                // Malformed structure cannot leave a partial import; bad rows are reported independently.
                runBlocking { documents.write(provider + "broken", adi + "<CALL:999>bad".toByteArray()) }
                val afterImport = snapshot(); readFile(LogFileKind.ADIF, provider + "broken")
                check(main { vm.uiState.value.error != null && vm.uiState.value.preview == null }); check(snapshot() == afterImport)
                val unsupported = adi.toString(Charsets.US_ASCII).replace("<MODE:3>FT8", "<MODE:3>FT4").toByteArray()
                // Use headerless records when concatenating ADIF rows.
                val partial = unsupported + adi.copyOfRange(adi.toString(Charsets.US_ASCII).indexOf("<CALL:"), adi.size)
                runBlocking { documents.write(provider + "partial", partial) }
                readFile(LogFileKind.ADIF, provider + "partial")
                check(main { vm.uiState.value.preview!!.issues.size == 1 && vm.uiState.value.preview!!.rows.size == 1 })
                main { vm.cancel() }; check(snapshot() == afterImport)
                report.put("malformed_no_partial_write_and_indexed_rejected_rows", true)
                // Preserve one accepted frozen export across a denied write and real destination retry.
                main { vm.prepareExport(LogFileKind.BACKUP) }; await("backup destination") { main { vm.uiState.value.choosing } }
                main { vm.document(provider + "fail") }; await("provider denied export") { main { !vm.uiState.value.busy && vm.uiState.value.retryExport } }
                if (PhoneNavigation.root(instrumentation)?.packageName?.toString() != context.packageName) shell("input keyevent KEYCODE_BACK")
                main { vm.retryExport(); vm.document(provider + "retry") }; await("provider retry") { main { !vm.uiState.value.busy && vm.uiState.value.notice == "Log file saved." } }
                check(LogBackup.decode(runBlocking { documents.read(provider + "retry") }).records.toSet() == snapshot().records.toSet())
                report.put("denied_destination_retry_frozen_snapshot", true)
                // Restore missing owned rows from a complete backup, preserving IDs/versions and existing log.
                val full = LogBackup.encode(snapshot()); runBlocking { documents.write(provider + "restore", full) }
                raw { db -> db.delete("qsos", "id=?", arrayOf(record.id)); db.delete("pending_qsos", "id=?", arrayOf(pending.id)) }
                readFile(LogFileKind.BACKUP, provider + "restore")
                check(main { vm.uiState.value.selected == setOf(record.id, pending.id) })
                val review = main { vm.uiState.value.preview }
                main { activity!!.recreate() }; SystemClock.sleep(600)
                check(main { vm.uiState.value.preview == review && !vm.uiState.value.reviewed })
                main { vm.reviewed(true); vm.apply() }; await("restored entries") { main { !vm.uiState.value.busy } }
                check(LogBackup.encode(snapshot()).contentEquals(full))
                readFile(LogFileKind.BACKUP, provider + "restore")
                check(main { vm.uiState.value.selected.isEmpty() && vm.uiState.value.preview!!.rows.all { it.status == LogEntryStatus.EXISTING } })
                main { vm.cancel() }; report.put("restore_missing_saved_pending_exact_ids_versions_recreation_and_repeat_skip", true)
                shareCheck(adi, false); shareCheck(adi, true); report.put("separate_uid_denied_without_grant_and_exact_bytes_with_grant", true)
                launch(); PhoneNavigation.open(instrumentation, "Logbook")
                click("Share ADIF")
                await("Android share sheet") { PhoneNavigation.root(instrumentation)?.packageName?.toString()?.let { it != context.packageName } == true }
                shell("input keyevent KEYCODE_BACK")
                check(snapshot().records.single { it.id == importedId } == imported)
                manifest.put("after", digest(LogBackup.encode(snapshot()))); checkpoint.writeText(manifest.toString())
                report.put("actual_share_sheet_cancellation_non_mutating", true)
            }
            report.put("result", "PASS"); result.putString("log_files_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("error", "${error.javaClass.simpleName}: ${error.message}")
            result.putString("log_files_result", report.toString()); error.printStackTrace()
        } finally {
            main { vm.cancel() }
            activity?.let { runCatching { main { it.finish() } } }
            File(context.cacheDir, "log-transfer-$scenario.json").writeText(report.toString(2))
        }
        instrumentation.finish(code, result)
    }
}
