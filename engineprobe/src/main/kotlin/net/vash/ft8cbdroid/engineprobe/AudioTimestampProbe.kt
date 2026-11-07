package net.vash.ft8cbdroid.engineprobe

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class TimestampProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "8CBdroid capture timing diagnostic\nCollecting timestamps for 10 seconds. No audio is saved or played."
            textSize = 22f
        })
    }
}

/** Hardware observations only: collection is not a UTC-slot qualification pass. */
class AudioTimestampProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle(); var code = Activity.RESULT_CANCELED
        var activity: Activity? = null; var recorder: AudioRecord? = null; var echo: AcousticEchoCanceler? = null
        try {
            activity = startActivitySync(Intent(targetContext, TimestampProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val manager = targetContext.getSystemService(AudioManager::class.java)
            val mic = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).first { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            if (targetContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Grant microphone permission before collecting timestamps.")
            val input = AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, 19200)).build()
            recorder = input
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(input.audioSessionId)?.also { it.setEnabled(false) }
            check(input.setPreferredDevice(mic)); input.startRecording()
            val samples = ShortArray(960); val timestamp = AudioTimestamp(); val monotonic = AudioTimestamp(); var frames = 0L
            val points = JSONArray(); val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < 10_000) {
                val count = input.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0); frames += count
                if (count > 0 && input.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS &&
                    input.getTimestamp(monotonic, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                    points.put(JSONArray().put(frames).put(timestamp.framePosition).put(timestamp.nanoTime)
                        .put(SystemClock.elapsedRealtimeNanos()).put(monotonic.framePosition).put(monotonic.nanoTime).put(System.nanoTime()))
                SystemClock.sleep(5)
            }
            check(points.length() >= 100)
            val report = JSONObject().put("result", "DIAGNOSTIC").put("model", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE).put("rate", input.sampleRate)
                .put("buffer_frames", input.bufferSizeInFrames).put("source", source).put("points", points)
            File(targetContext.filesDir, "r11-timestamps.json").writeText(report.toString())
            result.putString("timestamp_result", "COLLECTED"); code = Activity.RESULT_OK
        } catch (error: Throwable) { result.putString("timestamp_failure", "${error.javaClass.simpleName}: ${error.message}") }
        finally {
            runCatching { recorder?.stop() }; runCatching { echo?.release() }; runCatching { recorder?.release() }
            activity?.let { runCatching { runOnMainSync { it.finish() } } }
        }
        finish(code, result)
    }
}
