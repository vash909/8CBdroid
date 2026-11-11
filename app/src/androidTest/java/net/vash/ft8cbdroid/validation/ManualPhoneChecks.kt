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
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.qso.*
import net.vash.ft8cbdroid.ntp.UdpNtpClient
import net.vash.ft8cbdroid.operate.*
import net.vash.ft8cbdroid.profile.ProfilesViewModel
import net.vash.ft8cbdroid.time.*
import org.json.JSONArray
import org.json.JSONObject

/** Physical desktop/phone exchange. Host audio and independent desktop decoding are required. */
class ManualPhoneChecks(private val instrumentation: Instrumentation, private val runId: String,
    private val ntpHost: String, private val ntpPort: Int) {
    private val context get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return requireNotNull(value).getOrThrow()
    }
    private fun await(label: String, timeout: Long = 8000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "Timed out: $label" }; SystemClock.sleep(25) }
    }
    private fun stage(name: String) {
        val file = File(context.cacheDir, "r19a-ready.tmp")
        file.writeText(JSONObject().put("run", runId).put("stage", name).toString())
        check(file.renameTo(File(context.cacheDir, "r19a-ready.json")))
        await("host $name", 15000) { File(context.cacheDir, "r19a-go-$runId-$name").exists() }
    }
    fun run() {
        var activity: MainActivity? = null; var output: ManualOperatingViewModel? = null; var audio: CaptureViewModel? = null
        val result = Bundle(); val report = JSONObject().put("run", runId); var code = Activity.RESULT_CANCELED
        try {
            require(runId.matches(Regex("[A-Za-z0-9-]{1,64}")))
            var current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = current
            val model = main { ViewModelProvider(current)[ManualOperatingViewModel::class.java] }; output = model
            val capture = main { ViewModelProvider(current)[CaptureViewModel::class.java] }; audio = capture
            val time = main { ViewModelProvider(current)[TimeViewModel::class.java] }
            val profiles = main { ViewModelProvider(current)[ProfilesViewModel::class.java] }
            await("phone profile and station initialization") { main { profiles.uiState.value.ready && model.uiState.value.available && model.uiState.value.outputs.isNotEmpty() } }
            check(main { model.uiState.value.station?.callsign == "1AT106" }) { "Save test station 1AT106 and a valid locator first." }
            val measurement = runBlocking { UdpNtpClient(AndroidTimeSource, ntpPort).measure(ntpHost) }
            main { time.clock.apply(measurement) }
            report.put("ntp_rtt_ms", measurement.roundTripMillis).put("ntp_uncertainty_ms", measurement.uncertaintyMillis)
            await("Operate accessibility window") {
                PhoneNavigation.root(instrumentation)?.let { it.packageName == context.packageName && it.childCount > 0 } == true
            }
            // Exercise the real Operate controls, not a separate test screen.
            PhoneNavigation.click(instrumentation, main { model.uiState.value.outputs.single().label })
            PhoneNavigation.click(instrumentation, "Calibrate speaker")
            await("speaker calibration", 12000) { main { !model.uiState.value.busy && model.uiState.value.latency != null } }
            PhoneNavigation.click(instrumentation, "Start RX")
            fun receiving() = await("active microphone") { main { capture.uiState.value.phase == CapturePhase.CAPTURING } }
            receiving()
            val received = JSONArray(); val slots = JSONArray()
            fun incoming(name: String, expected: String) {
                stage("receive-$name")
                await("physical desktop $expected", 55000) { main {
                    check(capture.uiState.value.error == null) { capture.uiState.value.error.orEmpty() }
                    capture.uiState.value.live.rows.any { (it.decode.message as? UnpackResult.Decoded)?.message?.text == expected }
                } }
                received.put(JSONObject().put("stage", name).put("text", expected).put("learned_calls", main { capture.uiState.value.live.learnedCalls }))
            }
            incoming("cq", "CQ 26AT101")
            incoming("reply", "<1AT106> 26AT101")
            PhoneNavigation.click(instrumentation, "Use peer")
            check(main { model.uiState.value.peer == "26AT101" && !model.uiState.value.armed })
            fun outgoing(name: String, choice: ManualMessage, expected: String) {
                main { model.report("-12"); model.frequency("1000") }
                await("qualified QSO suggestion $choice") { main { model.uiState.value.suggestion?.kind == choice } }
                val before = main { model.uiState.value.sent.size }
                PhoneNavigation.click(instrumentation, "Use suggested message")
                check(main { !model.uiState.value.armed && model.uiState.value.sent.size == before })
                check(main { model.uiState.value.text == expected })
                PhoneNavigation.click(instrumentation, "Calibrate speaker")
                await("fresh calibration", 12000) { main { !model.uiState.value.busy && model.uiState.value.latency != null } }; receiving()
                stage("capture-$name")
                PhoneNavigation.click(instrumentation, "Enable TX")
                check(main { model.uiState.value.armed }) { main { model.uiState.value.error.orEmpty() } }
                PhoneNavigation.click(instrumentation, "Send one slot")
                await("queued manual message", 18000) { main { model.uiState.value.queuedUtcMillis != null || model.uiState.value.error != null } }
                val queued = main { requireNotNull(model.uiState.value.queuedUtcMillis) { model.uiState.value.error.orEmpty() } }
                await("one complete speaker slot", 55000) { main { !model.uiState.value.busy } }
                val value = main { model.uiState.value }
                check(value.error == null) { value.error.orEmpty() }
                check(!value.armed && value.sent.first().text == expected)
                receiving()
                check(main { capture.uiState.value.live.rows.isEmpty() && capture.uiState.value.live.learnedCalls == 0 })
                slots.put(JSONObject().put("stage", name).put("period", value.period.name).put("utc_millis", queued)
                    .put("text", expected).put("payload77", value.sent.first().payload.toBitString())
                    .put("frames", value.sent.first().frames).put("first_frame_error_ms", value.playback?.firstFrameErrorMillis))
                val count = value.sent.size
                SystemClock.sleep(500)
                check(main { !model.uiState.value.armed && model.uiState.value.sent.size == count })
            }
            outgoing("reply", ManualMessage.REPLY, "<26AT101> 1AT106")
            check(main { model.uiState.value.qso?.replySent == true })
            incoming("report", "1AT106 -07")
            await("qualified received report") { main { model.uiState.value.qso?.receivedReport == -7 } }
            check(main { model.uiState.value.choice == ManualMessage.REPLY && !model.uiState.value.armed })
            outgoing("roger", ManualMessage.ROGER, "26AT101 R-12")
            incoming("rrr", "1AT106 RRR")
            await("classic RRR advice") { main { model.uiState.value.qso?.peerRogerReceived == true && model.uiState.value.suggestion?.kind == ManualMessage.SIGNOFF } }
            check(main { model.uiState.value.qso?.phase != CbQsoPhase.COMPLETE && !model.uiState.value.armed })
            val beforeForeign = main { model.uiState.value.qso }
            incoming("foreign-final", "161XZ085 RR73")
            check(main { model.uiState.value.qso == beforeForeign && model.uiState.value.peer == "26AT101" && !model.uiState.value.armed })
            report.put("actual_rrr_advice", true).put("actual_foreign_final_ignored", true)
            incoming("final", "1AT106 RR73")
            check(main { model.uiState.value.qso?.phase == CbQsoPhase.SIGNOFF })
            outgoing("signoff", ManualMessage.SIGNOFF, "26AT101 73")
            check(main { model.uiState.value.qso?.phase == CbQsoPhase.COMPLETE && model.uiState.value.suggestion == null })
            report.put("qualified_live_qso_advice", true).put("explicit_apply_never_arms", true).put("audio_confirmed_qso_model_completion", true)
            stage("captures-complete")
            report.put("slots", slots).put("received", received).put("manual_acoustic_exchange", true)
                .put("cold_after_output_and_warm_before_reply", true).put("peer_selection_never_arms", true)
            val sentCount = main { model.uiState.value.sent.size }
            PhoneNavigation.click(instrumentation, "Test tone · 2 s")
            await("bounded test tone", 10000) { main { !model.uiState.value.busy } }; receiving()
            check(main { model.uiState.value.sent.size == sentCount && !model.uiState.value.armed })
            report.put("bounded_tone_not_a_sent_message", true)
            fun startRx() { main { capture.requestStart(true) }; receiving() }
            fun queue() {
                main { model.enableTx(); model.send() }
                await("queue") { main { model.uiState.value.queuedUtcMillis != null } }
            }
            fun idle() { await("audio cleanup") { main { !model.uiState.value.busy && capture.uiState.value.phase == CapturePhase.IDLE } } }
            queue(); PhoneNavigation.click(instrumentation, "Stop"); idle(); SystemClock.sleep(650)
            check(main { model.uiState.value.sent.size == sentCount && !model.uiState.value.armed }); report.put("stop_no_sent_or_recovery", true)
            startRx(); queue()
            await("actual speaker playback", 40000) { main { (model.uiState.value.playback?.playedFrames ?: 0) >= 24000 } }
            val stoppedAt = SystemClock.elapsedRealtime()
            main { model.stop(); capture.stop() }; idle()
            report.put("playing_stop_cleanup_ms", SystemClock.elapsedRealtime() - stoppedAt)
            check(main { model.uiState.value.sent.size == sentCount }); report.put("playing_stop_omits_partial_audio", true)
            startRx(); queue(); main { model.custom("CQ 26AT101") }; idle(); report.put("edit_cancels_snapshot", true)
            startRx(); queue(); main { time.clock.reset() }; idle()
            check(main { model.uiState.value.error != null }); report.put("clock_reset_cancels", true)
            main { time.clock.apply(measurement) }; startRx(); queue()
            main { current.startActivity(Intent().setComponent(ComponentName("net.vash.ft8cbdroid.engineprobe", "net.vash.ft8cbdroid.engineprobe.PreviewFocusActivity"))) }
            idle(); report.put("actual_focus_loss_cancels", true)
            SystemClock.sleep(1800)
            check(main { !model.uiState.value.armed })
            startRx(); queue(); PhoneNavigation.click(instrumentation, "Settings"); idle(); report.put("navigation_disarms", true)
            PhoneNavigation.click(instrumentation, "Operate"); startRx(); queue()
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            main { current.recreate() }; current = requireNotNull(monitor.waitForActivityWithTimeout(6000)) as MainActivity
            activity = current; instrumentation.removeMonitor(monitor); idle(); report.put("retained_recreation_disarms", true)
            main { profiles.apply() }; await("profile cleanup") { main { !profiles.uiState.value.busy } }
            check(main { model.uiState.value.outputId == null && model.uiState.value.latency == null && !model.uiState.value.armed })
            report.put("profile_clears_output_calibration", true).put("result", "PASS")
                .put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
            File(context.cacheDir, "r19a-report.json").writeText(report.toString(2))
            result.putString("manual_audio_result", "PASS"); result.putString("manual_audio_report", report.toString()); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("manual_audio_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}; manual=${output?.uiState?.value}; capture=${audio?.uiState?.value?.copy(spectrum = SpectrumState())}")
        } finally {
            output?.let { main { it.stop() } }; audio?.let { main { it.stop(clear = true) } }
            activity?.let { main { it.finish() } }
        }
        instrumentation.finish(code, result)
    }
}
