package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.core.audio.SlotPeriod
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.ntp.UdpNtpClient
import net.vash.ft8cbdroid.operate.ManualOperatingViewModel
import net.vash.ft8cbdroid.profile.ProfilesViewModel
import net.vash.ft8cbdroid.time.AndroidTimeSource
import net.vash.ft8cbdroid.time.TimeViewModel
import org.json.JSONArray
import org.json.JSONObject

/** Real microphone/speaker scenarios. The host supplies independently generated desktop audio. */
class AcousticPhoneChecks(private val instrumentation: Instrumentation, private val run: String,
    private val ntpHost: String, private val ntpPort: Int) {
    private val context get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, millis: Long = 12000, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (!test()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }
            SystemClock.sleep(30)
        }
    }
    private fun stage(id: String): JSONObject {
        val file = File(context.cacheDir, "acoustic-ready.tmp")
        file.writeText(JSONObject().put("run", run).put("stage", id).toString())
        check(file.renameTo(File(context.cacheDir, "acoustic-ready.json")))
        val go = File(context.cacheDir, "acoustic-go-$run-$id.json")
        await("host $id", 15000) { go.exists() }
        return JSONObject(go.readText())
    }
    fun run() {
        var activity: MainActivity? = null
        var model: ManualOperatingViewModel? = null
        var capture: CaptureViewModel? = null
        val result = Bundle()
        val events = JSONArray()
        var code = Activity.RESULT_CANCELED
        try {
            require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            val cases = JSONObject(instrumentation.context.assets.open("acoustic.json").bufferedReader().use { it.readText() })
            val current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = current
            val operating = main { ViewModelProvider(current)[ManualOperatingViewModel::class.java] }; model = operating
            val input = main { ViewModelProvider(current)[CaptureViewModel::class.java] }; capture = input
            val time = main { ViewModelProvider(current)[TimeViewModel::class.java] }
            val profiles = main { ViewModelProvider(current)[ProfilesViewModel::class.java] }
            await("station and Phone profile") { main { profiles.uiState.value.ready && operating.uiState.value.available && operating.uiState.value.outputs.isNotEmpty() } }
            check(main { operating.uiState.value.station?.callsign == cases.getString("station") }) { "Save station 1AT106 and apply a Phone input/output profile first." }
            val measurement = runBlocking { UdpNtpClient(AndroidTimeSource, ntpPort).measure(ntpHost) }
            main { time.clock.apply(measurement) }
            PhoneNavigation.click(instrumentation, main { operating.uiState.value.outputs.single().label })
            PhoneNavigation.click(instrumentation, "Start RX")
            fun receiving() = await("live microphone") { main {
                check(input.uiState.value.error == null) { input.uiState.value.error.orEmpty() }
                input.uiState.value.phase == CapturePhase.CAPTURING && input.uiState.value.decodeLive
            } }
            receiving()
            val steps = cases.getJSONArray("steps")
            repeat(steps.length()) { index ->
                val step = steps.getJSONObject(index)
                val id = step.getString("id")
                val event = JSONObject().put("id", id).put("action", step.getString("action"))
                when (step.getString("action")) {
                    "reset" -> {
                        main { input.stop(clear = true) }
                        await("input released") { main { input.uiState.value.phase == CapturePhase.IDLE } }
                        main { input.requestStart(true) }; receiving()
                        check(main { input.uiState.value.live.rows.isEmpty() && input.uiState.value.live.learnedCalls == 0 })
                    }
                    "receive" -> {
                        receiving()
                        val before = main { operating.uiState.value }
                        val slot = stage(id).getLong("utc_millis")
                        await("physical decode $id", 55000) { main {
                            check(input.uiState.value.error == null) { input.uiState.value.error.orEmpty() }
                            input.uiState.value.live.rows.any { it.decode.slotStartUtc.toEpochMilli() == slot &&
                                it.decode.frame.payload.toBitString() == step.getString("payload77") }
                        } }
                        val row = main { input.uiState.value.live.rows.single { it.decode.slotStartUtc.toEpochMilli() == slot &&
                            it.decode.frame.payload.toBitString() == step.getString("payload77") } }
                        val text = (row.decode.message as? UnpackResult.Decoded)?.message?.text
                        check(text == step.getString("expected_text")) { "$id: $text != ${step.getString("expected_text")}" }
                        val peer = manualPeer(row, cases.getString("station"))
                        check((peer != null) == step.getBoolean("selectable")) { "$id: unexpected peer candidate $peer" }
                        val after = main { operating.uiState.value }
                        check(!after.armed && !after.busy && after.sent == before.sent && after.peer == before.peer && after.text == before.text) {
                            "A received message changed manual operating work."
                        }
                        event.put("utc_millis", slot).put("text", text).put("payload77", row.decode.frame.payload.toBitString())
                            .put("flags", JSONArray(row.decode.flags.map { it.name })).put("peer_candidate", peer?.callsign ?: JSONObject.NULL)
                            .put("learned_calls", main { input.uiState.value.live.learnedCalls }).put("no_automatic_action", true)
                    }
                    "send" -> {
                        main {
                            operating.peer(step.getString("peer")); operating.report("-07")
                            operating.choose(ManualMessage.valueOf(step.getString("kind")))
                            operating.period(SlotPeriod.valueOf(step.getString("period"))); operating.frequency("1000")
                        }
                        check(main { operating.uiState.value.text == step.getString("message") })
                        PhoneNavigation.click(instrumentation, "Calibrate speaker")
                        await("speaker calibration") { main { !operating.uiState.value.busy && operating.uiState.value.latency != null } }
                        receiving(); stage(id)
                        PhoneNavigation.click(instrumentation, "Enable TX")
                        check(main { operating.uiState.value.armed }) { main { operating.uiState.value.error.orEmpty() } }
                        PhoneNavigation.click(instrumentation, "Send one slot")
                        await("queued slot") { main { operating.uiState.value.queuedUtcMillis != null || operating.uiState.value.error != null } }
                        val utc = main { requireNotNull(operating.uiState.value.queuedUtcMillis) { operating.uiState.value.error.orEmpty() } }
                        await("complete output", 55000) { main { !operating.uiState.value.busy } }
                        val sent = main { operating.uiState.value }
                        check(sent.error == null && !sent.armed) { sent.error.orEmpty() }
                        val message = sent.sent.first()
                        check(message.utcMillis == utc && message.text == step.getString("message") &&
                            message.payload.toBitString() == step.getString("payload77") && message.frames >= 180000)
                        receiving()
                        check(main { input.uiState.value.live.rows.isEmpty() && input.uiState.value.live.learnedCalls == 0 })
                        event.put("utc_millis", utc).put("text", message.text).put("payload77", message.payload.toBitString())
                            .put("frames", message.frames).put("first_frame_error_ms", sent.playback?.firstFrameErrorMillis)
                            .put("period", sent.period.name).put("fresh_rx_context", true)
                    }
                    else -> error("Unknown acoustic action")
                }
                events.put(event)
                File(context.cacheDir, "acoustic-events.json").writeText(events.toString())
            }
            stage("complete")
            val report = JSONObject().put("run", run).put("result", "PASS").put("events", events)
                .put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
                .put("ntp_rtt_ms", measurement.roundTripMillis).put("ntp_uncertainty_ms", measurement.uncertaintyMillis)
            File(context.cacheDir, "acoustic-report.json").writeText(report.toString(2))
            result.putString("acoustic_result", "PASS"); result.putString("acoustic_report", report.toString())
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("acoustic_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}; events=$events; operating=${model?.uiState?.value}; capture=${capture?.uiState?.value?.error}")
        } finally {
            model?.let { main { it.stop() } }; capture?.let { main { it.stop(clear = true) } }
            activity?.let { main { it.finish() } }
        }
        instrumentation.finish(code, result)
    }
}
