package net.vash.ft8cbdroid.validation

import android.app.Instrumentation
import android.graphics.Rect
import android.os.Build
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
        if (node.text?.toString() == text) {
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
        for (forward in listOf(true, false)) repeat(16) {
            val root = requireNotNull(root(instrumentation))
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
        click(instrumentation, "Settings")
        click(instrumentation, page)
    }
}
