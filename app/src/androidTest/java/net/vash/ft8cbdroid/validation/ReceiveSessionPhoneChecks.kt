package net.vash.ft8cbdroid.validation

import android.app.*
import android.content.Intent
import android.media.AudioManager
import android.os.*
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.ntp.UdpNtpClient
import net.vash.ft8cbdroid.receive.*
import net.vash.ft8cbdroid.time.AndroidTimeSource
import net.vash.ft8cbdroid.operate.ManualOperatingViewModel
import org.json.JSONObject
import java.io.File

/** Real service, recorder, slot assembler, LAN socket, notifications and Activity lifecycle. */
class ReceiveSessionPhoneChecks(private val instrumentation: Instrumentation, private val run: String, private val cold: Boolean = false) {
    private val context get() = instrumentation.targetContext
    private val runtime get() = (context.applicationContext as EightCbApplication).receiver
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, timeout: Long = 12000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "Timed out: $label" }; SystemClock.sleep(40) }
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand("timeout 5 $command")).use { it.readBytes().decodeToString() }
    private fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) as MainActivity
    private fun ready(stage: String, extras: JSONObject = JSONObject()) {
        extras.put("run", run).put("stage", stage)
        File(context.cacheDir, "receive-ready.json").writeText(extras.toString())
    }
    private fun input(stage: String): Long {
        var result: JSONObject? = null
        await("host playback plan for $stage", 45000) {
            val data = runCatching { JSONObject(File(context.cacheDir, "receive-input.json").readText()) }.getOrNull()
            if (data?.optString("run") == run && data.optString("stage") == stage) result = data
            result != null
        }
        return requireNotNull(result).getLong("utc_millis")
    }
    private fun waitUtc(value: Long) { while (System.currentTimeMillis() < value) SystemClock.sleep(30) }
    private fun hasText(text: String) = main { runtime.capture.uiState.value.live.rows.any {
        (it.decode.message as? UnpackResult.Decoded)?.message?.text?.trim() == text
    } }
    private fun serviceCount() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).count { it.service.className == ReceiveService::class.java.name && it.foreground }
    private fun assertOne(runId: Long) {
        check(main { runtime.capture.uiState.value.receiveRun == runId && runtime.capture.uiState.value.phase == CapturePhase.CAPTURING })
        check(serviceCount() == 1) { "Expected one foreground receiver" }
        check(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.size == 1) { "Expected one actual recorder" }
    }
    private fun notificationStop() {
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .single { it.id == ReceiveService.NOTIFICATION }.notification
        requireNotNull(notification.actions).single { it.title == "Stop RX" }.actionIntent.send()
        await("notification Stop cleanup") { main { !runtime.session.uiState.value.active && runtime.capture.uiState.value.phase == CapturePhase.IDLE && !runtime.lan.uiState.value.active } && serviceCount() == 0 }
    }
    fun run() {
        val report = JSONObject().put("run", run).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
        val output = Bundle(); var code = Activity.RESULT_CANCELED; var activity: MainActivity? = null
        try {
            require(run.matches(Regex("[a-zA-Z0-9-]{1,64}")))
            var current = launch(); activity = current
            await("saved receive graph") { main { runtime.profiles.uiState.value.ready && runtime.capture.uiState.value.canStart && runtime.time.uiState.value.saved != null } }
            if (cold) {
                SystemClock.sleep(1500)
                check(main { !runtime.session.uiState.value.active && runtime.capture.uiState.value.phase == CapturePhase.IDLE && !runtime.lan.uiState.value.active })
                check(serviceCount() == 0)
                val manual = main { ViewModelProvider(current)[ManualOperatingViewModel::class.java] }
                check(main { !manual.uiState.value.armed && !manual.uiState.value.autoSequence.active && manual.uiState.value.sent.isEmpty() })
                report.put("cold_process_no_receive_or_tx_restart", true)
            } else {
                if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
                val mapping = runBlocking { UdpNtpClient(AndroidTimeSource).measure(main { requireNotNull(runtime.time.uiState.value.saved).host }) }
                main { runtime.time.clock.apply(mapping) }
                PhoneNavigation.click(instrumentation, "Start RX")
                await("microphone service") { main { runtime.capture.uiState.value.phase == CapturePhase.CAPTURING && runtime.session.uiState.value.phase == ReceivePhase.RUNNING } }
                val id = main { runtime.capture.uiState.value.receiveRun }
                assertOne(id)
                ready("local-navigation")
                val first = input("local-navigation")
                waitUtc(first + 3000)
                PhoneNavigation.click(instrumentation, "Settings")
                PhoneNavigation.click(instrumentation, "Station")
                assertOne(id)
                PhoneNavigation.click(instrumentation, "Chat")
                assertOne(id)
                PhoneNavigation.click(instrumentation, "Operate")
                await("CQ decoded across navigation", 30000) { hasText("CQ 26AT101") }
                assertOne(id)
                report.put("real_audio_slot_and_decode_across_settings_chat_navigation", true)
                ready("local-background")
                val second = input("local-background")
                waitUtc(second + 3000)
                shell("input keyevent KEYCODE_HOME")
                await("CQ decoded in background", 30000) { hasText("CQ 13AT101") }
                assertOne(id)
                report.put("real_audio_slot_and_decode_in_background", true)
                current = launch(); activity = current
                PhoneNavigation.click(instrumentation, "Operate")
                val samples = main { requireNotNull(runtime.capture.uiState.value.metrics).inputSamples }
                val rotation = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                main { current.requestedOrientation = if (current.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                current = requireNotNull(rotation.waitForActivityWithTimeout(8000)) as MainActivity
                activity = current; instrumentation.removeMonitor(rotation)
                SystemClock.sleep(500)
                assertOne(id)
                check(main { requireNotNull(runtime.capture.uiState.value.metrics).inputSamples > samples })
                // Destroy the screen's ViewModelStore and attach a new Activity to the same live graph.
                main { current.finish() }
                SystemClock.sleep(500)
                current = launch(); activity = current
                await("new Activity joins existing receive graph") { main { runtime.profiles.uiState.value.ready } }
                assertOne(id)
                report.put("rotation_and_destroyed_activity_keep_same_recorder_and_run", true)
                notificationStop()
                check(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
                report.put("real_notification_stop_releases_microphone", true)
                PhoneNavigation.click(instrumentation, "Start RX")
                await("explicit retry before interruption") { main { runtime.capture.uiState.value.phase == CapturePhase.CAPTURING } }
                ready("microphone-interruption")
                await("actual microphone access interruption", 15000) { main { !runtime.session.uiState.value.active && runtime.session.uiState.value.error != null && runtime.capture.uiState.value.phase == CapturePhase.IDLE } }
                ready("microphone-restore")
                input("microphone-restore")
                SystemClock.sleep(1200)
                check(main { runtime.capture.uiState.value.phase == CapturePhase.IDLE && !runtime.session.uiState.value.active })
                PhoneNavigation.click(instrumentation, "Start RX")
                await("explicit recovery after permission interruption") { main { runtime.capture.uiState.value.phase == CapturePhase.CAPTURING } }
                PhoneNavigation.click(instrumentation, "Stop RX")
                await("in-app Stop") { main { !runtime.session.uiState.value.active } }
                report.put("actual_microphone_access_interruption_explicit_recovery_and_in_app_stop", true)
                PhoneNavigation.open(instrumentation, "Audio & source")
                PhoneNavigation.click(instrumentation, "WSJT-CB LAN")
                PhoneNavigation.click(instrumentation, "Operate")
                val originalPort = main { runtime.lan.uiState.value.port }
                val occupied = java.net.DatagramSocket(0)
                try {
                    main { runtime.lan.changePort(occupied.localPort.toString()) }
                    PhoneNavigation.click(instrumentation, "Start RX")
                    await("real bind failure with released service") {
                        main { !runtime.session.uiState.value.active && runtime.session.uiState.value.error?.contains("UDP") == true } && serviceCount() == 0
                    }
                } finally { occupied.close() }
                main { runtime.lan.changePort(originalPort) }
                report.put("real_lan_bind_failure_visible_and_explicit_retry", true)
                PhoneNavigation.click(instrumentation, "Start RX")
                await("LAN service") { main { runtime.lan.uiState.value.active && runtime.session.uiState.value.source == ReceiveSource.LAN } }
                val port = main { runtime.lan.uiState.value.port.toInt() }
                ready("lan-navigation", JSONObject().put("port", port))
                PhoneNavigation.click(instrumentation, "Settings")
                PhoneNavigation.click(instrumentation, "Chat")
                await("LAN decode across navigation", 15000) { main { runtime.lan.uiState.value.connection.decodes.any { it.message == "CQ 26AT101" } } }
                ready("lan-background", JSONObject().put("port", port))
                shell("input keyevent KEYCODE_HOME")
                await("LAN decode in background", 15000) { main { runtime.lan.uiState.value.connection.decodes.any { it.message == "CQ 13AT101" } } }
                check(serviceCount() == 1)
                ready("lan-screen-off", JSONObject().put("port", port))
                shell("input keyevent KEYCODE_SLEEP")
                await("screen off") { !context.getSystemService(PowerManager::class.java).isInteractive }
                await("LAN decode with screen off", 15000) { main { runtime.lan.uiState.value.connection.decodes.any { it.message == "CQ 14AT101" } } }
                notificationStop()
                report.put("actual_lan_navigation_background_screen_off_and_notification_stop", true)
                shell("input keyevent KEYCODE_WAKEUP")
                ready("unlock")
                await("unlocked phone", 180000) { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
                current = launch(); activity = current
                PhoneNavigation.open(instrumentation, "Audio & source")
                PhoneNavigation.click(instrumentation, "Phone / USB audio")
                PhoneNavigation.click(instrumentation, "Operate")
                runBlocking(Dispatchers.Main) { runtime.time.resetForProfile() }
                val renewed = runBlocking { UdpNtpClient(AndroidTimeSource).measure(main { requireNotNull(runtime.time.uiState.value.saved).host }) }
                main { runtime.time.clock.apply(renewed) }
                PhoneNavigation.click(instrumentation, "Start RX")
                await("microphone resumed explicitly") { main { runtime.capture.uiState.value.phase == CapturePhase.CAPTURING } }
                val thirdId = main { runtime.capture.uiState.value.receiveRun }
                ready("local-screen-off")
                val third = input("local-screen-off")
                waitUtc(third + 3000)
                shell("input keyevent KEYCODE_SLEEP")
                await("screen off during actual FT8") { !context.getSystemService(PowerManager::class.java).isInteractive }
                await("CQ decoded with screen off", 30000) { hasText("CQ 14AT101") }
                assertOne(thirdId)
                val oldTimeRevision = main { runtime.time.clock.revision }
                ready("background-ntp-refresh")
                await("actual five-minute NTP refresh while screen off", 320000) {
                    main { runtime.time.clock.revision > oldTimeRevision && runtime.capture.uiState.value.phase == CapturePhase.CAPTURING && runtime.capture.uiState.value.receiveRun > thirdId }
                }
                check(!context.getSystemService(PowerManager::class.java).isInteractive)
                check(serviceCount() == 1)
                check(main { runtime.session.uiState.value.phase == ReceivePhase.RUNNING && runtime.session.uiState.value.error == null })
                report.put("actual_five_minute_background_ntp_refresh_with_joined_receive_boundary", true)
                notificationStop()
                report.put("real_audio_slot_and_decode_with_screen_off_and_stop", true)
                shell("input keyevent KEYCODE_WAKEUP")
            }
            report.put("result", "PASS"); code = Activity.RESULT_OK; output.putString("receive_result", "PASS")
        } catch (error: Throwable) {
            report.put("result", "FAIL").put("failure", "${error.javaClass.simpleName}: ${error.message}")
            output.putString("receive_failure", "${error.message}; ${error.stackTrace.take(4)}")
        } finally {
            main { runtime.stop() }
            activity?.let { main { it.finish() } }
            File(context.cacheDir, if (cold) "receive-cold-report.json" else "receive-report.json").writeText(report.toString(2))
        }
        instrumentation.finish(code, output)
    }
}
