package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.UiAutomation
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.app.Instrumentation
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.vash.ft8cbdroid.ui.*
import net.vash.ft8cbdroid.audio.CaptureUiState
import net.vash.ft8cbdroid.lan.LanUiState
import net.vash.ft8cbdroid.operate.ManualActions
import net.vash.ft8cbdroid.operate.ManualOperatingState
import net.vash.ft8cbdroid.settings.StationSettingsUiState
import net.vash.ft8cbdroid.time.TimeUiState
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.*
import net.vash.ft8cbdroid.core.recording.*
import java.time.Instant
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.core.time.TimeMeasurement
import net.vash.ft8cbdroid.protocol.LanPhase
import org.json.JSONObject

/** Real production screen, UTC clock ticks and fifteen simultaneous on-device UDP decodes. No audio/TX. */
class OperateLayoutPhoneChecks(private val instrumentation: Instrumentation, private val run: String) {
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null; instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, millis: Long = 12000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + millis
        while (!condition()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label" }; SystemClock.sleep(50) }
    }
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun all() = nodes(PhoneNavigation.root(instrumentation))
    private fun visible(text: String) = all().any { it.text?.toString() == text || it.contentDescription?.toString()?.startsWith("Message: $text;") == true }
    private fun timer() = all().firstOrNull { it.contentDescription?.toString()?.startsWith("FT8 period:") == true }?.contentDescription?.toString()
    private fun click(text: String) = PhoneNavigation.click(instrumentation, text)
    private fun DataOutputStream.utf8(value: String) { val b = value.toByteArray(); writeInt(b.size); write(b) }
    private fun packet(type: Int, body: DataOutputStream.() -> Unit) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeInt(0xadbccbda.toInt()); it.writeInt(2); it.writeInt(type); it.utf8("layout-$run"); it.body() }
    }.toByteArray()
    fun run() {
        val context = instrumentation.targetContext; val result = Bundle(); val report = JSONObject().put("run", run)
        var activity: MainActivity? = null; var code = Activity.RESULT_CANCELED
        var cleanup: (() -> Unit)? = null
        try {
            require(run.matches(Regex("[A-Za-z0-9-]{1,64}")))
            var current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity = current; val runtime = current.receiveRuntime
            await("graph") { main { runtime.profiles.uiState.value.ready && runtime.time.uiState.value.saved != null } }
            check(main { !runtime.session.uiState.value.active && !runtime.manual.uiState.value.hasTxOperation })
            val previous = main { runtime.lan.uiState.value }; val source = main { runtime.profiles.uiState.value.receiveLan }
            cleanup = {
                main { runtime.stop(); runtime.time.clock.reset() }
                await("listener stopped") { main { !runtime.lan.uiState.value.active && !runtime.lan.uiState.value.busy && !runtime.session.uiState.value.active } }
                main { runtime.lan.changePort(previous.port); runtime.lan.changeAddress(previous.desktopAddress); runtime.profiles.temporarySource(source) }
            }
            await("unsynchronized period") { timer()?.contains("Device UTC (not synchronized)") == true }
            val values = mutableSetOf<String>()
            val until = SystemClock.elapsedRealtime() + 17000
            while (SystemClock.elapsedRealtime() < until) { timer()?.let(values::add); SystemClock.sleep(250) }
            check(values.size >= 12 && values.any { "Even" in it } && values.any { "Odd" in it })
            report.put("real_utc_ticks_rollover_and_unsynchronized_label", true)
            // An explicitly labeled UI clock fixture tests corrected presentation, not NTP/GPS acquisition.
            main {
                val now = runtime.time.clock.source.read()
                runtime.time.clock.apply(TimeMeasurement("UI clock fixture", now.monotonicNanos, now.unixMillis * 1_000_000 + 2_000_000_000L, 1.0, now))
                runtime.time.poll()
            }
            await("corrected timer") { timer()?.contains("Corrected UTC") == true }
            report.put("applied_clock_presentation_only", true)
            val port = DatagramSocket(0).use { it.localPort }
            main { runtime.profiles.temporarySource(true); runtime.lan.changePort(port.toString()); runtime.lan.changeAddress("127.0.0.1") }
            await("LAN selected on screen") { visible("LAN carries messages, not waterfall audio.") }
            click("Start RX"); await("bound socket") { main { runtime.lan.uiState.value.active } }
            DatagramSocket().use { sender ->
                fun send(data: ByteArray) = sender.send(DatagramPacket(data, data.size, InetAddress.getByName("127.0.0.1"), port))
                send(packet(0) { writeInt(3); utf8("UI fixture"); utf8("test") })
                await("heartbeat") { main { runtime.lan.uiState.value.connection.phase == LanPhase.CONNECTED } }
                repeat(15) { i -> send(packet(2) {
                    writeBoolean(i != 14); writeInt(12_300_000); writeInt(-5 - i); writeDouble(.3); writeInt(500 + 100 * i)
                    utf8("~"); utf8("CQ 26AT${100 + i}"); writeBoolean(i == 14); writeBoolean(i == 14)
                }) }
                await("all 15 decodes") { main { runtime.lan.uiState.value.connection.decodes.size == 15 } }
                await("received count") { visible("Received 15") }
                val shown = all().filter { it.contentDescription?.toString()?.startsWith("Message: CQ 26AT1") == true && it.isVisibleToUser }.filter {
                    val b = Rect(); it.getBoundsInScreen(b); b.height() > 0
                }
                check(shown.size >= 8) { "Only ${shown.size} dense messages visible" }
                report.put("simultaneous_udp_messages", 15).put("visible_dense_messages", shown.size)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "operate-layout-dense.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val scrolls = all().filter { it.isScrollable }
                scrolls.first().performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); SystemClock.sleep(200)
                all().last { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); SystemClock.sleep(200)
                await("oldest station reachable") { visible("CQ 26AT100") }
                all().last { it.isScrollable }.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); SystemClock.sleep(200)
                report.put("all_station_rows_reachable", true)
                click("CQ 26AT114")
                await("details") { visible("Message details") && all().any { it.text?.toString()?.contains("Low confidence") == true } }
                check(visible("From 26AT114 · CB 026 · England")); check(!visible("Use peer"))
                click("Close"); click("CQ")
                await("CQ filter") { visible("Received 15/15") }; click("All")
                main { current.recreate() }; SystemClock.sleep(800)
                await("rotation state") { visible("Received 15") && timer() != null }
                click("Settings"); click("Operate")
                await("navigation state") { visible("Received 15") && timer() != null }
                report.put("full_details_flags_filter_recreation_navigation", true)
                check(instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                await("landscape") { val b = Rect(); PhoneNavigation.root(instrumentation)?.getBoundsInScreen(b); b.width() > b.height() }
                check(timer() != null && main { runtime.lan.uiState.value.connection.decodes.size == 15 })
                check(instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0))
                await("portrait") { val b = Rect(); PhoneNavigation.root(instrumentation)?.getBoundsInScreen(b); b.height() > b.width() }
                report.put("actual_rotation_retains_messages_and_period_footer", true)
                // Scroll the page while leaving the compact clock footer visible.
                val scroller = all().firstOrNull { it.isScrollable }
                scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); SystemClock.sleep(200)
                check(timer() != null); report.put("period_footer_retained_while_scrolling", true)
            }
            click("Stop"); await("Stop clears rows") { main { runtime.lan.uiState.value.connection.decodes.isEmpty() && !runtime.lan.uiState.value.active } }
            check(main { !runtime.manual.uiState.value.armed && !runtime.manual.uiState.value.hasTxOperation })
            report.put("explicit_stop_and_no_tx", true)
            // Local presentation fixture exercises shipped row/dialog actions without inventing microphone reception.
            current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = current
            val payload = (CbMessageCodec.pack("CQ 26AT101") as PackResult.Encoded).payload
            val utc = Instant.parse("2026-11-17T12:00:00Z")
            val decoded = RecordedDecode(Ft8DecodedFrame(payload, .8f, 1200f, 30, -7f), CbMessageCodec.unpack(payload), utc, utc, .3f, setOf(RecordedDecodeFlag.CRC_LDPC_VALID, RecordedDecodeFlag.SNR_ESTIMATED))
            var selected: ManualPeer? = null; var rx = 0; var fontScale by mutableStateOf(1f)
            var view by mutableStateOf(SpectrumView())
            var answering by mutableStateOf(false)
            var answered: LiveDecodeRow? = null
            var answerCount = 0
            val longText = "Unsupported: Long message text remains available in full in the scrollable details dialog"
            val longDecoded = decoded.copy(message = UnpackResult.Unsupported(longText.removePrefix("Unsupported: ")))
            main { current.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) { RadioTheme {
                OperatePanel(StationSettingsUiState(saved = StationIdentity("1AT106", "JN44")), OperatingSource.LOCAL,
                    CaptureUiState(spectrumView = view, live = LiveReceiveState(rows = listOf(LiveDecodeRow(decoded, "UI fixture", null), LiveDecodeRow(longDecoded, "UI fixture", null)))), LanUiState(),
                    TimeUiState(), {}, {}, {}, {}, { rx = it.rxHz; view = it }, { Box(Modifier.height(108.dp)) },
                    manual = ManualOperatingState(busy = answering),
                    manualActions = ManualActions(selectPeer = { selected = it }, answerDecode = { answered = it; answerCount++ }))
            } } } }
            await("local fixture row") { visible("CQ 26AT101") }
            fun stationRow() = all().first { it.contentDescription?.toString()?.startsWith("Message: CQ 26AT101;") == true }
            // Check the rendered background of an untouched row, independent of accessibility selection mapping or touch ripples.
            fun highlightPixel(): Int {
                val node = all().first { it.contentDescription?.toString()?.startsWith("Message: $longText;") == true }
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                return try { image.getPixel(bounds.left + 3, bounds.centerY()) } finally { image.recycle() }
            }
            val unselectedPixel = highlightPixel()
            PhoneNavigation.hold(instrumentation, "CQ 26AT101")
            await("explicit hold callback") { answerCount == 1 }
            check(answered?.decode?.frame?.payload == payload && !visible("Message details"))
            main { answering = true }; SystemClock.sleep(200)
            check(!stationRow().isLongClickable)
            main { answering = false }; SystemClock.sleep(200)
            click("CQ 26AT101"); await("local details") { visible("Message details") && visible("Use peer") }
            click("Use peer"); check(selected?.callsign == "26AT101" && answerCount == 1)
            click("CQ 26AT101"); click("→ RX"); check(rx == 1200)
            await("rendered RX frequency highlight") { highlightPixel() != unselectedPixel }
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                try { File(context.cacheDir, "operate-layout-rx-highlight.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { image.recycle() }
            }
            main { view = view.copy(rxHz = 1300) }
            await("RX highlight moves without removing messages") { highlightPixel() == unselectedPixel && visible("CQ 26AT101") }
            report.put("rx_highlight_and_explicit_hold_presentation_callbacks", true)
            main { fontScale = 1.4f }; SystemClock.sleep(400)
            click(longText); check(all().any { it.text?.toString() == longText })
            click("Close"); report.put("large_text_full_message_details", true)
            check(main { !runtime.manual.uiState.value.armed && !runtime.manual.uiState.value.hasTxOperation })
            report.put("local_presentation_fixture_details_peer_and_rx_actions_no_tx", true).put("result", "PASS"); result.putString("operate_layout_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) { report.put("result", "FAIL").put("error", "${error.javaClass.simpleName}: ${error.message}"); result.putString("operate_layout_result", report.toString()); error.printStackTrace() }
        finally {
            runCatching { instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE) }
            runCatching { cleanup?.invoke() }.onFailure { report.put("cleanup_error", it.toString()); code = Activity.RESULT_CANCELED }
            activity?.let { runCatching { main { it.finish() } } }
            File(context.cacheDir, "operate-layout-report.json").writeText(report.toString(2))
        }
        instrumentation.finish(code, result)
    }
}
