package net.vash.ft8cbdroid.engineprobe

import android.app.Activity
import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.hamlib.NativeHamlibCatalog
import org.json.JSONObject
import java.io.File

/** Software-only catalog/JNI check. Does not request USB permission or open a radio. */
class HamlibProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        var code = Activity.RESULT_CANCELED
        try {
            val started = SystemClock.elapsedRealtime()
            runBlocking {
                val backend = NativeHamlibCatalog()
                val catalog = backend.load()
                check(catalog.version.contains("4.7.2"))
                check(catalog.models.size >= 100)
                val dummy = catalog.models.single { it.isDummy }
                check(dummy.readsFrequency && dummy.readsMode)
                for (manufacturer in listOf("Icom", "Yaesu", "Kenwood"))
                    check(catalog.models.any { it.manufacturer == manufacturer })
                repeat(20) { check(backend.checkDummy().passed) }
                repeat(3) { check(backend.load() == catalog) }
                val report = JSONObject().put("result", "PASS").put("version", catalog.version)
                    .put("catalog_entries", catalog.models.size).put("dummy_cycles", 20)
                    .put("catalog_reloads", 3).put("hardware_tested", false)
                    .put("model", Build.MODEL).put("android", Build.VERSION.RELEASE)
                    .put("abi", Build.SUPPORTED_ABIS.first()).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                File(context.cacheDir, "hamlib-probe.json").writeText(report.toString(2))
                output.putString("hamlib_report", report.toString())
                output.putString("hamlib_result", "PASS")
                code = Activity.RESULT_OK
            }
        } catch (error: Exception) {
            output.putString("hamlib_failure", "${error.javaClass.simpleName}: ${error.message}")
        } catch (error: LinkageError) {
            output.putString("hamlib_failure", "${error.javaClass.simpleName}: ${error.message}")
        }
        finish(code, output)
    }
}
