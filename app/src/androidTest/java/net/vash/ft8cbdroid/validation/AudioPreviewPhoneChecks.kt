package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.message.WaveformViewModel
import net.vash.ft8cbdroid.profile.ProfilesViewModel
import net.vash.ft8cbdroid.core.message.EncodingMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Real AudioTrack/speaker/focus and shipped UI. External PC capture must acknowledge each full slot. */
class AudioPreviewPhoneChecks(private val instrumentation: Instrumentation, private val runId: String) {
    private val context get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(block) }
        return requireNotNull(value).getOrThrow()
    }
    private fun await(label: String, timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime()+timeout
        while (!condition()) { check(SystemClock.elapsedRealtime()<end) { "Timed out: $label" }; SystemClock.sleep(20) }
    }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    fun run() {
        var activity: MainActivity? = null; var vm: WaveformViewModel? = null
        val result = Bundle(); val report = JSONObject().put("run",runId); var code = Activity.RESULT_CANCELED
        try {
            require(runId.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "A unique preview_run argument is required." }
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            var current = instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity = current
            val model = main { ViewModelProvider(current)[WaveformViewModel::class.java] }; vm = model
            val profiles = main { current.receiveRuntime.profiles }
            await("profiles and outputs") { main { profiles.uiState.value.ready && model.uiState.value.outputs.isNotEmpty() } }
            check(main { !model.uiState.value.isPlaying && model.uiState.value.outputId == null })
            PhoneNavigation.open(instrumentation,"Message tools")
            main { model.generate("CQ 1AT106",EncodingMode.AUTOMATIC) }
            await("generated waveform",10000) { main { model.uiState.value.summary != null } }
            val output = main { model.uiState.value.outputs.single().id }
            main { model.selectOutput(output) }
            val slots = JSONArray()
            for (gain in listOf(100,50)) {
                main { model.previewGain(gain.toString()); model.previewRamp("20") }
                val stage = "gain-$gain"
                val ready = File(context.cacheDir,"r14-ready.tmp")
                ready.writeText(JSONObject().put("run",runId).put("stage",stage).toString())
                check(ready.renameTo(File(context.cacheDir,"r14-ready.json")))
                await("external recorder",30000) { File(context.cacheDir,"r14-go-$runId-$stage").isFile }
                PhoneNavigation.click(instrumentation,"Play speaker preview")
                await("playback start") { main { model.uiState.value.isPlaying } }
                await("complete playback",21000) { main { !model.uiState.value.isPlaying } }
                val state = main { model.uiState.value }
                check(state.error == null) { state.error!! }
                val played = requireNotNull(state.playback)
                check(played.clientRateHz==12000 && played.writtenFrames==180000 && played.playedFrames==180000L) { played.toString() }
                slots.put(JSONObject().put("gain_percent",gain).put("client_rate_hz",played.clientRateHz)
                    .put("frames_written",played.writtenFrames).put("frames_played",played.playedFrames).put("output",played.output))
            }
            File(context.cacheDir,"r14-ready.json").writeText(JSONObject().put("run",runId).put("stage","captures-complete").toString())
            report.put("full_slots",slots)
            fun start() {
                main { model.previewGain("100"); model.selectOutput(output); model.playPreview() }
                await("active output") { main { model.uiState.value.isPlaying && (model.uiState.value.playback?.playedFrames ?: 0)>12000 } }
            }
            fun stopped() {
                await("AudioTrack cleanup") { main { !model.uiState.value.isPlaying } }
                SystemClock.sleep(200)
                check(main { !model.uiState.value.isPlaying })
            }
            start(); PhoneNavigation.click(instrumentation,"Stop preview"); stopped()
            check(main { model.uiState.value.playback!!.playedFrames<180000 })
            report.put("explicit_stop",true)
            start(); PhoneNavigation.click(instrumentation,"Clear output selection"); stopped()
            check(main { model.uiState.value.outputId==null && !model.uiState.value.canPlay })
            report.put("output_change_stops_without_fallback",true)
            start(); main { model.changeInput("CQ 26AT101",EncodingMode.AUTOMATIC) }; stopped()
            check(main { model.uiState.value.summary==null })
            main { model.generate("CQ 1AT106",EncodingMode.AUTOMATIC) }
            await("regeneration") { main { model.uiState.value.summary!=null } }
            report.put("message_edit_invalidates_pcm",true)
            start()
            main { current.startActivity(Intent().setComponent(ComponentName("net.vash.ft8cbdroid.engineprobe",
                "net.vash.ft8cbdroid.engineprobe.PreviewFocusActivity"))) }
            stopped()
            check(main { model.uiState.value.error?.contains("focus",ignoreCase=true)==true }) { "Focus loss was not reported: ${model.uiState.value}" }
            SystemClock.sleep(1800); check(main { !model.uiState.value.isPlaying })
            report.put("separate_app_focus_loss_and_no_resume",true)
            start(); PhoneNavigation.click(instrumentation,"Operate"); stopped()
            report.put("navigation_stops",true)
            PhoneNavigation.open(instrumentation,"Message tools")
            start(); shell("timeout 4 input keyevent KEYCODE_HOME"); stopped()
            shell("timeout 5 am start --user 0 -W -f 0x30020000 -n net.vash.ft8cbdroid/.MainActivity")
            await("activity resumed") { main { current.hasWindowFocus() } }
            check(main { !model.uiState.value.isPlaying })
            report.put("background_no_resume",true)
            start()
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name,null,false)
            main { current.recreate() }
            current = requireNotNull(monitor.waitForActivityWithTimeout(6000)) as MainActivity
            instrumentation.removeMonitor(monitor); activity = current
            stopped(); check(main { ViewModelProvider(current)[WaveformViewModel::class.java]===model })
            report.put("rotation_stops_retained_session",true)
            start(); main { profiles.apply() }
            await("profile switch cleanup") { main { !profiles.uiState.value.busy && !model.uiState.value.isPlaying } }
            check(main { model.uiState.value.outputId==null })
            report.put("profile_apply_joins_output_cleanup",true)
            report.put("result","PASS").put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE)
            File(context.cacheDir,"r14-report.json").writeText(report.toString(2))
            result.putString("preview_result","PASS");result.putString("preview_report",report.toString());code=Activity.RESULT_OK
        } catch (error: Throwable) { result.putString("preview_failure","${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}") }
        finally { vm?.let { main { it.stopPreview() } }; activity?.let { main { it.finish() } } }
        instrumentation.finish(code,result)
    }
}
