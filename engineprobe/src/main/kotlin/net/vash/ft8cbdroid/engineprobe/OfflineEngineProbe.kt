package net.vash.ft8cbdroid.engineprobe

import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.dsp.NativeFt8Engine
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Separate no-permission APK: never opens the main app, audio devices or network. */
class OfflineEngineProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        try {
            runBlocking {
                val engine = NativeFt8Engine()
                val corpus = JSONObject(context.assets.open("channel.json").bufferedReader().use { it.readText() })
                val waves = corpus.getJSONArray("waves")
                val timings = mutableListOf<Long>()
                repeat(waves.length()) { i ->
                    val fixture = waves.getJSONObject(i)
                    val bytes = context.assets.open(fixture.getString("file")).use { it.readBytes() }
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    check(hash == fixture.getString("sha256")) { "Desktop WAV checksum mismatch" }
                    check(bytes.size == 44 + Ft8PcmSlot.sampleCount * 2)
                    val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
                    val pcm = Ft8PcmSlot.fromSamples(FloatArray(Ft8PcmSlot.sampleCount) { buffer.short / 32768f })
                    val start = SystemClock.elapsedRealtime()
                    val decoded = engine.decode(pcm)
                    timings += SystemClock.elapsedRealtime() - start
                    check(decoded.frames.size == 1) { "Expected exactly one desktop frame" }
                    val frame = decoded.frames.single()
                    check(frame.payload.toBitString() == fixture.getString("payload77")) { "Desktop payload mismatch" }
                    check(abs(frame.audioFrequencyHz - fixture.getDouble("frequency_hz")) <= 6.25)
                    check(abs(frame.startSecondsInSlot - fixture.getDouble("lead_seconds")) <= .16)
                }
                val payload = Ft8Payload77.fromBits(waves.getJSONObject(0).getString("payload77"))
                val start = SystemClock.elapsedRealtime()
                val encoded = engine.encode(payload, 1000f)
                report.putLong("encode_ms", SystemClock.elapsedRealtime() - start)
                check(encoded.toFloatArray().any { abs(it) > .1f })
                check(engine.decode(encoded).frames.map { it.payload } == listOf(payload)) { "Roundtrip mismatch" }
                check(engine.decode(Ft8PcmSlot.fromSamples(FloatArray(Ft8PcmSlot.sampleCount))).frames.isEmpty()) {
                    "Silence produced a valid frame"
                }
                check(runCatching { engine.encode(payload, Float.NaN) }.isFailure) { "Invalid frequency accepted" }
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                report.putString("desktop_decode_ms", timings.joinToString(","))
                report.putInt("desktop_wavs", waves.length())
                report.putInt("pss_after_kib", memory.totalPss)
                report.putLong("native_heap_after_bytes", Debug.getNativeHeapAllocatedSize())
                report.putString("result", "PASS: independent desktop WAVs, encode/decode, silence and input rejection")
            }
            finish(-1, report)
        } catch (error: Exception) {
            report.putString("result", "FAIL: ${error.javaClass.simpleName}: ${error.message}")
            finish(0, report)
        }
    }
}
