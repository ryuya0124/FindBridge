package dev.findbridge.verification

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the real R8 Activity and GMS picker, then cancels. No account permission is granted. */
@android.annotation.SuppressLint(
    "ResourceType"
) // Stable native View IDs in the separately built target APK.
class GmsNativePickerTest {
    @Test
    fun nativeAccountPickerReturnsWithoutOpeningBrowser() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gmsprobe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        val host =
            instrumentation.startActivitySync(
                Intent()
                    .setClassName(
                        instrumentation.targetContext.packageName,
                        "dev.findbridge.android.GoogleAuthActivity",
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        try {
            ui.adoptShellPermissionIdentity("android.permission.REORDER_TASKS")
            try {
                (host.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .moveTaskToFront(host.taskId, 0)
            } finally {
                ui.dropShellPermissionIdentity()
            }
            val focusUntil = SystemClock.uptimeMillis() + 5_000
            var focused = false
            while (!focused && SystemClock.uptimeMillis() < focusUntil) {
                instrumentation.runOnMainSync { focused = host.hasWindowFocus() }
                if (!focused) SystemClock.sleep(50)
            }
            assertTrue("The product activity must have focus before opening GMS", focused)
            instrumentation.runOnMainSync {
                host.findViewById<android.view.View>(1311).performClick()
            }
            val until = SystemClock.uptimeMillis() + 15_000
            var nativePicker = false
            while (!nativePicker && SystemClock.uptimeMillis() < until) {
                nativePicker =
                    ui.rootInActiveWindow?.packageName?.toString() == "com.google.android.gms"
                if (!nativePicker) SystemClock.sleep(100)
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply { putBoolean("nativeGmsPicker", nativePicker) },
            )
            assertTrue("The account picker must belong to Google Play services", nativePicker)
            fun containsWeb(node: android.view.accessibility.AccessibilityNodeInfo): Boolean =
                node.className?.toString()?.contains("WebView") == true ||
                    (0 until node.childCount).any { child ->
                        node.getChild(child)?.let(::containsWeb) == true
                    }
            assertTrue(
                "GMS account selection must use native Views",
                ui.rootInActiveWindow?.let { !containsWeb(it) } == true,
            )
            ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            var returned = false
            val returnUntil = SystemClock.uptimeMillis() + 10_000
            while (!returned && SystemClock.uptimeMillis() < returnUntil) {
                instrumentation.runOnMainSync {
                    returned =
                        host.findViewById<TextView>(1310)?.text?.contains("…") == false &&
                            host.findViewById<android.view.View>(1311) != null
                }
                if (!returned) SystemClock.sleep(100)
            }
            assertTrue("Cancel must restore the native connection UI", returned)
        } finally {
            instrumentation.runOnMainSync {
                if (!host.isFinishing && !host.isDestroyed) host.finish()
            }
        }
    }
}
