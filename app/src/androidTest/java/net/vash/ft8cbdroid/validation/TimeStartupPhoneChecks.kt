package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.CapturePhase
import net.vash.ft8cbdroid.time.DataStoreTimePreferencesRepository
import net.vash.ft8cbdroid.time.timePreferences
import org.json.JSONObject

/** Fresh process, real preferred time backend, no Measure/Apply button and no audio start. */
class TimeStartupPhoneChecks(private val instrumentation: Instrumentation, private val run: String, private val gps: Boolean) {
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    fun run() {
        val context = instrumentation.targetContext
        val repository = DataStoreTimePreferencesRepository(context.timePreferences)
        val original = runBlocking { repository.settings.first() }
        val report = JSONObject().put("run", run).put("preferred_source", if (gps) "GPS" else "NTP")
        val result = Bundle(); var code = Activity.RESULT_CANCELED; var activity: MainActivity? = null
        try {
            require(run.matches(Regex("[A-Za-z0-9-]{1,64}")))
            runBlocking { repository.save(original.copy(preferGps = gps)) }
            val current = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = current
            val runtime = main { current.receiveRuntime }
            val until = SystemClock.elapsedRealtime() + 115000
            while (!main { runCatching { runtime.time.clock.snapshot() }.isSuccess && runtime.profiles.uiState.value.ready }) {
                check(SystemClock.elapsedRealtime() < until) { "Automatic startup did not obtain time: ${main { runtime.time.uiState.value }}" }
                SystemClock.sleep(100)
            }
            val mapping = main { runtime.time.clock.snapshot() }
            check(if (gps) mapping.source == "GPS/GNSS" else mapping.source.startsWith("NTP:")) { "Preferred backend did not succeed: ${mapping.source}" }
            check(main { runtime.time.uiState.value.refreshWarning == null && !runtime.session.uiState.value.active &&
                !runtime.manual.uiState.value.hasTxOperation && runtime.capture.uiState.value.phase == CapturePhase.IDLE })
            check(runBlocking { repository.settings.first() }.preferGps == gps)
            PhoneNavigation.open(instrumentation, "Time synchronization")
            main { current.recreate() }; SystemClock.sleep(800)
            check(main { runtime.time.clock.revision == mapping.revision && !runtime.manual.uiState.value.hasTxOperation })
            report.put("automatic_real_time_without_measure_or_apply", true)
                .put("preference_persistence_recreation_and_no_audio_start", true)
                .put("source", mapping.source).put("uncertainty_ms", mapping.uncertaintyMillis)
                .put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE).put("result", "PASS")
            result.putString("time_startup_result", "PASS"); code = Activity.RESULT_OK
        } catch (error: Throwable) { report.put("result", "FAIL").put("error", "${error.javaClass.simpleName}: ${error.message}"); error.printStackTrace() }
        finally {
            activity?.let { current -> main { current.receiveRuntime.time.cancel(); current.receiveRuntime.stop(); current.finish() } }
            runBlocking { repository.save(original) }
            File(context.cacheDir, "time-startup-report.json").writeText(report.toString(2))
        }
        instrumentation.finish(code, result)
    }
}
