package net.vash.ft8cbdroid.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.vash.ft8cbdroid.core.Ft8DecodeProfile
import net.vash.ft8cbdroid.core.diagnostics.*
import org.json.JSONArray
import org.json.JSONObject

/** One atomic, size-bounded, app-private checkpoint. No permission, analytics service or external upload. */
class AndroidDiagnosticsArchive(private val context: Context) : DiagnosticsArchive {
    private val file = AtomicFile(File(context.noBackupFilesDir, "session-diagnostics.json"))
    override suspend fun read(): DiagnosticReport? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
        val bytes = file.openRead().use { stream ->
            val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (out.size() <= MAX_BYTES) {
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        require(bytes.size <= MAX_BYTES) { "Diagnostic report exceeds its limit" }
        decode(JSONObject(bytes.toString(Charsets.UTF_8)))
    }
    override suspend fun write(report: DiagnosticReport) = withContext(Dispatchers.IO) {
        val bytes = encode(report).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override suspend fun delete() = withContext(Dispatchers.IO) {
        file.delete(); check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
    }
    override suspend fun export(document: String, report: DiagnosticReport) = withContext(Dispatchers.IO) {
        val bytes = encode(report).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        val stream = context.contentResolver.openOutputStream(Uri.parse(document), "wt") ?: error("No output stream")
        stream.use { it.write(bytes) }
    }
    private fun encode(report: DiagnosticReport) = JSONObject().apply {
        put("schema", 1); put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        put("deviceModel", Build.MODEL); put("androidRelease", Build.VERSION.RELEASE); put("androidApi", Build.VERSION.SDK_INT)
        put("durationLimitMillis", SessionDiagnostics.MAX_DURATION_MILLIS)
        put("sampleIntervalMillis", SessionDiagnostics.SAMPLE_INTERVAL_MILLIS)
        put("elapsedMillis", report.elapsedMillis); put("end", report.end.name)
        put("events", JSONObject().apply { report.events.forEach { (event, count) -> put(event.name, count) } })
        put("decodeCount", report.decodeCount); put("decodeTotalMillis", report.decodeTotalMillis)
        put("decodeMaxMillis", report.decodeMaxMillis ?: JSONObject.NULL)
        put("samples", JSONArray().apply { report.samples.forEach { sample -> put(JSONObject().apply {
            val o = sample.observation; val r = sample.resources
            put("elapsedMillis", sample.elapsedMillis); put("source", o.source.name); put("phase", o.phase.name)
            put("decoder", o.decoder.name); put("inputRun", o.inputRun ?: JSONObject.NULL); put("inputRate", o.inputRate ?: JSONObject.NULL)
            put("clippedSamples", o.clippedSamples ?: JSONObject.NULL); put("driftPpm", o.driftPpm ?: JSONObject.NULL)
            put("retainedLanRows", o.retainedLanRows); put("timeRevision", o.timeRevision ?: JSONObject.NULL)
            put("timeUsable", o.timeUsable); put("timeUncertaintyMillis", o.timeUncertaintyMillis ?: JSONObject.NULL)
            put("txActive", o.txActive); put("pssKiB", r.pssKiB ?: JSONObject.NULL)
            put("processCpuMillis", r.processCpuMillis ?: JSONObject.NULL); put("batteryPercent", r.batteryPercent ?: JSONObject.NULL)
            put("batteryTemperatureC", r.batteryTemperatureC ?: JSONObject.NULL); put("plugged", r.plugged ?: JSONObject.NULL)
            put("thermalStatus", r.thermalStatus ?: JSONObject.NULL)
        }) } })
    }
    private fun decode(json: JSONObject): DiagnosticReport {
        require(json.getInt("schema") == 1)
        val rows = json.getJSONArray("samples"); require(rows.length() <= SessionDiagnostics.MAX_SAMPLES)
        fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
        fun JSONObject.intOrNull(key: String) = if (isNull(key)) null else getInt(key)
        fun JSONObject.doubleOrNull(key: String) = if (isNull(key)) null else getDouble(key).also { require(it.isFinite()) }
        return DiagnosticReport(json.getLong("elapsedMillis").also { require(it in 0..SessionDiagnostics.MAX_DURATION_MILLIS) },
            DiagnosticEnd.valueOf(json.getString("end")), ReceiveDiagnosticEvent.entries.mapNotNull { event ->
                json.getJSONObject("events").longOrNull(event.name)?.let { event to it.also { count -> require(count >= 0) } }
            }.toMap(), json.getLong("decodeCount"), json.getLong("decodeTotalMillis"), json.longOrNull("decodeMaxMillis"),
            (0 until rows.length()).map { index ->
                val s = rows.getJSONObject(index)
                DiagnosticSample(s.getLong("elapsedMillis"), DiagnosticObservation(
                    DiagnosticSource.valueOf(s.getString("source")), DiagnosticPhase.valueOf(s.getString("phase")),
                    Ft8DecodeProfile.valueOf(s.getString("decoder")), s.longOrNull("inputRun"), s.intOrNull("inputRate"), s.longOrNull("clippedSamples"),
                    s.doubleOrNull("driftPpm"), s.getInt("retainedLanRows"), s.longOrNull("timeRevision"), s.getBoolean("timeUsable"),
                    s.doubleOrNull("timeUncertaintyMillis"), s.getBoolean("txActive")),
                    DiagnosticResources(s.longOrNull("pssKiB"), s.longOrNull("processCpuMillis"), s.intOrNull("batteryPercent"),
                        s.doubleOrNull("batteryTemperatureC"), if (s.isNull("plugged")) null else s.getBoolean("plugged"), s.intOrNull("thermalStatus")))
            })
    }
    companion object { const val MAX_BYTES = 256 * 1024 }
}

class AndroidDiagnosticResources(private val context: Context) {
    suspend fun read(): DiagnosticResources = withContext(Dispatchers.IO) {
        val battery = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        fun extra(key: String) = battery?.takeIf { it.hasExtra(key) }?.getIntExtra(key, -1)?.takeIf { it >= 0 }
        val level = extra(BatteryManager.EXTRA_LEVEL); val scale = extra(BatteryManager.EXTRA_SCALE)
        DiagnosticResources(
            pssKiB = runCatching { Debug.getPss() }.getOrNull()?.takeIf { it >= 0 },
            processCpuMillis = Process.getElapsedCpuTime(),
            batteryPercent = if (level != null && scale != null && scale > 0) (100L * level / scale).toInt().coerceIn(0, 100) else null,
            batteryTemperatureC = extra(BatteryManager.EXTRA_TEMPERATURE)?.div(10.0),
            plugged = extra(BatteryManager.EXTRA_PLUGGED)?.let { it != 0 },
            thermalStatus = if (Build.VERSION.SDK_INT >= 29) runCatching { context.getSystemService(PowerManager::class.java).currentThermalStatus }.getOrNull() else null,
        )
    }
}
