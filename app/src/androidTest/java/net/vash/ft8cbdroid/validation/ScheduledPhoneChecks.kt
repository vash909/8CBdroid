package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.audio.AndroidAudioPreview
import net.vash.ft8cbdroid.core.audio.*
import net.vash.ft8cbdroid.core.message.EncodingMode
import net.vash.ft8cbdroid.core.Ft8PcmSlot
import net.vash.ft8cbdroid.message.WaveformViewModel
import net.vash.ft8cbdroid.profile.ProfilesViewModel
import net.vash.ft8cbdroid.time.*
import net.vash.ft8cbdroid.ntp.UdpNtpClient
import org.json.JSONArray
import org.json.JSONObject

/** Real synchronized speaker output. The PC records against its independently synchronized clock. */
class ScheduledPhoneChecks(private val instrumentation: Instrumentation, private val runId: String, private val ntpHost: String, private val ntpPort: Int) {
    private val context get()=instrumentation.targetContext
    private fun <T> main(block: ()->T): T {
        var result: Result<T>?=null;instrumentation.runOnMainSync { result=runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(label: String, timeout: Long=6000, condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(!condition()) { check(SystemClock.elapsedRealtime()<end) { "Timed out: $label" };SystemClock.sleep(20) }
    }
    fun run() {
        val result=Bundle();val report=JSONObject().put("run",runId);var code=Activity.RESULT_CANCELED
        var activity: MainActivity?=null;var vm: WaveformViewModel?=null
        try {
            require(runId.matches(Regex("[A-Za-z0-9-]{1,64}")))
            instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            var current=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity=current
            val model=main { ViewModelProvider(current)[WaveformViewModel::class.java] };vm=model
            val time=main { ViewModelProvider(current)[TimeViewModel::class.java] }
            val profiles=main { ViewModelProvider(current)[ProfilesViewModel::class.java] }
            await("initial state") { main { profiles.uiState.value.ready && model.uiState.value.outputs.isNotEmpty() && !time.uiState.value.loading } }
            PhoneNavigation.open(instrumentation,"Message tools")
            main { model.generate("CQ 1AT106",EncodingMode.AUTOMATIC) }
            await("waveform") { main { model.uiState.value.summary!=null } }
            val output=main { model.uiState.value.outputs.single().id }
            main { model.selectOutput(output) }
            PhoneNavigation.click(instrumentation,"Measure speaker latency")
            await("calibration completion",10000) { main { !model.uiState.value.isPlaying } }
            val latency=main { requireNotNull(model.uiState.value.latency) { "Calibration: ${model.uiState.value.error}" } }
            report.put("latency_ms",latency.nanos/1e6).put("latency_spread_ms",latency.spreadNanos/1e6)
            main { model.queueSlot() }
            check(main { !model.uiState.value.isPlaying && model.uiState.value.error?.contains("Synchronize")==true })
            report.put("unsynchronized_queue_rejected",true)
            val measurement=runBlocking { UdpNtpClient(AndroidTimeSource,ntpPort).measure(ntpHost) }
            main { time.clock.apply(measurement) }
            report.put("ntp_source",measurement.source).put("ntp_offset_ms",measurement.offsetMillis())
                .put("ntp_uncertainty_ms",measurement.uncertaintyMillis).put("ntp_rtt_ms",measurement.roundTripMillis)
            File(context.cacheDir,"r15-ready.json").writeText(JSONObject().put("run",runId).toString())
            await("PC recorder",30000) { File(context.cacheDir,"r15-go-$runId").exists() }
            val slots=JSONArray()
            for(period in SlotPeriod.entries) {
                PhoneNavigation.click(instrumentation,if(period==SlotPeriod.EVEN) "Even · 00/30" else "Odd · 15/45")
                PhoneNavigation.click(instrumentation,"Queue one UTC slot")
                await("queue accepted") { main { model.uiState.value.isPlaying } }
                val utc=main { requireNotNull(model.uiState.value.queuedUtcMillis) }
                await("complete $period slot",52000) { main { !model.uiState.value.isPlaying } }
                val state=main { model.uiState.value }
                check(state.error==null) { "Scheduled output: ${state.error}" }
                val played=requireNotNull(state.playback)
                check(played.playedFrames==180000L && played.writtenFrames==180000 && played.clientRateHz==12000)
                check(played.firstFrameErrorMillis!=null && kotlin.math.abs(played.firstFrameErrorMillis!!)<=75)
                slots.put(JSONObject().put("period",period.name).put("utc_millis",utc)
                    .put("first_frame_error_ms",played.firstFrameErrorMillis).put("frames",played.playedFrames))
            }
            report.put("slots",slots)
            File(context.cacheDir,"r15-captures-complete.json").writeText(JSONObject().put("run",runId).toString())
            fun queue() {
                main { model.selectOutput(output);model.queueSlot() }
                check(main { model.uiState.value.isPlaying }) { "Queue failed: ${model.uiState.value.error}" }
            }
            fun stopped() { await("output cleanup") { main { !model.uiState.value.isPlaying } } }
            queue();main { model.stopPreview() };stopped();report.put("stop_waiting",true)
            queue();main { model.changeInput("CQ 26AT101",EncodingMode.AUTOMATIC) };stopped()
            check(main { model.uiState.value.summary==null })
            main { model.generate("CQ 1AT106",EncodingMode.AUTOMATIC) };await("regenerated") { main { model.uiState.value.summary!=null } }
            report.put("edit_cancels_snapshot",true)
            queue();main { time.clock.reset() };stopped()
            check(main { model.uiState.value.error?.contains("Time correction")==true })
            main { time.clock.apply(measurement) };report.put("correction_change_cancels_without_retargeting",true)
            queue();main { model.period(if(model.uiState.value.period==SlotPeriod.EVEN) SlotPeriod.ODD else SlotPeriod.EVEN) };stopped()
            report.put("period_change_cancels",true)
            queue();main { current.startActivity(Intent().setComponent(ComponentName("net.vash.ft8cbdroid.engineprobe",
                "net.vash.ft8cbdroid.engineprobe.PreviewFocusActivity"))) };stopped()
            check(main { model.uiState.value.error?.contains("focus",true)==true });SystemClock.sleep(1800)
            check(main { !model.uiState.value.isPlaying });report.put("focus_loss_while_waiting_no_resume",true)
            queue();PhoneNavigation.click(instrumentation,"Operate");stopped()
            PhoneNavigation.open(instrumentation,"Message tools");check(main { !model.uiState.value.isPlaying })
            report.put("navigation_cancels_wait",true)
            queue();val monitor=instrumentation.addMonitor(MainActivity::class.java.name,null,false)
            main { current.recreate() };current=requireNotNull(monitor.waitForActivityWithTimeout(6000)) as MainActivity
            instrumentation.removeMonitor(monitor);activity=current;stopped()
            check(main { ViewModelProvider(current)[WaveformViewModel::class.java]===model })
            report.put("recreation_cancels_wait",true)
            queue();main { profiles.apply() };stopped()
            await("profile apply") { main { !profiles.uiState.value.busy } }
            check(main { model.uiState.value.latency==null && model.uiState.value.outputId==null })
            report.put("profile_clears_calibration_and_queue",true)
            // Exercise actual Android output with an intentionally expired command deadline.
            main { time.clock.apply(measurement) }
            val mapping=time.clock.snapshot();val first=SystemClock.elapsedRealtimeNanos()-1_000_000_000
            val late=ScheduledSlot(mapping.at(first)/1_000_000,first,first-latency.nanos,mapping,latency)
            val player=AndroidAudioPreview(context)
            try {
                val error=runCatching { runBlocking { player.playScheduled(Ft8PcmSlot.fromSamples(FloatArray(180000)),output,PreviewSettings(),late,time.clock) {} } }.exceptionOrNull()
                check(error?.message?.contains("deadline")==true) { "Expired deadline result: $error" }
            } finally { player.close() }
            report.put("actual_backend_rejects_expired_deadline",true)
            report.put("result","PASS").put("model",android.os.Build.MODEL).put("android",android.os.Build.VERSION.RELEASE)
            File(context.cacheDir,"r15-report.json").writeText(report.toString(2))
            result.putString("scheduled_result","PASS");result.putString("scheduled_report",report.toString());code=Activity.RESULT_OK
        } catch(error: Throwable) {
            result.putString("scheduled_failure","${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}; state=${vm?.uiState?.value}")
        } finally { vm?.let { main { it.stopPreview() } };activity?.let { main { it.finish() } } }
        instrumentation.finish(code,result)
    }
}
