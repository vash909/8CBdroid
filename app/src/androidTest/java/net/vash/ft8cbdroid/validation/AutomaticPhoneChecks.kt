package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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

/** Actual foreground automatic exchanges. The host supplies/repeats/drops desktop audio, never model callbacks. */
class AutomaticPhoneChecks(private val instrumentation: Instrumentation, private val run: String) {
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
    private fun contains(root: AccessibilityNodeInfo?, text: String): Boolean {
        if (root == null) return false
        if (root.text?.toString() == text) return true
        repeat(root.childCount) { if (contains(root.getChild(it), text)) return true }
        return false
    }
    fun run() {
        var current: MainActivity? = null
        var operating: ManualOperatingViewModel? = null
        var capture: CaptureViewModel? = null
        var observer: Job? = null
        val result = Bundle(); val report = JSONObject(); var code = Activity.RESULT_CANCELED
        try {
            require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            var activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            current = activity
            val model = main { ViewModelProvider(activity)[ManualOperatingViewModel::class.java] }; operating = model
            val input = main { ViewModelProvider(activity)[CaptureViewModel::class.java] }; capture = input
            val time = main { ViewModelProvider(activity)[TimeViewModel::class.java] }
            val profiles = main { ViewModelProvider(activity)[ProfilesViewModel::class.java] }
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
            PhoneNavigation.click(instrumentation, main { model.uiState.value.outputs.single().label })
            fun idle() = await("audio released") { main { !model.uiState.value.busy && input.uiState.value.phase == CapturePhase.IDLE } }
            fun receiving() = await("live microphone") { main {
                check(input.uiState.value.error == null) { input.uiState.value.error.orEmpty() }
                input.uiState.value.phase == CapturePhase.CAPTURING && input.uiState.value.decodeLive
            } }
            fun prepare() {
                main { model.stop(); input.stop(clear = true) }; idle()
                PhoneNavigation.click(instrumentation, "Calibrate speaker")
                await("speaker timing") { main { !model.uiState.value.busy && model.uiState.value.latency != null } }
                PhoneNavigation.click(instrumentation, "Start RX"); receiving()
            }
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
                    .put("reason", value.autoSequence.reason).put("qso", value.qso?.phase?.name)
                    .put("sent", sends).put("rows", rows).put("last_slot_utc", rx.live.lastSlotUtcMillis)
                    .put("receive_started_utc", rx.receiveStartedUtcMillis).put("error", value.error ?: rx.error)
            }
            val roles = JSONArray()
            for (role in listOf("calling", "answering", "no-response")) {
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
                roles.put(last); stage("done-$role")
            }
            report.put("roles", roles).put("periodic_ntp_refreshes", clockEvents)
            check(clockEvents.length() > 0) { "The physical suite did not observe a real five-minute NTP refresh" }
            fun startGuard() {
                prepare(); PhoneNavigation.click(instrumentation, "Auto CQ")
                await("guard AutoSeq active") { main { model.uiState.value.autoSequence.active } }
            }
            val guards = JSONArray()
            for (guard in listOf("queued-stop", "playing-stop", "edit", "clock-reset", "focus-loss", "navigation", "recreation")) {
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
                    "recreation" -> {
                        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                        main { activity.recreate() }; activity = requireNotNull(monitor.waitForActivityWithTimeout(6000)) as MainActivity
                        current = activity; instrumentation.removeMonitor(monitor)
                    }
                }
                await("AutoSeq cancellation") { main { !model.uiState.value.autoSequence.active && !model.uiState.value.busy } }
                check(main { !model.uiState.value.armed && model.uiState.value.sent.size == before })
                SystemClock.sleep(700); check(main { !model.uiState.value.autoSequence.active })
                guards.put(guard)
                if (guard == "navigation") PhoneNavigation.click(instrumentation, "Operate")
                if (guard == "focus-loss") { SystemClock.sleep(1800); main { activity.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) } }
                if (guard == "clock-reset") {
                    val observation = runBlocking { UdpNtpClient(AndroidTimeSource).measure(server) }
                    main { time.clock.apply(observation) }; previousRevision = time.clock.revision
                }
            }
            await("idle time refresh") { main { !time.uiState.value.refreshPending && !time.uiState.value.refreshing && !model.uiState.value.busy } }
            check(main { time.uiState.value.refreshWarning == null }) { "A pre-existing time warning must be resolved before the offline scenario." }
            stage("ntp-offline")
            val expiry = main { time.clock.snapshot().expiresAtNanos }
            await("actual periodic NTP failure", 330000) { main { time.uiState.value.refreshWarning != null } }
            check(main { time.clock.snapshot().expiresAtNanos == expiry && !model.uiState.value.autoSequence.active && !model.uiState.value.armed })
            PhoneNavigation.click(instrumentation, "⚠")
            await("time warning destination") { contains(PhoneNavigation.root(instrumentation), "Time synchronization") }
            stage("ntp-online")
            // Respect the real client's per-host request interval after the failed refresh.
            SystemClock.sleep(if (server == "ntp.nict.jp") 257000 else 65000)
            PhoneNavigation.click(instrumentation, "Measure NTP")
            await("real network recovery") { main { time.uiState.value.busy == null && time.uiState.value.measurement != null } }
            check(main { time.uiState.value.error == null }) { main { time.uiState.value.error.orEmpty() } }
            PhoneNavigation.click(instrumentation, "Apply measured time")
            check(main { time.uiState.value.refreshWarning == null && !model.uiState.value.autoSequence.active && !model.uiState.value.armed })
            check(main { input.uiState.value.phase == CapturePhase.IDLE })
            report.put("real_ntp_failure_warning_and_manual_recovery", true).put("failed_refresh_preserved_expiry", true)
            report.put("guards", guards).put("result", "PASS").put("model", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE).put("manual_ntp_observations", 3)
            File(context.cacheDir, "autoseq-report.json").writeText(report.toString(2))
            result.putString("autoseq_report", report.toString()); result.putString("autoseq_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("autoseq_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.take(3)}; manual=${operating?.uiState?.value}; capture=${capture?.uiState?.value?.copy(spectrum = SpectrumState())}")
        } finally {
            observer?.cancel()
            operating?.let { main { it.stop() } }; capture?.let { main { it.stop(clear = true) } }
            current?.let { main { it.finish() } }
        }
        instrumentation.finish(code, result)
    }
}
