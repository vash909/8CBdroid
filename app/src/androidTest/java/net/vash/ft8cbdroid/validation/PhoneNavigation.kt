package net.vash.ft8cbdroid.validation

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Navigate the shipped UI rather than changing route state from the test. */
object PhoneNavigation {
    fun root(instrumentation: Instrumentation): AccessibilityNodeInfo? {
        val ui = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) ui.clearCache() else ui.serviceInfo = ui.serviceInfo
        return ui.rootInActiveWindow?.also { it.refresh() }
    }
    private fun clickable(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text || node.contentDescription?.toString() == text) {
            var parent: AccessibilityNodeInfo? = node
            while (parent != null && !parent.isScrollable) {
                if (parent.isClickable) return parent
                parent = parent.parent
            }
        }
        repeat(node.childCount) { clickable(node.getChild(it), text)?.let { return it } }
        return null
    }
    fun click(instrumentation: Instrumentation, text: String) {
        fun scroller(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            repeat(node.childCount) { scroller(node.getChild(it))?.let { return it } }; return null
        }
        // Page-sized accessibility scrolling avoids repeated swipes into nested message lists.
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            for (index in 0 until 8) {
                val root = root(instrumentation) ?: break
                val node = clickable(root, text); val bounds = Rect(); node?.getBoundsInScreen(bounds)
                val screen = Rect(); root.getBoundsInScreen(screen)
                if (node?.isVisibleToUser == true && screen.contains(bounds) && bounds.width() > 0 && bounds.height() > 0) {
                    check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $text" }
                    SystemClock.sleep(180); return
                }
                if (scroller(root)?.performAction(action) != true) break
                SystemClock.sleep(220)
            }
        }
        for (forward in listOf(true, false)) repeat(16) {
            val root = root(instrumentation)
            if (root == null) { SystemClock.sleep(100); return@repeat }
            val node = clickable(root, text); val bounds = Rect(); node?.getBoundsInScreen(bounds)
            val screen = Rect(); root.getBoundsInScreen(screen)
            if (node?.isVisibleToUser == true && bounds.width() > 0 && bounds.height() > 0 && screen.contains(bounds)) {
                check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $text" }
                SystemClock.sleep(180); return
            }
            val y = screen.centerY(); val end = y + if (forward) -screen.height()/5 else screen.height()/5
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "timeout 4 input swipe ${screen.centerX()} $y ${screen.centerX()} $end 600")).use { it.readBytes() }
            SystemClock.sleep(160)
        }
        error("Visible control not found: $text")
    }
    fun open(instrumentation: Instrumentation, page: String) {
        // Profile loading can finish before the first Compose navigation frame is drawn.
        val until = SystemClock.elapsedRealtime() + 8000
        while (true) {
            val current = root(instrumentation)
            if (current?.packageName?.toString() == instrumentation.targetContext.packageName &&
                (clickable(current, "Settings") != null || clickable(current, "Operate") != null)) break
            check(SystemClock.elapsedRealtime() < until) { "App navigation did not become ready" }
            SystemClock.sleep(50)
        }
        // Compose does not expose accessibility clicks for an already selected tab.
        if (clickable(root(instrumentation), "Settings") == null) click(instrumentation, "Operate")
        click(instrumentation, "Settings")
        click(instrumentation, page)
    }
    fun replace(instrumentation: Instrumentation, label: String, value: String) {
        click(instrumentation, label)
        fun focused(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable && node.isFocused) return node
            repeat(node.childCount) { focused(node.getChild(it))?.let { return it } }
            return null
        }
        val until = SystemClock.elapsedRealtime() + 4000
        var field = focused(root(instrumentation))
        while (field == null && SystemClock.elapsedRealtime() < until) { SystemClock.sleep(50); field = focused(root(instrumentation)) }
        check(requireNotNull(field) { "Focused editor missing: $label" }.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })) { "Cannot edit $label" }
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("timeout 4 input keyevent KEYCODE_BACK")).use { it.readBytes() }
        SystemClock.sleep(180)
    }
}
