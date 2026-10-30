package dev.findbridge.verification

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Uses only manifest activity names and native view IDs so it also tests the actual R8 APK.
 * Requires the owner's existing encrypted login. Never reads or exports credentials.
 */
// The production form deliberately exposes stable numeric View IDs, not resource IDs.
// This independent driver cannot link the R8 target's generated R class.
@SuppressLint("ResourceType")
class ReleaseLoginLiveTest {
    @Test
    fun signedApkReconnectsAfterAWindowInterruptionUsingARealNativeTouch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseauth") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var authenticationOpened = false
        var interrupted = false
        val retry = InstrumentationRegistry.getArguments().getString("retry") == "true"
        var cancelledOnce = false
        var retried = false
        var openedCount = 0
        var overlay: AlertDialog? = null
        var auth: Activity? = null
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val lifecycle = ActivityLifecycleCallback { activity, stage ->
            if (
                activity.javaClass.name == "dev.findbridge.android.AuthActivity" &&
                    stage == Stage.RESUMED
            ) {
                authenticationOpened = true
                openedCount++
                if (retry && !cancelledOnce) {
                    cancelledOnce = true
                    activity.finish()
                    return@ActivityLifecycleCallback
                }
                auth = activity
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (
                    !interrupted &&
                        InstrumentationRegistry.getArguments().getString("interrupt") == "true"
                ) {
                    interrupted = true
                    overlay =
                        AlertDialog.Builder(activity)
                            .setTitle("Authentication interruption test")
                            .setMessage(
                                "The official sign-in will resume after this test window closes."
                            )
                            .setCancelable(false)
                            .show()
                    activity.window.decorView.postDelayed(
                        {
                            overlay?.dismiss()
                            overlay = null
                        },
                        10_000,
                    )
                }
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(lifecycle) }
        val login =
            instrumentation.startActivitySync(
                Intent()
                    .setClassName(context.packageName, "dev.findbridge.android.LoginActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        try {
            var restored = false
            val loadUntil = SystemClock.uptimeMillis() + 10000
            while (!restored && SystemClock.uptimeMillis() < loadUntil) {
                instrumentation.runOnMainSync {
                    login.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    restored = login.findViewById<EditText>(1101)?.text?.isNotEmpty() == true
                }
                SystemClock.sleep(100)
            }
            assertTrue("Owner-saved login must be available in the native form", restored)
            if (InstrumentationRegistry.getArguments().getString("keyboard") == "true") {
                instrumentation.runOnMainSync {
                    val input = login.findViewById<EditText>(1101)
                    input.requestFocus()
                    login
                        .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                        .showSoftInput(
                            input,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT,
                        )
                }
                var keyboardVisible = false
                val keyboardUntil = SystemClock.uptimeMillis() + 5000
                while (!keyboardVisible && SystemClock.uptimeMillis() < keyboardUntil) {
                    instrumentation.runOnMainSync {
                        keyboardVisible =
                            login.window.decorView.rootWindowInsets?.isVisible(
                                android.view.WindowInsets.Type.ime()
                            ) == true
                    }
                    SystemClock.sleep(100)
                }
                assertTrue(
                    "The real soft keyboard must open before the connect touch",
                    keyboardVisible,
                )
            }
            fun tapConnect() {
                val point = Rect()
                instrumentation.runOnMainSync {
                    assertTrue(
                        login.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE !=
                            0
                    )
                    assertTrue(login.findViewById<EditText>(1102).text.isEmpty())
                    val button = login.findViewById<View>(1103)
                    button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true)
                }
                SystemClock.sleep(300)
                instrumentation.runOnMainSync {
                    assertTrue(login.findViewById<View>(1103).getGlobalVisibleRect(point))
                }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event =
                        MotionEvent.obtain(
                            down,
                            SystemClock.uptimeMillis(),
                            action,
                            point.exactCenterX(),
                            point.exactCenterY(),
                            0,
                        )
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                    SystemClock.sleep(70)
                }
            }
            tapConnect()
            val until = SystemClock.uptimeMillis() + 120000
            var complete = false
            while (!complete && SystemClock.uptimeMillis() < until) {
                var retryReady = false
                instrumentation.runOnMainSync {
                    complete = login.isFinishing || login.isDestroyed
                    retryReady =
                        retry &&
                            cancelledOnce &&
                            !retried &&
                            !complete &&
                            login.hasWindowFocus() &&
                            login.findViewById<View>(1103).isEnabled
                }
                if (retryReady) {
                    retried = true
                    tapConnect()
                }
                SystemClock.sleep(250)
            }
            assertTrue("Official Apple authentication must open", authenticationOpened)
            assertTrue(
                "The signed APK must complete the native form after official authentication",
                complete,
            )
            if (retry)
                assertTrue(
                    "Cancelled authentication must be retryable with the saved blank-password form",
                    retried && openedCount >= 2,
                )
            InstrumentationRegistry.getArguments().getString("completedField")?.let { field ->
                instrumentation.runOnMainSync {
                    assertTrue(
                        "The production form must report successful authentication",
                        login.javaClass
                            .getDeclaredField(field)
                            .apply { isAccessible = true }
                            .getBoolean(login),
                    )
                }
            }
            println(
                "Signed APK: native touch, stored login, official authentication and focus restoration PASS"
            )
        } finally {
            instrumentation.runOnMainSync {
                monitor.removeLifecycleCallback(lifecycle)
                overlay?.dismiss()
                auth?.takeIf { !it.isFinishing && !it.isDestroyed }?.finish()
                if (!login.isFinishing && !login.isDestroyed) login.finish()
            }
        }
    }
}
