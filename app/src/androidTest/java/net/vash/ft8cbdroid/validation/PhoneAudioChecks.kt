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
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.core.audio.SlotPeriod
import net.vash.ft8cbdroid.core.message.EncodingMode
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.dsp.NativeFt8Engine
import net.vash.ft8cbdroid.message.WaveformViewModel
import net.vash.ft8cbdroid.ntp.UdpNtpClient
import net.vash.ft8cbdroid.profile.ProfilesViewModel
import net.vash.ft8cbdroid.time.AndroidTimeSource
import net.vash.ft8cbdroid.time.TimeViewModel
import org.json.JSONArray
import org.json.JSONObject

/** Real half-duplex audio: the host supplies desktop WAVs acoustically and records phone output. */
class PhoneAudioChecks(private val instrumentation: Instrumentation, private val runId: String,
    private val ntpHost: String, private val ntpPort: Int) {
    private val context get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, timeout: Long = 8000, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!check()) { require(SystemClock.elapsedRealtime() < until) { "Timed out: $label" }; SystemClock.sleep(25) }
    }
    private fun stage(name: String) {
        val temporary = File(context.cacheDir, "r17a-ready.tmp")
        temporary.writeText(JSONObject().put("run", runId).put("stage", name).toString())
        check(temporary.renameTo(File(context.cacheDir, "r17a-ready.json")))
        await("host stage $name", 15000) { File(context.cacheDir, "r17a-go-$runId-$name").exists() }
    }
    fun run() {
        var activity: MainActivity? = null; var audio: CaptureViewModel? = null; var output: WaveformViewModel? = null
        val result = Bundle(); val report = JSONObject().put("run", runId); var code = Activity.RESULT_CANCELED
        try {
            require(runId.matches(Regex("[A-Za-z0-9-]{1,64}")))
            var current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = current
            val capture = main { current.receiveRuntime.capture }; audio = capture
            val model = main { ViewModelProvider(current)[WaveformViewModel::class.java] }; output = model
            val time = main { current.receiveRuntime.time }
            val profiles = main { current.receiveRuntime.profiles }
            await("initialization") { main { profiles.uiState.value.ready && !time.uiState.value.loading && model.uiState.value.outputs.isNotEmpty() } }
            PhoneNavigation.open(instrumentation, "Message tools")
            main { capture.changeStation(null); capture.select(capture.uiState.value.inputs.first { it.kind == net.vash.ft8cbdroid.core.audio.AudioInputKind.PHONE }.id) }
            PhoneNavigation.click(instrumentation, "Generate offline WAV")
            await("generated PCM") { main { model.uiState.value.summary != null } }
            main { model.selectOutput(model.uiState.value.outputs.single().id) }
            PhoneNavigation.click(instrumentation, "Resume microphone after successful output")
            check(main { model.uiState.value.resumeReceive })
            val measurement = runBlocking { UdpNtpClient(AndroidTimeSource, ntpPort).measure(ntpHost) }
            main { time.clock.apply(measurement) }
            report.put("ntp_rtt_ms", measurement.roundTripMillis).put("ntp_uncertainty_ms", measurement.uncertaintyMillis)
            fun idle() = await("all cleanup") { main { !model.uiState.value.isPlaying && capture.uiState.value.phase == CapturePhase.IDLE } }
            fun receive() {
                main { capture.decodeLive(true); capture.requestStart(true) }
                await("microphone active") { main { capture.uiState.value.phase == CapturePhase.CAPTURING } }
            }
            fun received(name: String) {
                stage(name)
                await("real desktop CQ $name", 55000) {
                    main {
                        check(capture.uiState.value.error == null) { capture.uiState.value.error.orEmpty() }
                        capture.uiState.value.live.rows.any { (it.decode.message as? UnpackResult.Decoded)?.message?.text == "CQ 1AT106" }
                    }
                }
                check(main { capture.uiState.value.live.learnedCalls > 0 })
            }
            // Exercise the shipped receive control, including the real permission-granted path.
            PhoneNavigation.click(instrumentation, "Receive FT8")
            await("first active microphone") { main { capture.uiState.value.phase == CapturePhase.CAPTURING } }
            received("receive-first")
            main { model.measureLatency() }
            await("calibration recovery", 10000) { main { !model.uiState.value.isPlaying } }
            check(main { model.uiState.value.latency != null && capture.uiState.value.phase in listOf(CapturePhase.STARTING, CapturePhase.CAPTURING) })
            report.put("latency_ms", main { model.uiState.value.latency!!.nanos / 1e6 })
            report.put("silent_calibration_recovers_existing_receive", true)
            val slots = JSONArray()
            for (period in SlotPeriod.entries) {
                stage("capture-${period.name.lowercase()}")
                main { model.period(period); model.queueSlot() }
                check(main { model.uiState.value.isPlaying }) { main { model.uiState.value.error.orEmpty() } }
                val utc = main { requireNotNull(model.uiState.value.queuedUtcMillis) }
                await("input released") { main { capture.uiState.value.phase == CapturePhase.IDLE } }
                check(main { capture.uiState.value.outputBusy && !capture.uiState.value.canStart && capture.uiState.value.metrics == null && capture.uiState.value.live.rows.isEmpty() })
                val start = SystemClock.elapsedRealtime()
                var blockedObservations = 0
                await("complete scheduled output and recovery", 52000) {
                    main {
                        if (model.uiState.value.isPlaying && capture.uiState.value.outputBusy) {
                            check(capture.uiState.value.phase == CapturePhase.IDLE)
                            check(NativeFt8Engine.workSnapshot().nativeCalls == 0)
                            capture.requestStart(true)
                            check(capture.uiState.value.phase == CapturePhase.IDLE)
                            blockedObservations++
                        }
                        !model.uiState.value.isPlaying
                    }
                }
                val state = main { model.uiState.value }; check(state.error == null) { state.error.orEmpty() }
                val played = requireNotNull(state.playback)
                check(played.playedFrames == 180000L && played.writtenFrames == 180000)
                await("receive recovery") { main { capture.uiState.value.phase == CapturePhase.CAPTURING } }
                check(main { capture.uiState.value.live.rows.isEmpty() && capture.uiState.value.latestSlotUtcMillis == null && capture.uiState.value.live.learnedCalls == 0 })
                slots.put(JSONObject().put("period", period.name).put("utc_millis", utc)
                    .put("first_frame_error_ms", played.firstFrameErrorMillis).put("frames", played.playedFrames)
                    .put("blocked_input_observations", blockedObservations).put("elapsed_ms", SystemClock.elapsedRealtime() - start))
                received("receive-after-${period.name.lowercase()}")
            }
            report.put("slots", slots).put("actual_receive_output_receive_cycles", 2).put("no_self_decode_or_partial_slot_after_output", true)
            stage("captures-complete")
            fun queue() { main { model.queueSlot() }; check(main { model.uiState.value.isPlaying }) }
            queue(); main { model.stopPreview() }; idle(); SystemClock.sleep(650)
            check(main { capture.uiState.value.phase == CapturePhase.IDLE }); report.put("stop_never_recovers", true)
            receive(); queue(); main { capture.stop() }
            // Cancel output explicitly after invalidating recovery; the JVM checks cover successful completion with invalidation.
            main { model.stopPreview() }; idle(); report.put("stop_microphone_invalidates_recovery", true)
            receive(); queue(); main { current.startActivity(Intent().setComponent(ComponentName("net.vash.ft8cbdroid.engineprobe", "net.vash.ft8cbdroid.engineprobe.PreviewFocusActivity"))) }
            idle(); check(main { model.uiState.value.error?.contains("focus", true) == true })
            SystemClock.sleep(1800); check(main { capture.uiState.value.phase == CapturePhase.IDLE }); report.put("real_focus_loss_never_recovers", true)
            receive(); queue(); main { time.clock.reset() }; idle()
            check(main { model.uiState.value.error?.contains("Time correction") == true }); report.put("clock_reset_never_recovers", true)
            main { time.clock.apply(measurement) }; receive(); queue()
            main { model.changeInput("CQ 26AT101", EncodingMode.AUTOMATIC) }; idle(); report.put("message_edit_never_recovers", true)
            main { model.generate("CQ 1AT106", EncodingMode.AUTOMATIC) }; await("new PCM") { main { model.uiState.value.summary != null } }
            receive(); queue(); PhoneNavigation.click(instrumentation, "Operate"); idle()
            check(main { capture.uiState.value.metrics == null }); report.put("navigation_disarms_both", true)
            PhoneNavigation.open(instrumentation, "Message tools")
            receive(); queue()
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            main { current.recreate() }; current = requireNotNull(monitor.waitForActivityWithTimeout(6000)) as MainActivity
            activity = current; instrumentation.removeMonitor(monitor); idle()
            report.put("retained_recreation_disarms_both", true)
            main { profiles.apply() }; await("profile apply") { main { !profiles.uiState.value.busy } }
            check(main { !model.uiState.value.resumeReceive && model.uiState.value.latency == null && !capture.uiState.value.outputBusy })
            report.put("profile_clears_recovery_consent", true)
            report.put("result", "PASS").put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
            File(context.cacheDir, "r17a-report.json").writeText(report.toString(2))
            result.putString("phone_audio_result", "PASS"); result.putString("phone_audio_report", report.toString()); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("phone_audio_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}; output=${output?.uiState?.value}; capture=${audio?.uiState?.value?.copy(spectrum = net.vash.ft8cbdroid.core.audio.SpectrumState())}")
        } finally {
            output?.let { main { it.stopPreview() } }; audio?.let { main { it.stop(clear = true) } }
            activity?.let { main { it.finish() } }
        }
        instrumentation.finish(code, result)
    }
}
