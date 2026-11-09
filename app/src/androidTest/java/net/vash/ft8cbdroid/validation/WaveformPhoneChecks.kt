package net.vash.ft8cbdroid.validation

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import net.vash.ft8cbdroid.MainActivity
import net.vash.ft8cbdroid.core.message.EncodingMode
import net.vash.ft8cbdroid.dsp.NativeFt8Engine
import net.vash.ft8cbdroid.message.WaveformViewModel
import org.json.JSONObject

/** Finite on-device checks of the real Activity, ViewModel, JNI encoder and ContentResolver exporter.
 * Default export checks do not use audio or change saved settings. The explicit preview scenario uses the speaker and applies the current profile.
 */
class WaveformPhoneChecks : Instrumentation() {
    private var scenario: String? = null
    private var previewRun: String = ""
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(description: String, timeout: Long = 4000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $description" }
            SystemClock.sleep(10)
        }
    }
    private fun root(): AccessibilityNodeInfo? {
        // Samsung's virtual tree can remain cached after scrolling without fresh events.
        if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
        else uiAutomation.serviceInfo = uiAutomation.serviceInfo
        return uiAutomation.rootInActiveWindow?.also { it.refresh() }
    }
    // Compose exposes virtual descendants through traversal; a provider's text-search API
    // need not return those descendants. Navigation labels can also have empty bounds.
    private fun findNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        repeat(root.childCount) { findNode(root.getChild(it), predicate)?.let { return it } }
        return null
    }
    private fun node(text: String): AccessibilityNodeInfo? = findNode(root()) {
        it.text?.toString() == text
    }
    private fun button(text: String): AccessibilityNodeInfo? {
        var current = node(text)
        while (current != null) {
            if (current.isClickable || current.className == "android.widget.Button") return current
            current = current.parent
        }
        return null
    }
    private fun scrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null || root.isScrollable) return root
        repeat(root.childCount) { scrollable(root.getChild(it))?.let { return it } }
        return null
    }
    private fun show(text: String) {
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            repeat(8) {
                if (node(text) != null) return
                swipe(direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }
        check(node(text) != null) { "UI text is not visible: $text" }
    }
    private fun swipe(forward: Boolean, fraction: Float = 0.55f) {
        val bounds = Rect()
        requireNotNull(scrollable(root())).getBoundsInScreen(bounds)
        val start = bounds.top + bounds.height() * if (forward) 0.8f else 0.2f
        val end = start + bounds.height() * fraction * if (forward) -1 else 1
        // Use Android's input command, which selects a consistent virtual touch device.
        // Samsung can reject mixed-device MotionEvent streams injected individually.
        val x = bounds.centerX()
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
            "timeout 3 input touchscreen swipe $x ${start.toInt()} $x ${end.toInt()} 250"
        )).use { check(it.readBytes().isEmpty()) { "Touch command failed" } }
        SystemClock.sleep(150)
    }
    private fun click(text: String) {
        show(text)
        check(requireNotNull(button(text)).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click: $text" }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); scenario = arguments?.getString("scenario"); previewRun = arguments?.getString("preview_run") ?: ""; start() }
    override fun onStart() {
        scenario?.let {
            if (it == "scheduled") { ScheduledPhoneChecks(this, previewRun).run(); return }
            if (it == "preview") { AudioPreviewPhoneChecks(this, previewRun).run(); return }
            if (it == "spectrum") { SpectrumPhoneChecks(this).run(); return }
            if (it == "live" || it == "operate-live") { LiveReceivePhoneChecks(this, operate = it == "operate-live").run(); return }
            require(it == "ntp-audio" || it == "gps") { "Unknown time/capture scenario" }
            UtcPhoneChecks(this).run(it)
            return
        }
        val result = Bundle()
        val report = JSONObject()
        var activity: MainActivity? = null
        var model: WaveformViewModel? = null
        var code = Activity.RESULT_CANCELED
        try {
            uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val vm = main { ViewModelProvider(activity)[WaveformViewModel::class.java] }
            model = vm
            PhoneNavigation.open(this, "Message tools")
            await("Messages screen") { node("Message preview") != null }
            click("Generate offline WAV")
            await("first native waveform") { main { vm.uiState.value.canSave } }
            val success = Uri.parse("content://net.vash.ft8cbdroid.test.r07/success")
            val failure = Uri.parse("content://net.vash.ft8cbdroid.test.r07/fail")
            main { check(vm.prepareExport()); vm.export(failure.toString()) }
            await("provider failure") { main { vm.uiState.value.error != null && !vm.uiState.value.isSaving } }
            val error = "Could not save the WAV. Choose a destination and retry."
            show(error)
            check(main { vm.uiState.value.error == error && vm.uiState.value.canSave })
            check(requireNotNull(targetContext.contentResolver.call(failure, "stats", null, null)).getInt("failures") > 0)
            report.put("real_provider_failure_visible", true)
            main { check(vm.prepareExport()); vm.export(success.toString()) }
            await("save retry") { main { vm.uiState.value.notice == "WAV saved." && !vm.uiState.value.isSaving } }
            show("WAV saved.")
            val wav = targetContext.contentResolver.openInputStream(success)!!.use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) }
            check(wav.size == 360044 && hash == "2aca24c402918763725ca19547197ee79b78a7043b3afd12e11ac79fb38d5618")
            report.put("save_retry_native_wav_sha256", hash)

            // Hold Default's workers without CPU spinning, so the actual Cancel button can be
            // exercised deterministically before a 38 ms encode finishes. Every wait is bounded.
            main { vm.changeFrequency("1250"); vm.changeFrequency("1000") }
            show("Generate offline WAV")
            val generateBounds = Rect()
            requireNotNull(button("Generate offline WAV")).getBoundsInScreen(generateBounds)
            if (generateBounds.centerY() > targetContext.resources.displayMetrics.heightPixels * 0.65f) swipe(true, 0.25f)
            val count = Runtime.getRuntime().availableProcessors().coerceIn(2, 16)
            val ready = CountDownLatch(count)
            val release = CountDownLatch(1)
            val blockers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                repeat(count) { blockers.launch { ready.countDown(); release.await(5, TimeUnit.SECONDS) } }
                check(ready.await(3, TimeUnit.SECONDS)) { "Could not hold dispatcher for cancellation test" }
                click("Generate offline WAV")
                await("Cancel generation button", 2000) { button("Cancel generation") != null }
                click("Cancel generation")
                await("cancel notice", 1000) { main { vm.uiState.value.notice == "Generation cancelled." } }
                check(main { !vm.uiState.value.canSave && vm.uiState.value.summary == null })
            } finally { release.countDown(); blockers.cancel() }
            await("no retained native work") { NativeFt8Engine.workSnapshot().admitted == 0 }
            SystemClock.sleep(100)
            check(main { vm.uiState.value.summary == null })
            report.put("cancel_button_discards_queued_generation", true)

            var cancelledActive = false
            repeat(3) {
                if (!cancelledActive) {
                    main { vm.generate("CQ 1AT106", EncodingMode.AUTOMATIC) }
                    val until = SystemClock.elapsedRealtime() + 1000
                    while (SystemClock.elapsedRealtime() < until && NativeFt8Engine.workSnapshot().stage != 5 &&
                        main { vm.uiState.value.isGenerating }) SystemClock.sleep(1)
                    cancelledActive = main {
                        if (NativeFt8Engine.workSnapshot().stage == 5) { vm.cancel(); true } else false
                    }
                    await("native cleanup") { NativeFt8Engine.workSnapshot().admitted == 0 }
                }
            }
            check(cancelledActive) { "Did not observe cancellation during native encoding" }
            check(main { vm.uiState.value.notice == "Generation cancelled." && !vm.uiState.value.canSave })
            report.put("active_native_encode_cancelled", true)
            click("Generate offline WAV")
            await("generate after cancellation") { main { vm.uiState.value.canSave } }
            report.put("generation_retry_after_cancel", true)

            show("Message")
            val field = requireNotNull(findNode(root()) {
                it.isEditable && it.text?.toString() == "CQ 1AT106"
            })
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "<...> 26AT101")
            }))
            val unsupported = "An unresolved callsign cannot be encoded for TX."
            await("unresolved-input rejection") { node(unsupported) != null }
            show("Generate offline WAV")
            check(button("Generate offline WAV")?.isEnabled == false)
            check(main { !vm.uiState.value.canSave && vm.uiState.value.summary == null })
            report.put("unresolved_input_rejected_in_ui", true)
            report.put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
                .put("result", "PASS")
            File(targetContext.cacheDir, "r07-waveform-checks.json").writeText(report.toString(2))
            result.putString("waveform_checks", report.toString())
            result.putString("waveform_result", "PASS")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("waveform_failure", "${error.javaClass.simpleName}: ${error.message}; ${error.stackTrace.firstOrNull()}")
            runCatching {
                val nodes = StringBuilder()
                fun record(node: AccessibilityNodeInfo?, depth: Int = 0) {
                    if (node == null || depth > 30) return
                    nodes.appendLine("${" ".repeat(depth)}${node.className}: ${node.text} clickable=${node.isClickable} scrollable=${node.isScrollable}")
                    repeat(node.childCount) { record(node.getChild(it), depth + 1) }
                }
                record(root())
                File(targetContext.cacheDir, "r07-failure-tree.txt").writeText(nodes.toString())
            }
        } finally {
            model?.let { vm -> runCatching { main { vm.cancel() } } }
            activity?.let { runCatching { main { it.finish() } } }
        }
        finish(code, result)
    }
}
