package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.lan.LanViewModel
import net.vash.ft8cbdroid.ntp.UdpNtpClient
import net.vash.ft8cbdroid.operate.ManualOperatingViewModel
import net.vash.ft8cbdroid.time.AndroidTimeSource
import net.vash.ft8cbdroid.time.TimeViewModel
import org.json.JSONObject

/** Real incoming desktop audio, shipped UI filters and retained Activity state. No synthetic decode events. */
class OperatingContextPhoneChecks(private val instrumentation: Instrumentation, private val run: String) {
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, timeout: Long = 12000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "Timed out: $label" }; SystemClock.sleep(30) }
    }
    private fun contains(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.text?.toString() == text) return true
        repeat(node.childCount) { if (contains(node.getChild(it), text)) return true }
        return false
    }
    private fun visible(text: String) = contains(PhoneNavigation.root(instrumentation), text)
    fun run() {
        val context = instrumentation.targetContext
        val output = Bundle(); val report = JSONObject().put("run", run)
        var activity: MainActivity? = null; var capture: CaptureViewModel? = null; var model: ManualOperatingViewModel? = null
        var lan: LanViewModel? = null
        var code = Activity.RESULT_CANCELED
        try {
            require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            var current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = current
            val input = main { ViewModelProvider(current)[CaptureViewModel::class.java] }; capture = input
            val operating = main { ViewModelProvider(current)[ManualOperatingViewModel::class.java] }; model = operating
            val time = main { ViewModelProvider(current)[TimeViewModel::class.java] }
            await("saved Phone setup") { main { input.uiState.value.canStart && operating.uiState.value.available && time.uiState.value.saved != null } }
            check(main { operating.uiState.value.station?.callsign == "1AT106" })
            val host = main { requireNotNull(time.uiState.value.saved).host }
            val observation = runBlocking { UdpNtpClient(AndroidTimeSource).measure(host) }
            main { time.clock.apply(observation) }
            PhoneNavigation.click(instrumentation, "Start RX")
            await("real microphone") { main { input.uiState.value.phase == CapturePhase.CAPTURING && input.uiState.value.decodeLive } }
            File(context.cacheDir, "context-ready.json").writeText(JSONObject().put("run", run).put("stage", "audio").toString())
            fun texts() = main { input.uiState.value.live.rows.mapNotNull { (it.decode.message as? UnpackResult.Decoded)?.message?.text } }
            await("unknown-division acoustic CQ", 70000) { "CQ 999ZZ999" in texts() }
            await("unknown country on screen") { visible("From 999ZZ999 · CB 999 · Country unknown") }
            await("all three acoustic messages", 90000) { texts().containsAll(listOf("CQ 999ZZ999", "1AT106 -07", "CQ 26AT101")) }
            val before = main { input.uiState.value.live.rows }
            check(before.size == 3) { "Unexpected additional audio rows" }
            PhoneNavigation.click(instrumentation, "To me")
            await("directed filter") { visible("Received 1/3") && visible("1AT106 -07") }
            check(main { input.uiState.value.live.rows == before })
            check(!visible("From 26AT101 · CB 026 · England") && !visible("Sender unknown"))
            PhoneNavigation.click(instrumentation, "CQ")
            await("CQ filter") { visible("Received 2/3") && visible("From 26AT101 · CB 026 · England") }
            PhoneNavigation.click(instrumentation, "Use peer")
            await("explicit peer and country") { main { operating.uiState.value.peer == "26AT101" } && visible("Peer country: CB 026 · England") }
            check(main { input.uiState.value.live.rows == before && !operating.uiState.value.armed && !operating.uiState.value.autoSequence.active && operating.uiState.value.sent.isEmpty() })
            report.put("actual_acoustic_messages", texts().size).put("cq_and_directed_filters", true)
                .put("backend_rows_unchanged", true).put("explicit_peer_country", true).put("unknown_sender_hidden_and_division_unknown", true)
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            main { current.recreate() }
            current = requireNotNull(monitor.waitForActivityWithTimeout(6000)) as MainActivity
            activity = current; instrumentation.removeMonitor(monitor)
            await("recreation stops reception") { main { input.uiState.value.phase == CapturePhase.IDLE } }
            await("saved CQ filter") { visible("Received 0/0") }
            PhoneNavigation.click(instrumentation, "All")
            await("reset filter") { visible("Received 0") }
            PhoneNavigation.click(instrumentation, "Start RX")
            await("explicit RX recovery") { main { input.uiState.value.phase == CapturePhase.CAPTURING } }
            PhoneNavigation.click(instrumentation, "Stop")
            await("Stop releases microphone") { main { input.uiState.value.phase == CapturePhase.IDLE } }
            check(main { !operating.uiState.value.armed && !operating.uiState.value.autoSequence.active && operating.uiState.value.sent.isEmpty() })
            report.put("filter_restored_after_recreation", true).put("explicit_recovery_and_stop", true)
            val desktop = main { ViewModelProvider(current)[LanViewModel::class.java] }; lan = desktop
            PhoneNavigation.open(instrumentation, "Audio & source")
            PhoneNavigation.click(instrumentation, "WSJT-CB LAN")
            PhoneNavigation.click(instrumentation, "Operate")
            PhoneNavigation.click(instrumentation, "Start RX")
            await("LAN listener") { main { desktop.uiState.value.active } }
            File(context.cacheDir, "context-ready.json").writeText(JSONObject().put("run", run).put("stage", "lan")
                .put("port", main { desktop.uiState.value.port.toInt() }).toString())
            await("actual UDP rows") { main { desktop.uiState.value.connection.decodes.size == 3 } }
            val lanRows = main { desktop.uiState.value.connection.decodes }
            await("LAN unknown division") { visible("From 999ZZ999 · CB 999 · Country unknown") }
            PhoneNavigation.click(instrumentation, "To me")
            await("LAN directed/replay filter") { visible("Received 1/3") && visible("1AT106 -07") &&
                visible("Desktop · replay · ~ · Off air · Low confidence") }
            check(!visible("Sender unknown"))
            PhoneNavigation.click(instrumentation, "CQ")
            await("LAN CQ filter") { visible("Received 2/3") }
            check(main { desktop.uiState.value.connection.decodes == lanRows && !operating.uiState.value.armed && operating.uiState.value.sent.isEmpty() })
            PhoneNavigation.click(instrumentation, "All")
            PhoneNavigation.click(instrumentation, "Stop")
            await("LAN stopped") { main { !desktop.uiState.value.active && !desktop.uiState.value.busy } }
            PhoneNavigation.open(instrumentation, "Audio & source")
            PhoneNavigation.click(instrumentation, "Phone / USB audio")
            PhoneNavigation.click(instrumentation, "Operate")
            report.put("actual_udp_country_filters_and_replay_provenance", true)
                .put("result", "PASS").put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
            output.putString("context_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("failure", "${error.javaClass.simpleName}: ${error.message}")
            output.putString("context_failure", "${error.message}; ${error.stackTrace.take(3)}")
        } finally {
            model?.let { main { it.stop() } }; capture?.let { main { it.stop(clear = true) } }; lan?.let { main { it.stop() } }
            activity?.let { main { it.finish() } }
            File(context.cacheDir, "context-report.json").writeText(report.toString(2))
        }
        instrumentation.finish(code, output)
    }
}
