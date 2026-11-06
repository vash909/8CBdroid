package net.vash.ft8cbdroid.engineprobe

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.core.*
import net.vash.ft8cbdroid.core.recording.RecordedSlotWav
import net.vash.ft8cbdroid.dsp.NativeFt8Engine
import net.vash.ft8cbdroid.reference.BenchmarkSignals
import org.json.JSONArray
import org.json.JSONObject

/** Explicit finite diagnostic run. No main-app access, audio, RF or private recordings. */
class DecoderProfileProbe(private val context: Context) {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun thermal(): JSONObject {
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else JSONObject.NULL)
            .put("battery_tenths_celsius", battery?.getIntExtra("temperature", -1) ?: -1)
    }
    private fun slot(case: JSONObject): Ft8PcmSlot {
        fun read(file: String) = context.assets.open(file).use { RecordedSlotWav.read(it) }
        val value = when (case.getString("kind")) {
            "recorded", "negative" -> read(case.getString("file"))
            "sweep" -> BenchmarkSignals.signal(read(case.getString("file")), case.getInt("snr_db"), case.getInt("seed"))
            else -> BenchmarkSignals.signal(null, null, case.getInt("seed"), case.getInt("tone_hz"))
        }
        check(hash(BenchmarkSignals.pcm16(value)) == case.getString("sha256_pcm")) { "Benchmark PCM differs: ${case.getString("id")}" }
        return value
    }
    suspend fun run(engine: NativeFt8Engine): JSONObject = coroutineScope {
        val manifestBytes = context.assets.open("benchmark.json").use { it.readBytes() }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val cases = manifest.getJSONArray("cases")
        val settings = manifest.getJSONArray("profiles")
        Ft8DecodeProfile.entries.forEachIndexed { index, profile ->
            val expected = settings.getJSONObject(index)
            check(profile.maxCandidates == expected.getInt("candidates") && profile.maxIterations == expected.getInt("iterations") &&
                profile.deadlineMillis == expected.getLong("deadline_ms"))
        }
        val report = JSONObject().put("schema", 1).put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
            .put("abi", Build.SUPPORTED_ABIS.first()).put("manifest_sha256", hash(manifestBytes))
            .put("thermal_before", thermal())
        var sampledPss = Debug.getPss()
        var sampledNative = Debug.getNativeHeapAllocatedSize()
        var samples = 0
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                sampledPss = maxOf(sampledPss, Debug.getPss())
                sampledNative = maxOf(sampledNative, Debug.getNativeHeapAllocatedSize())
                samples++
                delay(50)
            }
        }
        try {
            val rows = JSONArray()
            for (profile in Ft8DecodeProfile.entries) {
                repeat(cases.length()) { i ->
                    currentCoroutineContext().ensureActive()
                    val case = cases.getJSONObject(i)
                    val pcm = slot(case)
                    val result = engine.decode(pcm, profile)
                    check(result.candidateCount <= profile.maxCandidates && requireNotNull(result.processedCandidates) <= profile.maxCandidates)
                    val expected = if (case.isNull("payload77")) null else case.getString("payload77")
                    val bits = result.frames.map { it.payload.toBitString() }
                    val wrong = bits.count { it != expected }
                    check(wrong == 0) { "Unexpected payload: ${case.getString("id")}/${profile.label}" }
                    val clean = case.optString("file") in setOf("cq.wav", "leading-hash.wav", "trailing-hash.wav", "roger-report.wav")
                    if (case.getString("kind") == "recorded" && (clean || profile == Ft8DecodeProfile.STANDARD))
                        check(bits == listOf(expected)) { "Baseline reception regression" }
                    rows.put(JSONObject().put("case", case.getString("id")).put("profile", profile.label)
                        .put("detected", expected != null && expected in bits).put("false_frames", wrong)
                        .put("decode_ms", result.decodeMillis).put("candidates", result.candidateCount))
                }
            }
            report.put("cases", rows).put("thermal_after_corpus", thermal())
            val cancellation = JSONArray()
            val testSlot = context.assets.open("cq.wav").use { RecordedSlotWav.read(it) }
            repeat(5) {
                val first = async(Dispatchers.Default) { engine.decode(testSlot, Ft8DecodeProfile.STANDARD) }
                withTimeout(2000) { while (NativeFt8Engine.workSnapshot().stage != 1) delay(1) }
                val queued = async(start = CoroutineStart.UNDISPATCHED) { engine.decode(testSlot) }
                check(NativeFt8Engine.workSnapshot().admitted == 2)
                try { engine.decode(testSlot); error("Queue admitted a third request") } catch (_: Ft8EngineBusyException) { }
                queued.cancelAndJoin()
                check(NativeFt8Engine.workSnapshot().admitted == 1)
                val start = SystemClock.elapsedRealtime()
                first.cancelAndJoin()
                val latency = SystemClock.elapsedRealtime() - start
                check(latency < 1000) { "Native cancellation exceeded 1 second" }
                val end = NativeFt8Engine.workSnapshot()
                check(end.admitted == 0 && end.nativeCalls == 0) { "Native call leaked after cancellation" }
                cancellation.put(latency)
                check(engine.decode(testSlot).frames.size == 1)
            }
            report.put("cancel_to_release_ms", cancellation)
            val soak = JSONArray()
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < 45_000 && soak.length() < 500) {
                if (Build.VERSION.SDK_INT >= 29 && context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE)
                    error("Thermal status reached severe; sustained qualification stopped")
                val result = engine.decode(testSlot, Ft8DecodeProfile.STANDARD)
                check(result.frames.size == 1)
                soak.put(result.decodeMillis)
            }
            report.put("sustained_ms", SystemClock.elapsedRealtime() - start).put("sustained_decode_ms", soak)
                .put("thermal_after", thermal())
        } finally {
            sampler.cancelAndJoin()
        }
        report.put("sampled_max_pss_kib", sampledPss).put("sampled_max_native_bytes", sampledNative)
            .put("memory_samples", samples).put("memory_sample_interval_ms", 50)
        val summary = JSONArray()
        for (profile in Ft8DecodeProfile.entries) {
            val rows = report.getJSONArray("cases")
            val values = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.getString("profile") == profile.label }
            val times = values.map { it.getLong("decode_ms") }.sorted()
            fun percentile(p: Double) = times[(ceil(p * times.size).toInt() - 1).coerceAtLeast(0)]
            summary.put(JSONObject().put("profile", profile.label).put("cases", values.size)
                .put("detected", values.count { it.getBoolean("detected") }).put("false_frames", values.sumOf { it.getInt("false_frames") })
                .put("p50_ms", percentile(.5)).put("p95_ms", percentile(.95)).put("max_ms", times.last()))
        }
        report.put("profiles", summary).put("result", "PASS")
        File(context.filesDir, "r09-profile.json").writeText(report.toString(2))
        report
    }
}
