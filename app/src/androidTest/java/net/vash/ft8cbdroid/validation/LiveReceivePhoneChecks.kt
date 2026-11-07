package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.dsp.NativeFt8Engine
import net.vash.ft8cbdroid.time.TimeViewModel
import org.json.JSONArray
import org.json.JSONObject
import net.vash.ft8cbdroid.core.audio.localSpectrumMarkers
import kotlin.math.roundToInt

/** Desktop fixtures must arrive acoustically through the microphone; no supplied PCM or decoder results. */
class LiveReceivePhoneChecks(private val instrumentation: Instrumentation) {
    private val context get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, seconds: Int = 6, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(40) }
    }
    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null || node.text?.toString() == text) return node
        repeat(node.childCount) { find(node.getChild(it), text)?.let { return it } }
        return null
    }
    private fun ready(stage: String) {
        val temporary = File(context.cacheDir, "r12-ready.tmp")
        temporary.writeText(JSONObject().put("stage", stage).toString())
        check(temporary.renameTo(File(context.cacheDir, "r12-ready.json")))
    }
    private fun show(text: String) {
        val automation = instrumentation.uiAutomation
        for (forward in listOf(true, false)) repeat(14) {
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache() else automation.serviceInfo = automation.serviceInfo
            val root = requireNotNull(automation.rootInActiveWindow)
            val screen = Rect(); root.getBoundsInScreen(screen)
            val found = find(root, text); val bounds = Rect(); found?.getBoundsInScreen(bounds)
            if (found?.isVisibleToUser == true && bounds.width() > 0 && bounds.height() > 0 && Rect.intersects(screen, bounds)) return
            val x = screen.centerX(); val start = screen.centerY()
            val end = start + if (forward) -screen.height() / 5 else screen.height() / 5
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "timeout 3 input touchscreen swipe $x $start $x $end 600"
            )).use { check(it.readBytes().isEmpty()) }
            SystemClock.sleep(250)
        }
        error("Live UI text is not visible: $text")
    }
    fun run() {
        var activity: MainActivity? = null; var capture: CaptureViewModel? = null; var time: TimeViewModel? = null
        val output = Bundle(); var code = Activity.RESULT_CANCELED
        try {
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val timing = main { ViewModelProvider(activity)[TimeViewModel::class.java] }; time = timing
            val audio = main { ViewModelProvider(activity)[CaptureViewModel::class.java] }; capture = audio
            await("time settings") { main { !timing.uiState.value.loading } }
            main { timing.measureNtp() }
            await("actual NTP", 10) { main { timing.uiState.value.busy == null } }
            val measurement = main { requireNotNull(timing.uiState.value.measurement) { timing.uiState.value.error ?: "No measured time" } }
            main { timing.applyMeasurement() }
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache() else automation.serviceInfo = automation.serviceInfo
            var navigation = find(automation.rootInActiveWindow, "Audio")
            while (navigation != null && !navigation.isClickable) navigation = navigation.parent
            check(requireNotNull(navigation).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val mic = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            main { audio.changeStation(null); audio.select(mic.id); audio.decodeLive(true); check(!audio.requestStart(true)) }
            await("microphone recording") { main { audio.uiState.value.phase == CapturePhase.CAPTURING } }
            ready("first-run")
            fun messages() = main { audio.uiState.value.live.rows.mapNotNull { (it.decode.message as? UnpackResult.Decoded)?.message?.text } }
            fun healthy() = main {
                check(audio.uiState.value.error == null) { audio.uiState.value.error!! }
                check(audio.uiState.value.live.error == null) { audio.uiState.value.live.error!! }
            }
            await("desktop CQ and resolved directed message", 80) {
                healthy(); val texts = messages(); "CQ 1AT106" in texts && "<1AT106> 26AT101" in texts
            }
            val first = main { audio.uiState.value }
            check(first.metrics?.route?.input?.id == mic.id)
            check(first.live.rows.all { it.decode.slotStartUtc.epochSecond % 15 == 0L })
            check(messages().all { it in setOf("CQ 1AT106", "<1AT106> 26AT101") })
            val markers = localSpectrumMarkers(first.live.rows)
            check(first.spectrum.frames.isNotEmpty() && markers.isNotEmpty())
            show("Yellow markers: latest local decoded slot ${java.time.Instant.ofEpochMilli(markers.first().utcMillis)}. Frequency indicators, not waterfall event times.")
            val label = "Decode ${markers.first().frequencyHz.roundToInt()} Hz → RX"
            show(label)
            var markerButton = find(automation.rootInActiveWindow, label)
            while (markerButton != null && !markerButton.isClickable) markerButton = markerButton.parent
            check(requireNotNull(markerButton).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            check(main { audio.uiState.value.spectrumView.rxHz } == markers.first().frequencyHz.roundToInt().coerceIn(200, 2950))
            show("Live local decodes")
            show("Source: local microphone • Standard • latest 60 frames")
            show("<1AT106> 26AT101")
            main { audio.stop(clear = true) }
            await("capture/native cleanup") { main { audio.uiState.value.phase == CapturePhase.IDLE } }
            check(main { audio.uiState.value.live.rows.isEmpty() && audio.uiState.value.live.learnedCalls == 0 })
            check(NativeFt8Engine.workSnapshot().nativeCalls == 0)
            main { check(!audio.requestStart(true)) }
            await("explicit restart") { main { audio.uiState.value.phase == CapturePhase.CAPTURING } }
            ready("cold-restart")
            await("unknown hash after restart", 45) { healthy(); "<...> 26AT101" in messages() }
            val cold = main { audio.uiState.value.live }
            main { audio.stop(clear = true) }
            await("final cleanup") { main { audio.uiState.value.phase == CapturePhase.IDLE } }
            check(main { audio.uiState.value.live.rows.isEmpty() })
            val report = JSONObject().put("result", "PASS").put("model", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE).put("source", measurement.source)
                .put("complete_slots", first.live.decodedSlots).put("decode_ms", first.live.decodeMillis)
                .put("first_run_messages", JSONArray(first.live.rows.map { (it.decode.message as? UnpackResult.Decoded)?.message?.text }))
                .put("cold_restart_messages", JSONArray(cold.rows.map { (it.decode.message as? UnpackResult.Decoded)?.message?.text }))
                .put("stop_clears_results_and_hashes", true).put("actual_builtin_microphone", true)
                .put("local_origin_and_message_visible_in_ui", true)
                .put("decoded_marker_visible_and_selects_rx", true)
                .put("native_calls_after_stop", NativeFt8Engine.workSnapshot().nativeCalls)
            File(context.cacheDir, "r12-live-report.json").writeText(report.toString(2))
            output.putString("live_report", report.toString()); output.putString("live_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            output.putString("live_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}")
        } finally {
            capture?.let { runCatching { main { it.stop(clear = true) } } }
            time?.let { runCatching { main { it.cancel() } } }
            activity?.let { runCatching { main { it.finish() } } }
        }
        instrumentation.finish(code, output)
    }
}
