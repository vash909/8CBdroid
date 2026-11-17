package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.core.diagnostics.*
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.diagnostics.AndroidDiagnosticsArchive
import net.vash.ft8cbdroid.receive.*
import org.json.JSONObject
import java.io.File

/** Actual graph, storage, document provider, receiver and lifecycle; no synthetic resource measurements. */
class DiagnosticsPhoneChecks(private val instrumentation: Instrumentation, private val run: String, private val scenario: String, private val radioInput: Boolean = false) {
    private val context get() = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as EightCbApplication).receiver
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, millis: Long = 12000, test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + millis
        while (!test()) { check(SystemClock.elapsedRealtime() < until) { "Timed out: $label" }; SystemClock.sleep(50) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("timeout 5 $command")).use { it.readBytes() }
    private fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
    private fun marker(stage: String, extra: JSONObject = JSONObject()) {
        File(context.cacheDir, "diagnostics-ready.json").writeText(extra.put("run", run).put("stage", stage).toString())
    }
    private fun exportCheck(): JSONObject {
        val vm = runtime.diagnostics
        main { check(vm.prepareExport()); vm.export("content://net.vash.ft8cbdroid.test.r07/fail") }
        await("real provider error") { main { vm.uiState.value.error != null && !vm.uiState.value.exporting } }
        main { check(vm.prepareExport()); vm.export("content://net.vash.ft8cbdroid.test.r07/success") }
        await("provider retry") { main { vm.uiState.value.notice == "Diagnostic report exported." && !vm.uiState.value.exporting } }
        val bytes = context.contentResolver.openInputStream(Uri.parse("content://net.vash.ft8cbdroid.test.r07/success"))!!.use { it.readBytes() }
        check(bytes.size <= AndroidDiagnosticsArchive.MAX_BYTES)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        check(json.getInt("schema") == 1 && json.getJSONArray("samples").length() <= SessionDiagnostics.MAX_SAMPLES)
        // Privacy-sensitive production state must never enter the report document.
        val station = main { runtime.station.uiState.value.saved }
        station?.callsign?.takeIf { it.isNotBlank() }?.let { check(!bytes.toString(Charsets.UTF_8).contains(it)) }
        station?.grid?.takeIf { it.isNotBlank() }?.let { check(!bytes.toString(Charsets.UTF_8).contains(it)) }
        val savedHost = main { runtime.time.uiState.value.saved?.host }
        savedHost?.let { check(!bytes.toString(Charsets.UTF_8).contains(it)) }
        File(context.cacheDir, "diagnostics-export.json").writeBytes(bytes)
        return json
    }
    fun run() {
        val result = Bundle(); val report = JSONObject().put("run", run).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
        var code = Activity.RESULT_CANCELED; var activity: MainActivity? = null; var ownsReport = false
        var preserveCheckpoint = false
        val originalLan = main { runtime.lan.uiState.value }; val originalSource = main { runtime.profiles.uiState.value.receiveLan }
        try {
            require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            activity = launch()
            val vm = main { runtime.diagnostics }
            await("saved graph") { main { !vm.uiState.value.loading && runtime.time.uiState.value.saved != null && !runtime.station.uiState.value.isLoading } }
            if (scenario == "diagnostics-cold") {
                check(File(context.cacheDir, "diagnostics-owned-run.txt").readText() == run)
                check(main { vm.uiState.value.report?.end == DiagnosticEnd.INTERRUPTED && !vm.uiState.value.active })
                check(main { !runtime.session.uiState.value.active && !runtime.manual.uiState.value.hasTxOperation })
                ownsReport = true
                report.put("interrupted_checkpoint_no_collection_rx_or_tx_restart", true)
                exportCheck()
            } else {
                check(main { vm.uiState.value.canStart }) { "Existing diagnostic report must be preserved; export/delete it explicitly before this bench test." }
                PhoneNavigation.open(instrumentation, "Session diagnostics")
                PhoneNavigation.click(instrumentation, "Start diagnostics"); ownsReport = true
                File(context.cacheDir, "diagnostics-owned-run.txt").writeText(run)
                await("initial private checkpoint") { main { vm.uiState.value.report?.samples?.isNotEmpty() == true } }
                check(File(context.noBackupFilesDir, "session-diagnostics.json").exists())
                check(main { !runtime.session.uiState.value.active && !runtime.manual.uiState.value.hasTxOperation })
                report.put("explicit_opt_in_adds_no_rx_or_tx", true)
                if (scenario == "diagnostics-interrupt") {
                    preserveCheckpoint = true
                    report.put("unfinished_checkpoint_saved_for_cold_process", true)
                } else if (scenario == "diagnostics-smoke") {
                    PhoneNavigation.click(instrumentation, "Chat")
                    val before = main { vm.uiState.value.report }
                    main { requireNotNull(activity).finish() }; activity = launch()
                    check(main { runtime.diagnostics === vm && vm.uiState.value.active && vm.uiState.value.report == before })
                    PhoneNavigation.open(instrumentation, "Session diagnostics")
                    PhoneNavigation.click(instrumentation, "Export diagnostic report")
                    SystemClock.sleep(500); shell("input keyevent KEYCODE_BACK")
                    await("picker cancelled") { main { !vm.uiState.value.choosing } }
                    check(main { vm.uiState.value.notice == "No diagnostic report exported." })
                    exportCheck()
                    PhoneNavigation.click(instrumentation, "Stop diagnostics")
                    await("diagnostic Stop checkpoint") { main { !vm.uiState.value.active && !vm.uiState.value.busy } }
                    val stored = runBlocking { AndroidDiagnosticsArchive(context).read() }
                    check(stored == main { vm.uiState.value.report })
                    val checkpoint = File(context.noBackupFilesDir, "session-diagnostics.json")
                    val originalBytes = checkpoint.readBytes()
                    try {
                        checkpoint.writeText("invalid diagnostic JSON")
                        check(runCatching { runBlocking { AndroidDiagnosticsArchive(context).read() } }.isFailure)
                        checkpoint.writeBytes(ByteArray(AndroidDiagnosticsArchive.MAX_BYTES + 1))
                        check(runCatching { runBlocking { AndroidDiagnosticsArchive(context).read() } }.isFailure)
                    } finally { checkpoint.writeBytes(originalBytes) }
                    check(runBlocking { AndroidDiagnosticsArchive(context).read() } == stored)
                    report.put("malformed_and_oversize_checkpoint_rejected_with_verified_restore", true)
                    PhoneNavigation.click(instrumentation, "Delete diagnostic report")
                    PhoneNavigation.click(instrumentation, "Cancel")
                    check(File(context.noBackupFilesDir, "session-diagnostics.json").exists())
                    PhoneNavigation.click(instrumentation, "Delete diagnostic report")
                    PhoneNavigation.click(instrumentation, "Delete report")
                    await("confirmed deletion") { main { vm.uiState.value.canStart } }
                    check(!File(context.noBackupFilesDir, "session-diagnostics.json").exists()); ownsReport = false
                    report.put("actual_navigation_recreation_picker_cancel_provider_retry_stop_roundtrip_and_confirmed_delete", true)
                } else if (scenario == "diagnostics-lan") {
                    await("saved profile") { main { runtime.profiles.uiState.value.ready } }
                    if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
                    main { runtime.lan.changeAddress("") }
                    PhoneNavigation.open(instrumentation, "Audio & source")
                    PhoneNavigation.click(instrumentation, "WSJT-CB LAN")
                    PhoneNavigation.click(instrumentation, "Operate"); PhoneNavigation.click(instrumentation, "Start RX")
                    await("LAN service") { main { runtime.session.uiState.value.phase == ReceivePhase.RUNNING } }
                    marker("lan", JSONObject().put("port", main { runtime.lan.uiState.value.port.toInt() }))
                    shell("input keyevent KEYCODE_HOME")
                    val started = SystemClock.elapsedRealtime()
                    while (SystemClock.elapsedRealtime() - started < 90_000) {
                        check(main { runtime.lan.uiState.value.active && runtime.session.uiState.value.error == null })
                        SystemClock.sleep(1000)
                    }
                    check(main { runtime.lan.uiState.value.connection.decodes.isNotEmpty() })
                    main { runtime.stop() }; await("LAN Stop") { main { !runtime.session.uiState.value.active } }
                    main { vm.stop() }; await("LAN report Stop") { main { !vm.uiState.value.busy } }
                    val data = exportCheck(); val samples = data.getJSONArray("samples")
                    check((0 until samples.length()).count { i ->
                        samples.getJSONObject(i).getString("source") == "LAN" && samples.getJSONObject(i).getInt("retainedLanRows") > 0
                    } >= 2)
                    report.put("actual_lan_source_background_history_samples_and_stop", true)
                } else {
                    check(scenario == "diagnostics-sustained")
                    await("Phone profile") { main { runtime.profiles.uiState.value.ready && runtime.capture.uiState.value.canStart } }
                    if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
                    instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.RECORD_AUDIO)
                    main { runtime.time.measureNtp() }
                    await("actual NTP measurement", 10000) { main { runtime.time.uiState.value.busy == null } }
                    check(main { runtime.time.uiState.value.canApply }) { main { runtime.time.uiState.value.error ?: "NTP measurement unavailable" } }
                    main { runtime.time.applyMeasurement() }
                    PhoneNavigation.click(instrumentation, "Operate")
                    PhoneNavigation.click(instrumentation, "Start RX")
                    await("actual microphone RX") { main { runtime.capture.uiState.value.phase == CapturePhase.CAPTURING } }
                    val started = SystemClock.elapsedRealtime(); val revisions = mutableSetOf<Long>(); val decodedSlots = mutableSetOf<Long>()
                    marker("microphone", JSONObject().put("duration_seconds", 900))
                    PhoneNavigation.click(instrumentation, "Settings"); PhoneNavigation.click(instrumentation, "Chat")
                    shell("input keyevent KEYCODE_HOME")
                    while (SystemClock.elapsedRealtime() - started < 900_000) {
                        main {
                            check(runtime.session.uiState.value.phase == ReceivePhase.RUNNING && runtime.session.uiState.value.error == null) {
                                runtime.session.uiState.value.error ?: "Receive session stopped"
                            }
                            check(runtime.capture.uiState.value.error == null) { requireNotNull(runtime.capture.uiState.value.error) }
                            check(!runtime.manual.uiState.value.hasTxOperation)
                            revisions.add(runtime.time.clock.revision)
                            runtime.capture.uiState.value.live.rows.forEach { row ->
                                if ((row.decode.message as? UnpackResult.Decoded)?.message?.text != null)
                                    decodedSlots.add(row.decode.slotStartUtc.toEpochMilli())
                            }
                        }
                        if (SystemClock.elapsedRealtime() - started >= 120_000 && report.optBoolean("screen_off") != true) {
                            shell("input keyevent KEYCODE_SLEEP")
                            check(!context.getSystemService(android.os.PowerManager::class.java).isInteractive)
                            report.put("screen_off", true)
                        }
                        SystemClock.sleep(1000)
                    }
                    check(revisions.size >= 3) { "Two real periodic time updates were not observed" }
                    check(decodedSlots.size >= if (radioInput) 1 else 8) { "Insufficient actual FT8 decodes for the selected sustained input" }
                    val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == ReceiveService.NOTIFICATION }.notification
                    requireNotNull(notification.actions).single { it.title.toString() == "Stop RX" }.actionIntent.send()
                    await("notification receiver Stop") { main { !runtime.session.uiState.value.active && runtime.capture.uiState.value.phase == CapturePhase.IDLE } }
                    main { vm.stop() }
                    await("final checkpoint") { main { !vm.uiState.value.active && !vm.uiState.value.busy } }
                    val data = exportCheck()
                    check(data.getJSONArray("samples").length() >= 30)
                    check(data.getJSONObject("events").getLong("DECODE_COMPLETE") >= 45)
                    check(data.getJSONObject("events").optLong("INPUT_FAILED") == 0L)
                    report.put("duration_millis", SystemClock.elapsedRealtime() - started).put("decoded_ft8_slots", decodedSlots.size).put("radio_input", radioInput)
                        .put("time_revisions", revisions.size).put("actual_microphone_screen_off_and_notification_stop", true)
                    shell("input keyevent KEYCODE_WAKEUP")
                }
            }
            report.put("result", "PASS"); code = Activity.RESULT_OK; result.putString("diagnostics_result", "PASS")
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("failure", "${error.javaClass.simpleName}: ${error.message}")
            result.putString("diagnostics_failure", "${error.message}; ${error.stackTrace.take(5)}")
        } finally {
            if (report.optString("result") == "FAIL") runCatching {
                val file = File(context.noBackupFilesDir, "session-diagnostics.json")
                if (file.exists()) File(context.cacheDir, "diagnostics-failed-checkpoint.json").writeBytes(file.readBytes())
            }
            if (!preserveCheckpoint) {
                if (ownsReport) { main { runtime.diagnostics.delete() }; await("bench report cleanup") { main { !runtime.diagnostics.uiState.value.busy } } }
                main { runtime.stop() }
                await("final receive cleanup") { main { !runtime.session.uiState.value.active } }
                main {
                    runtime.profiles.temporarySource(originalSource)
                    runtime.lan.changeAddress(originalLan.desktopAddress); runtime.lan.changePort(originalLan.port)
                }
                File(context.cacheDir, "diagnostics-owned-run.txt").delete()
            }
            activity?.let { main { it.finish() } }
            File(context.cacheDir, "diagnostics-phone-report.json").writeText(report.toString(2))
        }
        instrumentation.finish(code, result)
    }
}
