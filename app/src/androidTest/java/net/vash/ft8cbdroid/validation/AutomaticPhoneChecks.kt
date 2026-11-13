package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.core.audio.SpectrumState
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.core.qso.CbQsoPhase
import net.vash.ft8cbdroid.ntp.UdpNtpClient
import net.vash.ft8cbdroid.operate.ManualOperatingViewModel
import net.vash.ft8cbdroid.profile.ProfilesViewModel
import net.vash.ft8cbdroid.time.AndroidTimeSource
import net.vash.ft8cbdroid.time.TimeViewModel
import org.json.JSONArray
import org.json.JSONObject

/** Actual automatic exchanges, including continuous session lifecycle qualification. The host supplies/repeats/drops desktop audio, never model callbacks. */
class AutomaticPhoneChecks(
    private val instrumentation: Instrumentation,
    private val run: String,
    private val timeOnly: Boolean = false,
    private val continuous: Boolean = false,
) {
    private val context get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return requireNotNull(value).getOrThrow()
    }
    private fun await(label: String, millis: Long = 12000, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (!test()) { check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }; SystemClock.sleep(20) }
    }
    private fun publish(name: String, value: JSONObject) {
        val temporary = File(context.cacheDir, "$name.tmp"); temporary.writeText(value.toString())
        check(temporary.renameTo(File(context.cacheDir, "$name.json")))
    }
    private fun stage(name: String) {
        publish("autoseq-ready", JSONObject().put("run", run).put("stage", name))
        await("host $name", 18000) { File(context.cacheDir, "autoseq-go-$run-$name").exists() }
    }
    private fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("timeout 5 $command")).use { it.readBytes().decodeToString() }
    private fun stopFromNotification(title: String) {
        val notification = context.getSystemService(android.app.NotificationManager::class.java).activeNotifications
            .single { it.id == net.vash.ft8cbdroid.receive.ReceiveService.NOTIFICATION }.notification
        requireNotNull(notification.actions).single { it.title == title }.actionIntent.send()
    }
    fun run() {
        var current: MainActivity? = null
        var operating: ManualOperatingViewModel? = null
        var capture: CaptureViewModel? = null
        var observer: Job? = null
        var fixtures: AcousticLogFixtures? = null
        val result = Bundle(); val report = JSONObject(); var code = Activity.RESULT_CANCELED
        try {
            require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            report.put("run", run).put("result", "RUNNING").put("scope", if (timeOnly) "time-recovery" else if (continuous) "continuous" else "full")
            var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            current = activity
            val model = main { activity.receiveRuntime.manual }; operating = model
            fixtures = AcousticLogFixtures(context, run)
            val input = main { activity.receiveRuntime.capture }; capture = input
            val time = main { activity.receiveRuntime.time }
            val profiles = main { activity.receiveRuntime.profiles }
            val observedReceives = ConcurrentHashMap<String, JSONObject>()
            observer = main { CoroutineScope(Dispatchers.Main.immediate).launch {
                input.uiState.collect { rx -> rx.live.rows.forEach { row ->
                    val text = (row.decode.message as? UnpackResult.Decoded)?.message?.text ?: return@forEach
                    val utc = row.decode.slotStartUtc.toEpochMilli()
                    observedReceives["$utc:$text"] = JSONObject().put("utc_millis", utc).put("text", text).put("captured_samples", row.capturedSamples)
                } }
            } }
            await("Phone profile and time settings") { main { profiles.uiState.value.ready && model.uiState.value.available && time.uiState.value.saved != null } }
            check(main { model.uiState.value.station?.callsign == "1AT106" }) { "Save station 1AT106 and a Phone profile first." }
            val server = main { requireNotNull(time.uiState.value.saved).host }
            val measured = runBlocking { UdpNtpClient(AndroidTimeSource).measure(server) }
            main { time.clock.apply(measured) }
            val initialRevision = time.clock.revision
            val clockEvents = JSONArray(); var previousRevision = initialRevision
            if (!timeOnly) PhoneNavigation.click(instrumentation, main { model.uiState.value.outputs.single().label })
            fun idle() = await("audio released") { main { !model.uiState.value.busy && input.uiState.value.phase == CapturePhase.IDLE && !activity.receiveRuntime.session.uiState.value.active } }
            fun receiving() = await("live microphone") { main {
                check(input.uiState.value.error == null) { input.uiState.value.error.orEmpty() }
                input.uiState.value.phase == CapturePhase.CAPTURING && input.uiState.value.decodeLive
            } }
            fun prepare(direct: Boolean = false) {
                main { model.stop(); input.stop(clear = true) }; idle()
                if (main { model.uiState.value.outputId == null }) {
                    if (direct) main { model.output(model.uiState.value.outputs.single().id) }
                    else PhoneNavigation.click(instrumentation, main { model.uiState.value.outputs.single().label })
                }
                if (direct) main { model.calibrate() } else PhoneNavigation.click(instrumentation, "Calibrate speaker")
                await("speaker timing") { main { !model.uiState.value.busy && model.uiState.value.latency != null } }
                if (direct) main { input.decodeLive(true); input.assembleUtc(true); input.requestStart(true) }
                else PhoneNavigation.click(instrumentation, "Start RX")
                receiving()
            }
            fun measureTimeFromUi() {
                fun measure() {
                    PhoneNavigation.click(instrumentation, "Measure NTP")
                    await("NTP request completed") { main { time.uiState.value.busy == null } }
                }
                measure()
                if (main { time.uiState.value.error?.startsWith("Wait ") == true }) {
                    SystemClock.sleep(if (server == "ntp.nict.jp") 257000 else 65000)
                    measure()
                }
                check(main { time.uiState.value.error == null && time.uiState.value.measurement != null }) { main { time.uiState.value.error.orEmpty() } }
                PhoneNavigation.click(instrumentation, "Apply measured time")
                check(main { time.uiState.value.refreshWarning == null && !model.uiState.value.autoSequence.active && !model.uiState.value.armed })
                previousRevision = time.clock.revision
            }
            var foregroundTypes: Int? = null
            fun progress(role: String, since: Long): JSONObject = main {
                val value = model.uiState.value; val rx = input.uiState.value
                val sends = JSONArray()
                value.sent.filter { it.utcMillis >= since }.sortedBy { it.utcMillis }.forEach { sent ->
                    sends.put(JSONObject().put("utc_millis", sent.utcMillis).put("text", sent.text)
                        .put("payload77", sent.payload.toBitString()).put("frequency_hz", sent.frequencyHz).put("frames", sent.frames))
                }
                val rows = JSONArray()
                rx.live.rows.forEach { row -> rows.put(JSONObject().put("utc_millis", row.decode.slotStartUtc.toEpochMilli())
                    .put("text", (row.decode.message as? UnpackResult.Decoded)?.message?.text)
                    .put("captured_samples", row.capturedSamples)) }
                if (time.clock.revision != previousRevision) {
                    check(value.queuedUtcMillis == null || value.queuedTimeRevision == time.clock.revision) { "Clock moved while output was queued or playing" }
                    clockEvents.put(JSONObject().put("revision", time.clock.revision).put("utc_millis", System.currentTimeMillis()))
                    previousRevision = time.clock.revision
                }
                JSONObject().put("run", run).put("role", role).put("active", value.autoSequence.active)
                    .put("android_api", android.os.Build.VERSION.SDK_INT).put("foreground_service_types", foregroundTypes)
                    .put("reason", value.autoSequence.reason).put("qso", value.qso?.phase?.name)
                    .put("sent", sends).put("rows", rows).put("last_slot_utc", rx.live.lastSlotUtcMillis)
                    .put("receive_started_utc", rx.receiveStartedUtcMillis).put("error", value.error ?: rx.error)
            }
            val roles = JSONArray()
            val guards = JSONArray()
            fun runGuards() {
                fun startGuard() {
                    // Exercise the real backend here; the acoustic roles below also use the UI.
                    prepare(direct = true); main { model.startAutomatic(true) }
                    await("guard AutoSeq active") { main { model.uiState.value.autoSequence.active } }
                }
                for (guard in listOf("queued-stop", "playing-stop", "edit", "clock-reset", "focus-loss", "navigation", "recreation", "service-stop", "permission-loss", "profile-browse", "profile")) {
                    stage("guard-$guard")
                    startGuard()
                    val before = main { model.uiState.value.sent.size }
                    if (guard == "playing-stop" || guard == "focus-loss") {
                        await("active automatic audio", 45000) { main { (model.uiState.value.playback?.playedFrames ?: 0) >= 24000 } }
                    } else if (guard != "navigation" && guard != "recreation") {
                        await("queued automatic output", 45000) { main { model.uiState.value.queuedUtcMillis != null } }
                    }
                    when (guard) {
                        "queued-stop", "playing-stop" -> PhoneNavigation.click(instrumentation, "Stop AutoSeq")
                        "edit" -> main { model.frequency("1100") }
                        "clock-reset" -> main { time.clock.reset() }
                        "focus-loss" -> main { activity.startActivity(Intent().setComponent(ComponentName("net.vash.ft8cbdroid.engineprobe", "net.vash.ft8cbdroid.engineprobe.PreviewFocusActivity"))) }
                        "navigation" -> PhoneNavigation.click(instrumentation, "Settings")
                        "service-stop" -> stopFromNotification("Stop session")
                        "permission-loss" -> {
                            val op = shell("cmd appops get ${context.packageName} RECORD_AUDIO")
                            val mode = requireNotNull(Regex("RECORD_AUDIO: (allow|default|foreground)").find(op)).groupValues[1]
                            val uid = if (op.contains("Uid mode:")) "--uid " else ""
                            try {
                                shell("input keyevent KEYCODE_HOME")
                                shell("cmd appops set $uid${context.packageName} RECORD_AUDIO ignore")
                                await("actual microphone revocation stops TX", 15000) { main { !model.uiState.value.autoSequence.active && !model.uiState.value.busy && !activity.receiveRuntime.session.uiState.value.active } }
                            } finally { shell("cmd appops set $uid${context.packageName} RECORD_AUDIO $mode") }
                            SystemClock.sleep(800)
                            check(main { !model.uiState.value.armed && !model.uiState.value.autoSequence.active && input.uiState.value.phase == CapturePhase.IDLE })
                            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
                            current = activity
                        }
                        "profile-browse" -> {
                            val calibration = main { model.uiState.value.latency }
                            main { profiles.loadModels() }
                            await("read-only catalog work") { main { !profiles.uiState.value.busy } }
                            check(main { profiles.uiState.value.error == null && model.uiState.value.autoSequence.active && model.uiState.value.latency == calibration })
                            stopFromNotification("Stop TX")
                        }
                        "profile" -> {
                            main { profiles.apply() }
                            await("profile application stops automation") { main { !profiles.uiState.value.busy && !model.uiState.value.autoSequence.active && !model.uiState.value.busy } }
                            check(main { model.uiState.value.outputId == null && model.uiState.value.latency == null })
                        }
                        "recreation" -> {
                            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                            main { activity.recreate() }; activity = requireNotNull(monitor.waitForActivityWithTimeout(6000)) as MainActivity
                            current = activity; instrumentation.removeMonitor(monitor)
                        }
                    }
                    if (guard == "navigation" || guard == "recreation") {
                        check(main { activity.receiveRuntime.manual === model && model.uiState.value.autoSequence.active })
                        if (guard == "navigation") PhoneNavigation.click(instrumentation, "Stop TX")
                        else stopFromNotification("Stop TX")
                    }
                    await("AutoSeq cancellation") { main { !model.uiState.value.autoSequence.active && !model.uiState.value.busy } }
                    check(main { !model.uiState.value.armed && model.uiState.value.sent.size == before })
                    SystemClock.sleep(700); check(main { !model.uiState.value.autoSequence.active })
                    guards.put(guard)
                    report.put("guards", guards); publish("autoseq-report", report)
                    if (guard == "navigation") PhoneNavigation.click(instrumentation, "Operate")
                    if (guard == "focus-loss") { SystemClock.sleep(1800); main { activity.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } }
                    if (guard == "clock-reset" || guard == "profile") {
                        if (guard == "clock-reset") {
                            await("clock reset warning") { main { time.uiState.value.refreshWarning != null } }
                            PhoneNavigation.click(instrumentation, "⚠")
                        } else PhoneNavigation.open(instrumentation, "Time synchronization")
                        measureTimeFromUi()
                        PhoneNavigation.click(instrumentation, "Operate")
                    }
                }
            }
            if (!timeOnly) {
                if (continuous) runGuards()
                for (role in if (continuous) listOf("calling", "no-response", "answering") else listOf("calling", "answering", "no-response")) {
                    prepare()
                    observedReceives.clear()
                    val start = System.currentTimeMillis()
                    stage(role)
                    if (role == "answering") {
                        await("actual CQ for explicit answer", 45000) { main { input.uiState.value.live.rows.any {
                            (it.decode.message as? UnpackResult.Decoded)?.message?.text == "CQ 26AT101" } } }
                        PhoneNavigation.click(instrumentation, "Use peer")
                    }
                    PhoneNavigation.click(instrumentation, if (role == "answering") "Auto answer" else "Auto CQ")
                    await("AutoSeq started") { main { model.uiState.value.autoSequence.active || model.uiState.value.error != null } }
                    check(main { model.uiState.value.error == null }) { main { model.uiState.value.error.orEmpty() } }
                    if (continuous) {
                        // Samsung's service dump omits this field. Read the actual Android service API once per role.
                        foregroundTypes = main {
                            val field = net.vash.ft8cbdroid.receive.ReceiveRuntime::class.java.getDeclaredField("serviceOwner").apply { isAccessible = true }
                            val service = requireNotNull(field.get(activity.receiveRuntime) as? android.app.Service)
                            if (android.os.Build.VERSION.SDK_INT >= 29) service.foregroundServiceType else 0
                        }
                        report.put("foreground_service_types", foregroundTypes).put("android_api", android.os.Build.VERSION.SDK_INT)
                        val calibration = main { model.uiState.value.latency }
                        if (role == "calling") {
                            PhoneNavigation.click(instrumentation, "Settings")
                            PhoneNavigation.click(instrumentation, "Chat")
                            val old = activity
                            main { old.finish() }
                            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                            current = activity
                            check(main { activity.receiveRuntime.manual === model && model.uiState.value.autoSequence.active && model.uiState.value.latency == calibration })
                            shell("input keyevent KEYCODE_HOME")
                            report.put("automatic_navigation_activity_destruction_and_background_continue", true)
                        } else if (role == "answering") {
                            shell("input keyevent KEYCODE_SLEEP")
                            await("screen off for automatic QSO") { !context.getSystemService(android.os.PowerManager::class.java).isInteractive }
                        } else shell("input keyevent KEYCODE_HOME")
                    }
                    val received = linkedMapOf<String, JSONObject>()
                    val until = SystemClock.elapsedRealtime() + 210_000
                    var last: JSONObject
                    do {
                        last = progress(role, start)
                        publish("autoseq-progress", last)
                        val rows = last.getJSONArray("rows")
                        repeat(rows.length()) { i -> val row = rows.getJSONObject(i); received["${row.optLong("utc_millis")}:${row.optString("text")}"] = row }
                        check(last.isNull("error")) { last.toString() }
                        check(SystemClock.elapsedRealtime() < until) { "Automatic role deadline exceeded: $last" }
                        SystemClock.sleep(30)
                    } while (last.getBoolean("active"))
                    val sends = last.getJSONArray("sent")
                    val expected = when (role) { "calling" -> 5; "answering" -> 3; else -> 3 }
                    check(sends.length() == expected) { "Unexpected $role sends: $last" }
                    check(main { !model.uiState.value.armed && !model.uiState.value.busy })
                    if (role == "no-response") {
                        check(main { model.uiState.value.qso?.phase != CbQsoPhase.COMPLETE && !model.uiState.value.autoSequence.completed })
                        check(last.getString("reason").contains("three transmissions"))
                    } else check(main { model.uiState.value.qso?.phase == CbQsoPhase.COMPLETE && model.uiState.value.autoSequence.completed })
                    repeat(sends.length()) { i -> check(sends.getJSONObject(i).getLong("frames") == 161280L) }
                    last.put("received", JSONArray(observedReceives.values.toList()))
                    roles.put(last)
                    report.put("roles", roles).put("periodic_ntp_refreshes", clockEvents)
                    publish("autoseq-report", report)
                    if (continuous && role == "answering") {
                        check(!context.getSystemService(android.os.PowerManager::class.java).isInteractive)
                        report.put("automatic_complete_qso_with_screen_off", true)
                    }
                    stage("done-$role")
                    if (continuous && role != "answering") {
                        activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
                        current = activity
                        check(main { activity.receiveRuntime.manual === model })
                    }
                }
                report.put("roles", roles).put("periodic_ntp_refreshes", clockEvents)
                check(clockEvents.length() > 0) { "The physical suite did not observe a real five-minute NTP refresh" }
                if (!continuous) runGuards()
            }
            report.put("roles", roles).put("guards", guards)
            if (!continuous) {
            await("idle time refresh") { main { !time.uiState.value.refreshPending && !time.uiState.value.refreshing && !model.uiState.value.busy } }
            check(main { time.uiState.value.refreshWarning == null }) { main { "Unexpected time warning before offline scenario: ${time.uiState.value.refreshWarning}" } }
            stage("ntp-offline")
            val expiry = main { time.clock.snapshot().expiresAtNanos }
            await("actual periodic NTP failure", 330000) { main { time.uiState.value.refreshWarning != null } }
            check(main { time.clock.snapshot().expiresAtNanos == expiry && !model.uiState.value.autoSequence.active && !model.uiState.value.armed })
            report.put("failed_refresh_preserved_expiry", true)
            publish("autoseq-report", report)
            PhoneNavigation.click(instrumentation, "⚠")
            // The destination may retain its scroll position; exercise its actual
            // Measure/Apply controls below instead of requiring an off-screen title.
            stage("ntp-online")
            // Respect the real client's per-host request interval after the failed refresh.
            SystemClock.sleep(if (server == "ntp.nict.jp") 257000 else 65000)
            measureTimeFromUi()
            check(main { input.uiState.value.phase == CapturePhase.IDLE })
            report.put("real_ntp_failure_warning_and_manual_recovery", true).put("failed_refresh_preserved_expiry", true)
            }
            await("application-owned durable completions") { main { !activity.receiveRuntime.logbook.uiState.value.busy && activity.receiveRuntime.logbook.uiState.value.unsavedCompletions == 0 } }
            val staged = requireNotNull(fixtures).cleanup()
            if (!timeOnly) check(staged == 2) { "Expected one durable review candidate per completed bench exchange" }
            fixtures = null
            report.put("application_owned_completions_staged_once_and_fixtures_removed", staged)
            report.put("guards", guards).put("result", "PASS").put("model", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE).put("manual_ntp_observations", if (timeOnly) 2 else 3)
            File(context.cacheDir, "autoseq-report.json").writeText(report.toString(2))
            result.putString("autoseq_report", report.toString()); result.putString("autoseq_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("failure", "${error.javaClass.simpleName}: ${error.message}")
            publish("autoseq-report", report)
            result.putString("autoseq_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.take(3)}; manual=${operating?.uiState?.value}; capture=${capture?.uiState?.value?.copy(spectrum = SpectrumState())}")
        } finally {
            observer?.cancel()
            operating?.let { runBlocking(Dispatchers.Main) { it.stopAndJoin() } }
            capture?.let { runBlocking(Dispatchers.Main) { it.stopAndJoin(clear = true) } }
            if (fixtures != null) {
                await("accepted log writes before fixture cleanup") { main { current?.receiveRuntime?.logbook?.uiState?.value?.let { !it.busy && it.unsavedCompletions == 0 } ?: true } }
                fixtures.cleanup()
            }
            current?.let { main { it.finish() } }
        }
        instrumentation.finish(code, result)
    }
}
