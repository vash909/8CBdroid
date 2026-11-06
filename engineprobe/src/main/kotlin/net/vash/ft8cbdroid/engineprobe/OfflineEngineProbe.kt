package net.vash.ft8cbdroid.engineprobe

import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.core.Ft8Payload77
import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.core.message.CbMessageCodec
import net.vash.ft8cbdroid.core.message.PackResult
import net.vash.ft8cbdroid.core.message.UnpackResult
import net.vash.ft8cbdroid.core.message.OfflineFt8Generator
import net.vash.ft8cbdroid.core.message.Ft8GenerationSettings
import net.vash.ft8cbdroid.core.message.EncodingMode
import net.vash.ft8cbdroid.core.message.WaveformGenerationResult
import net.vash.ft8cbdroid.core.recording.RecordedSlotWav
import net.vash.ft8cbdroid.core.recording.RecordedSlotContext
import net.vash.ft8cbdroid.core.recording.RecordedSlotDecoder
import net.vash.ft8cbdroid.dsp.NativeFt8Engine
import net.vash.ft8cbdroid.reference.HashSessionReferenceChecks
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs

/** Offline entry point: never opens the main app, audio devices or network. */
class OfflineEngineProbe : Instrumentation() {
    private var profileRequested = false
    override fun onCreate(arguments: Bundle?) {
        profileRequested = arguments?.getString("benchmark") == "true"
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val report = Bundle()
        try {
            runBlocking {
                val rows = context.assets.open("wsjtcb/messages.tsv").bufferedReader().useLines { lines ->
                    lines.filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
                }
                fun ascii(hex: String) = hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
                for (row in rows) {
                    val fields = row.split('\t')
                    val encodedMessage = CbMessageCodec.pack(ascii(fields[1]))
                    val decodedMessage = CbMessageCodec.unpack(Ft8Payload77.fromBits(fields[2]))
                    if (fields[3] == "1") {
                        check(encodedMessage is PackResult.Encoded && encodedMessage.payload.toBitString() == fields[2]) { "Message pack mismatch: ${fields[0]}" }
                        check(decodedMessage is UnpackResult.Decoded && decodedMessage.message.text == ascii(fields[4])) { "Message unpack mismatch: ${fields[0]}" }
                    } else {
                        check(encodedMessage is PackResult.Unsupported && decodedMessage is UnpackResult.Unsupported)
                    }
                }
                report.putInt("message_vectors", rows.size)
                val hashScenarios = HashSessionReferenceChecks.scenarios(
                    context.assets.open("wsjtcb/hash-sessions.tsv").bufferedReader().use { it.readLines() },
                )
                hashScenarios.values.forEach(HashSessionReferenceChecks::verify)
                report.putInt("hash_session_scenarios", hashScenarios.size)
                val engine = NativeFt8Engine()
                val generator = OfflineFt8Generator(engine)
                var generatedCount = 0
                for (text in listOf("CQ 1AT106", "<1AT106> 26AT101", "26AT101 <1AT106>", "26AT101 R-10")) {
                    val expected = CbMessageCodec.pack(text) as PackResult.Encoded
                    for ((frequency, level) in listOf(200f to .01f, 1000f to .4f, 2900f to 1f)) {
                        val result = generator.generate(text, EncodingMode.AUTOMATIC,
                            Ft8GenerationSettings(frequency, level), true) as WaveformGenerationResult.Generated
                        check(result.waveform.message.payload == expected.payload)
                        val wav = result.waveform.wavBytes()
                        check(wav.size == 360044)
                        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
                        check(header.getInt(24) == 12000 && header.getShort(22).toInt() == 1)
                        check(result.waveform.pcm.toFloatArray().maxOf { abs(it) } <= level + .00001f)
                        check(engine.decode(result.waveform.pcm).frames.map { it.payload } == listOf(expected.payload))
                        if (text == "CQ 1AT106" && frequency == 1000f) {
                            java.io.File(context.filesDir, "r07-generated.wav").writeBytes(wav)
                            report.putString("generated_wav_sha256", MessageDigest.getInstance("SHA-256")
                                .digest(wav).joinToString("") { "%02x".format(it) })
                        }
                        generatedCount++
                    }
                }
                report.putInt("generated_waveforms", generatedCount)
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
                val recordings = JSONObject(context.assets.open("recordings.json").bufferedReader().use { it.readText() })
                val recordingCases = recordings.getJSONArray("cases")
                val recordingDecoder = RecordedSlotDecoder(engine)
                val recordingContext = RecordedSlotContext.parse(recordings.getString("slot_start_utc"))
                val recordedTimings = mutableListOf<Long>()
                repeat(recordingCases.length()) { i ->
                    val case = recordingCases.getJSONObject(i)
                    val bytes = context.assets.open(case.getString("file").substringAfterLast('/')).use { it.readBytes() }
                    check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == case.getString("sha256"))
                    val slot = RecordedSlotWav.parse(bytes)
                    val begin = SystemClock.elapsedRealtime()
                    val result = recordingDecoder.decode(slot, recordingContext)
                    recordedTimings += SystemClock.elapsedRealtime() - begin
                    if (case.isNull("payload77")) {
                        check(result.decodes.isEmpty()) { "Corrupt/noise recording accepted" }
                    } else {
                        val row = result.decodes.single()
                        check(row.frame.payload.toBitString() == case.getString("payload77"))
                        check((row.message as UnpackResult.Decoded).message.text == case.getString("text"))
                        check(abs(row.frame.startSecondsInSlot - case.getDouble("onset_seconds")) <= .08001)
                        check(abs(row.frame.audioFrequencyHz - case.getDouble("frequency_hz")) <= 6.25)
                        if (case.getString("kind") == "awgn")
                            check(abs(requireNotNull(row.frame.estimatedSnrDb) - case.getDouble("expected_snr_db")) <= 3)
                    }
                }
                report.putInt("recorded_slot_cases", recordingCases.length())
                report.putString("recorded_slot_ms", recordedTimings.joinToString(","))
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
                if (profileRequested) {
                    val profile = DecoderProfileProbe(context).run(engine)
                    report.putString("profile_summary", profile.getJSONArray("profiles").toString())
                    report.putString("profile_cancellation_ms", profile.getJSONArray("cancel_to_release_ms").toString())
                    report.putLong("profile_sampled_max_pss_kib", profile.getLong("sampled_max_pss_kib"))
                    report.putString("profile_result", profile.getString("result"))
                    report.putString("profile_report_sha256", MessageDigest.getInstance("SHA-256")
                        .digest(java.io.File(context.filesDir, "r09-profile.json").readBytes()).joinToString("") { "%02x".format(it) })
                }
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
