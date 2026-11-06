package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.audio.CaptureViewModel
import net.vash.ft8cbdroid.time.TimeViewModel
import org.json.JSONObject

/** Real time-source and microphone checks; GPS needs a real satellite solution, not a supplied location. */
class UtcPhoneChecks(private val instrumentation: Instrumentation) {
    private val targetContext get() = instrumentation.targetContext
    private val uiAutomation get() = instrumentation.uiAutomation
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(description: String, seconds: Long = 5, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + seconds * 1000
        while (!condition()) { check(SystemClock.elapsedRealtime() < until) { "Timed out: $description" }; SystemClock.sleep(20) }
    }
    private fun find(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.text?.toString() == text) return root
        repeat(root.childCount) { find(root.getChild(it), text)?.let { return it } }
        return null
    }
    fun run(scenario: String) {
        val result = Bundle(); val report = JSONObject().put("scenario", scenario)
        var code = Activity.RESULT_CANCELED
        var activity: MainActivity? = null
        var capture: CaptureViewModel? = null
        var time: TimeViewModel? = null
        var track: AudioTrack? = null
        try {
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            activity = instrumentation.startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val timing = main { ViewModelProvider(activity)[TimeViewModel::class.java] }; time = timing
            val audio = main { ViewModelProvider(activity)[CaptureViewModel::class.java] }; capture = audio
            await("saved time settings") { main { !timing.uiState.value.loading } }
            check(main { timing.uiState.value.saved != null })
            if (scenario == "gps") {
                main { check(!timing.requestGps(true)) }
                await("GPS started") { main { timing.uiState.value.busy == "GPS/GNSS" } }
                main { timing.cancel() }
                await("GPS cancellation cleanup") { main { timing.uiState.value.busy == null } }
                check(main { timing.uiState.value.measurement == null })
                report.put("gps_cancel_and_retry", true)
                main { timing.requestGps(true) }
                await("real GNSS UTC", 95) { main { timing.uiState.value.busy == null } }
            } else {
                main { timing.measureNtp() }
                await("actual NTP exchange", 10) { main { timing.uiState.value.busy == null } }
            }
            val measurement = main { requireNotNull(timing.uiState.value.measurement) { timing.uiState.value.error ?: "No valid time measurement" } }
            main { timing.applyMeasurement() }
            val mapping = timing.clock.snapshot()
            check(mapping.source == measurement.source)
            report.put("source", measurement.source).put("offset_ms", measurement.offsetMillis())
                .put("uncertainty_ms", measurement.uncertaintyMillis)
            if (scenario == "ntp-audio") {
                if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache() else uiAutomation.serviceInfo = uiAutomation.serviceInfo
                var button = find(uiAutomation.rootInActiveWindow, "Audio")
                while (button != null && !button.isClickable) button = button.parent
                check(requireNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val manager = targetContext.getSystemService(AudioManager::class.java)
                check(manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) { "Media volume must be audible for the two-second reference tone" }
                val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val mic = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                val wave = ShortArray(96000) { (2000 * sin(2 * PI * 1000 * it / 48000)).roundToInt().toShort() }
                val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(wave.size * 2).build()
                track = player
                check(player.setPreferredDevice(speaker)); check(player.write(wave, 0, wave.size) == wave.size)
                main { audio.select(mic.id); audio.assembleUtc(true); check(!audio.requestStart(true)) }
                await("first complete physical UTC slot", 35) {
                    main { check(audio.uiState.value.error == null) { audio.uiState.value.error!! }; (audio.uiState.value.slots?.completed ?: 0) >= 1 }
                }
                player.play(); SystemClock.sleep(2200)
                check(player.routedDevice?.id == speaker.id)
                await("second physical UTC slot", 20) {
                    main { check(audio.uiState.value.error == null) { audio.uiState.value.error!! }; (audio.uiState.value.slots?.completed ?: 0) >= 2 }
                }
                main { audio.stop() }; await("capture cleanup") { main { audio.uiState.value.phase == CapturePhase.IDLE } }
                val state = main { audio.uiState.value }
                check(requireNotNull(state.latestSlotUtcMillis) % 15000 == 0L)
                check(state.metrics?.route?.input?.id == mic.id)
                main { check(audio.prepareSlotExport()); audio.export("content://net.vash.ft8cbdroid.test.r07/success") }
                await("actual slot export") { main { !audio.uiState.value.exporting } }
                check(main { audio.uiState.value.error == null })
                val bytes = targetContext.contentResolver.openInputStream(Uri.parse("content://net.vash.ft8cbdroid.test.r07/success"))!!.use { it.readBytes() }
                check(bytes.size == 360044)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); buffer.position(44)
                val pcm = FloatArray(180000) { buffer.short / 32768f }
                fun peak(hz: Int): Double = (0 until 15).maxOf { second ->
                    var re = 0.0; var im = 0.0
                    repeat(12000) { i -> val phase = 2 * PI * hz * i / 12000; val value = pcm[second * 12000 + i]; re += value * cos(phase); im += value * sin(phase) }
                    2 * hypot(re, im) / 12000
                }
                val tone = peak(1000); val control = maxOf(peak(900), peak(1100))
                check(tone > 1e-5 && tone > control * 4) { "Physical reference tone was not recovered from the complete UTC slot" }
                File(targetContext.cacheDir, "r11-utc-slot.wav").writeBytes(bytes)
                val slots = requireNotNull(state.slots)
                report.put("complete_slots", slots.completed).put("utc_start_ms", state.latestSlotUtcMillis)
                    .put("drift_ppm", slots.driftPpm).put("wav_bytes", bytes.size)
                    .put("reference_amplitude", tone).put("control_amplitude", control).put("route", state.metrics!!.route.input.label)
                report.put("clock_unchanged", kotlin.math.abs((System.currentTimeMillis() - measurement.observed.unixMillis) -
                    (SystemClock.elapsedRealtimeNanos() - measurement.observed.monotonicNanos) / 1_000_000) < 50)
            }
            report.put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE).put("result", "PASS")
            File(targetContext.cacheDir, "r11-$scenario.json").writeText(report.toString(2))
            result.putString("utc_report", report.toString()); result.putString("utc_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("utc_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}")
        } finally {
            runCatching { track?.stop() }; runCatching { track?.release() }
            time?.let { runCatching { main { it.cancel() } } }
            capture?.let { runCatching { main { it.stop(clear = true) } } }
            activity?.let { runCatching { main { it.finish() } } }
        }
        instrumentation.finish(code, result)
    }
}
