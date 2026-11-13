package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.settings.StationSettingsViewModel
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual microphone and visible UI; the host must play independent 700/1900 Hz reference tones. */
class SpectrumPhoneChecks(private val instrumentation: Instrumentation) {
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }; return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, seconds: Int = 8, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(50) }
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null || predicate(node)) return node
        repeat(node.childCount) { find(node.getChild(it), predicate)?.let { return it } }; return null
    }
    private fun root(): AccessibilityNodeInfo? {
        val ui = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) ui.clearCache() else ui.serviceInfo = ui.serviceInfo
        return ui.rootInActiveWindow
    }
    private fun click(text: String) {
        var node = find(root()) { it.text?.toString() == text }
        while (node != null && !node.isClickable) node = node.parent
        check(requireNotNull(node) { "Control not found: $text" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun reveal(text: String, down: Boolean = false) {
        for (forward in listOf(down, !down)) repeat(10) {
            val root = requireNotNull(root()); val screen = Rect(); root.getBoundsInScreen(screen)
            val node = find(root) { it.text?.toString() == text }; val bounds = Rect(); node?.getBoundsInScreen(bounds)
            if (node?.isVisibleToUser == true && bounds.width() > 0 && bounds.height() > 0 && screen.contains(bounds)) return
            val middle = screen.centerY(); val step = screen.height() / 5
            shell("input touchscreen swipe ${screen.centerX()} $middle ${screen.centerX()} ${middle + if (forward) -step else step} 600")
            SystemClock.sleep(250)
        }
        error("Control not visible: $text")
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("timeout 8 $command")).use { String(it.readBytes()) }
    private fun showPlot() {
        repeat(12) {
            val root = requireNotNull(root()); val screen = Rect(); root.getBoundsInScreen(screen)
            val node = find(root) { it.contentDescription?.toString()?.startsWith("Local audio spectrum and waterfall.") == true }
            val bounds = Rect(); node?.getBoundsInScreen(bounds)
            val expectedHeight = (196 * instrumentation.targetContext.resources.displayMetrics.density).roundToInt()
            if (node?.isVisibleToUser == true && bounds.width() > 0 && bounds.height() >= expectedHeight - 2 && screen.contains(bounds)) return
            // Accessibility bounds may be clipped to the viewport: containment alone is insufficient.
            val towardTop = node != null && bounds.top < screen.centerY() && bounds.height() < expectedHeight - 2
            val middle = screen.centerY(); val step = screen.height() / 5
            shell("input touchscreen swipe ${screen.centerX()} $middle ${screen.centerX()} ${middle + if (towardTop) step else -step} 600")
            SystemClock.sleep(120)
        }
        error("Spectrum plot is not fully visible")
    }
    fun run() {
        val output = Bundle(); var code = Activity.RESULT_CANCELED
        var activity: MainActivity? = null; var capture: CaptureViewModel? = null
        try {
            val ui = instrumentation.uiAutomation
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
            val context = instrumentation.targetContext
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val station = main { ViewModelProvider(activity)[StationSettingsViewModel::class.java] }
            await("saved station loaded") { main { !station.uiState.value.isLoading } }
            val audio = main { activity.receiveRuntime.capture }; capture = audio
            PhoneNavigation.open(instrumentation, "Microphone tools")
            val mic = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            main { audio.select(mic.id); audio.assembleUtc(false); check(!audio.requestStart(true)) }
            await("real microphone") { main { audio.uiState.value.phase == CapturePhase.CAPTURING } }
            showPlot()
            shell("dumpsys gfxinfo net.vash.ft8cbdroid reset")
            File(context.cacheDir, "r13-spectrum-ready.json").writeText("{}")
            fun peak(values: List<Float>, hz: Float): Pair<Float, Float> {
                val bins = values.indices.filter { abs(SpectrumFft.frequency(it) - hz) < 20 }
                val bin = bins.maxBy { values[it] }; return SpectrumFft.frequency(bin) to values[bin]
            }
            fun matches(): Boolean = main {
                val values = audio.uiState.value.spectrum.frames.firstOrNull()?.dbfs ?: return@main false
                listOf(700f, 1900f).all { val (hz, db) = peak(values, it); abs(hz - it) <= 6 && db > -80 }
            }
            await("physical 700/1900 Hz tones", 20, ::matches)
            val started = SystemClock.elapsedRealtime(); val cpu = android.os.Process.getElapsedCpuTime()
            val first = main { audio.uiState.value.spectrum.processed }
            var maxCompute = 0L; var warmPss = 0L; var matched = 0; var previous = first
            repeat(120) { second ->
                SystemClock.sleep(1000)
                val value = main { audio.uiState.value }
                check(value.phase == CapturePhase.CAPTURING && value.error == null && value.spectrum.error == null)
                check(value.spectrum.frames.size <= SpectrumMonitor.HISTORY)
                check(value.spectrum.processed > previous) { "Spectrum stopped updating during capture" }
                previous = value.spectrum.processed
                check(value.metrics?.route?.input?.id == mic.id)
                if (matches()) matched++
                maxCompute = maxOf(maxCompute, value.spectrum.computeMicros)
                if (second == 59) {
                    warmPss = Debug.getPss(); shell("dumpsys gfxinfo net.vash.ft8cbdroid reset")
                }
            }
            val end = main { audio.uiState.value.spectrum }; val elapsed = SystemClock.elapsedRealtime() - started
            val pss = Debug.getPss(); val cpuMs = android.os.Process.getElapsedCpuTime() - cpu
            check(end.frames.size == 96 && end.processed - first >= 220) { "Insufficient sustained spectrum updates" }
            check(matched >= 100) { "Reference tones were not stable" }
            check(pss - warmPss < 65536) { "Spectrum memory grew by more than 64 MiB after warmup" }
            val gfx = shell("dumpsys gfxinfo net.vash.ft8cbdroid")
            File(context.cacheDir, "r13-gfxinfo.txt").writeText(gfx)
            val rendered = Regex("Total frames rendered: (\\d+)").find(gfx)?.groupValues?.get(1)?.toInt() ?: error("No render statistics")
            check(rendered >= 100) { "The visible plot did not render continuously" }
            val p90 = Regex("""90th percentile: (\d+)ms""").find(gfx)?.groupValues?.get(1)?.toInt() ?: error("No frame latency statistics")
            val jank = Regex("""Janky frames: \d+ \(([\d.]+)%\)""").find(gfx)?.groupValues?.get(1)?.toDouble() ?: error("No jank statistics")
            // Android frame statistics remain diagnostic: this is a 2 Hz instrument, not a 60 fps animation.
            val values = end.frames.first().dbfs
            reveal("Select TX"); click("Select TX"); reveal("+25 Hz"); click("+25 Hz")
            await("TX control response", 1) { main { audio.uiState.value.spectrumView.txHz == 1525 } }
            reveal("Zoom 4×", true); click("Zoom 4×")
            await("zoom control response", 1) { main { audio.uiState.value.spectrumView.spanHz == 750f } }
            showPlot()
            val plot = requireNotNull(find(root()) { it.contentDescription?.toString()?.startsWith("Local audio spectrum and waterfall.") == true })
            val bounds = Rect(); plot.getBoundsInScreen(bounds)
            val before = main { audio.uiState.value.spectrumView }
            val x = bounds.left + bounds.width() / 4
            shell("input tap $x ${bounds.centerY()}"); SystemClock.sleep(150)
            check(abs(main { audio.uiState.value.spectrumView.txHz } - before.at(.25f)) <= 3)
            reveal("Higher band"); click("Higher band")
            check(main { audio.uiState.value.spectrumView.startHz } > before.startHz)
            reveal("Lower band"); click("Lower band")
            reveal("Select RX"); click("Select RX"); reveal("+25 Hz", true); click("+25 Hz")
            check(main { audio.uiState.value.spectrumView.rxHz == 1025 })
            reveal("Zoom 1×", true); click("Zoom 1×")
            check(main { audio.uiState.value.spectrumView.startHz == 0f && audio.uiState.value.spectrumView.spanHz == 3000f })
            // Actual navigation retains the same spectrum/capture session until explicit Stop.
            PhoneNavigation.open(instrumentation, "Station")
            await("navigation continuity") { main { audio.uiState.value.phase == CapturePhase.CAPTURING } }
            check(main { audio.uiState.value.spectrum.sessionId == end.sessionId })
            PhoneNavigation.open(instrumentation, "Microphone tools")
            main { audio.stop(clear = true) }
            await("explicit Stop") { main { audio.uiState.value.phase == CapturePhase.IDLE } }
            reveal("Start microphone"); click("Start microphone")
            await("explicit restart") { main { audio.uiState.value.spectrum.frames.isNotEmpty() } }
            val restarted = main { audio.uiState.value.spectrum }
            check(restarted.sessionId != end.sessionId && restarted.frames.size < 10)
            main { audio.stop(clear = true) }
            await("Stop cleanup") { main { audio.uiState.value.phase == CapturePhase.IDLE } }
            check(main { audio.uiState.value.spectrum.frames.isEmpty() })
            val report = JSONObject().put("result", "PASS").put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
                .put("sustained_ms", elapsed).put("processed_windows", end.processed - first).put("history_rows", end.frames.size)
                .put("replaced_windows", end.replaced).put("max_observed_fft_us", maxCompute).put("tone_matches", matched)
                .put("tone_700_hz", peak(values, 700f).first).put("tone_1900_hz", peak(values, 1900f).first)
                .put("warm_pss_kib", warmPss).put("final_pss_kib", pss).put("process_cpu_ms", cpuMs)
                .put("actual_zoom_pan_cursor_tap_controls", true).put("navigation_and_cold_restart", true)
                .put("render_p90_ms", p90).put("janky_frames_percent", jank)
                .put("rendered_frames", rendered).put("plot_visible", true).put("no_ntp_required", true).put("stop_clears_history", true)
            File(context.cacheDir, "r13-spectrum-report.json").writeText(report.toString(2))
            output.putString("spectrum_report", report.toString()); output.putString("spectrum_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            output.putString("spectrum_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}")
        } finally {
            capture?.let { runCatching { main { it.stop(clear = true) } } }
            activity?.let { runCatching { main { it.finish() } } }
        }
        instrumentation.finish(code, output)
    }
}
