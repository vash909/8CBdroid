package net.vash.ft8cbdroid.engineprobe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.*
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.audio.AndroidMicrophoneCapture
import net.vash.ft8cbdroid.core.audio.*
import org.json.JSONObject

/** Only this explicitly invoked probe records audio and emits a short local reference tone. */
class AudioProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "8CBdroid R10 microphone test\nCapturing a short 1000 Hz reference tone.\nNo radio transmission."; textSize = 22f })
    }
}

class AudioCaptureProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var capture: AndroidMicrophoneCapture? = null
        var track: AudioTrack? = null
        var code = Activity.RESULT_CANCELED
        try {
            activity = startActivitySync(Intent(targetContext, AudioProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val manager = targetContext.getSystemService(AudioManager::class.java)
            check(manager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) { "Raise media volume above zero and retry the reference-tone test" }
            val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).first { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val wave = ShortArray(48000) { (8000 * sin(2 * PI * 1000 * it / 48000)).roundToInt().toShort() }
            val player = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(wave.size * 2).build()
            track = player
            check(player.setPreferredDevice(speaker))
            check(player.write(wave, 0, wave.size) == wave.size)
            check(player.setLoopPoints(0, wave.size, -1) == AudioTrack.SUCCESS)
            capture = AndroidMicrophoneCapture(targetContext)
            val recorder = capture
            player.play()
            val report = runBlocking {
                val latest = AtomicReference<CaptureMetrics>()
                val task = launch(Dispatchers.Default) { recorder.capture(input.id) { latest.set(it) } }
                val samples: FloatArray
                try {
                    withTimeout(8000) { while ((latest.get()?.outputSamples ?: 0) < 36000) delay(20) }
                    check(player.routedDevice?.id == speaker.id) { "Reference output did not reach the built-in speaker" }
                    samples = recorder.snapshot()
                } finally { withTimeout(3000) { task.cancelAndJoin() } }
                val metrics = requireNotNull(latest.get())
                check(metrics.route.input.id == input.id && samples.size in 36000..60000)
                // Analyze the last second, after input/filter warmup. No personal audio is published.
                val tail = samples.takeLast(12000)
                fun amplitude(hz: Int): Double {
                    var real = 0.0; var imaginary = 0.0
                    tail.forEachIndexed { i, value ->
                        val window = .5 - .5 * cos(2 * PI * i / (tail.size - 1))
                        real += value * window * cos(2 * PI * hz * i / 12000)
                        imaginary += value * window * sin(2 * PI * hz * i / 12000)
                    }
                    return 4 * hypot(real, imaginary) / tail.size
                }
                val peak = (990..1010).maxBy { amplitude(it) }
                val magnitude = amplitude(peak)
                check(abs(peak - 1000) <= 3 && magnitude > .0001 && magnitude > 5 * maxOf(amplitude(900), amplitude(1100))) {
                    "Reference tone was not received clearly; check media volume and microphone route"
                }
                val wav = CaptureWav.encode(samples)
                File(targetContext.filesDir, "r10-capture.wav").writeBytes(wav)
                val start = SystemClock.elapsedRealtime()
                val fresh = AtomicReference<CaptureMetrics>()
                val restart = launch(Dispatchers.Default) { recorder.capture(input.id) { fresh.set(it) } }
                try { withTimeout(4000) { while ((fresh.get()?.outputSamples ?: 0) < 2400) delay(20) } }
                finally { withTimeout(3000) { restart.cancelAndJoin() } }
                JSONObject().put("result", "PASS").put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
                    .put("client_rate", metrics.route.clientRate).put("device_format", metrics.route.deviceFormat)
                    .put("input", metrics.route.input.label).put("source", metrics.route.source).put("processing", metrics.route.processing)
                    .put("reference_hz", peak).put("reference_amplitude", magnitude).put("input_samples", metrics.inputSamples)
                    .put("output_samples", metrics.outputSamples).put("retained_samples", samples.size)
                    .put("clipped_input_samples", metrics.clippedSamples).put("restart_ms", SystemClock.elapsedRealtime() - start)
                    .put("wav_sha256", MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) })
            }
            File(targetContext.filesDir, "r10-capture.json").writeText(report.toString(2))
            result.putString("audio_result", "PASS")
            result.putString("audio_report", report.toString())
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("audio_failure", "${error.javaClass.simpleName}: ${error.message}")
        } finally {
            track?.let { runCatching { it.stop() }; runCatching { it.release() } }
            runCatching { capture?.close() }
            activity?.let { runCatching { runOnMainSync { it.finish() } } }
        }
        finish(code, result)
    }
}
