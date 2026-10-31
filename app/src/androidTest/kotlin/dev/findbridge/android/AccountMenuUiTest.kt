package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.findbridge.android.Ui.dp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AccountMenuUiTest {
    @Test
    fun glassAccountMenuDismissesAndKeepsActionsSeparateInBothAppearances() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(!AppleRepository.get(context).hasSession && !LoginVault(context).hasSaved)
        val settings = AppearanceSettings(context)
        val previous = settings.appearance
        val previousMotion = settings.reduceMotion
        try {
            settings.reduceMotion = true
            for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
                settings.appearance = appearance
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val drawn = CountDownLatch(1)
                    lateinit var anchor: View
                    var minimumHeight = 0
                    scenario.onActivity { activity ->
                        anchor =
                            descendants(activity.window.decorView).single {
                                it.contentDescription == activity.getString(R.string.account_menu)
                            }
                        minimumHeight = activity.dp(48)
                        activity.window.decorView.viewTreeObserver.registerFrameCommitCallback {
                            drawn.countDown()
                        }
                        activity.window.decorView.invalidate()
                    }
                    assertTrue(drawn.await(5, TimeUnit.SECONDS))
                    fun open(action: () -> Unit) {
                        instrumentation.uiAutomation.executeAndWaitForEvent(
                            { scenario.onActivity { action() } },
                            { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED },
                            5000,
                        )
                        instrumentation.uiAutomation.waitForIdle(100, 5000)
                    }
                    fun node(label: Int): AccessibilityNodeInfo =
                        instrumentation.uiAutomation.rootInActiveWindow
                            .findAccessibilityNodeInfosByText(context.getString(label))
                            .first { it.isClickable }

                    open { anchor.performClick() }
                    val signIn = node(R.string.sign_in)
                    assertTrue(Rect().also(signIn::getBoundsInScreen).height() >= minimumHeight)
                    assertEquals("android.widget.Button", signIn.className.toString())
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                    instrumentation.waitForIdleSync()

                    // An explicit connected fixture verifies both actions without using any
                    // account.
                    var connects = 0
                    var signOuts = 0
                    lateinit var menu: AccountMenu
                    scenario.onActivity { activity ->
                        val scene =
                            MainActivity::class
                                .java
                                .getDeclaredField("glass")
                                .apply { isAccessible = true }
                                .get(activity) as GlassScene
                        menu =
                            AccountMenu(activity, scene) {
                                Rect().also(anchor::getWindowVisibleDisplayFrame)
                            }
                    }
                    open { menu.show(anchor, true, { connects++ }, { signOuts++ }) }
                    assertNotNull(node(R.string.reconnect_account))
                    assertNotNull(node(R.string.sign_out))
                    if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(
                                    context.cacheDir,
                                    "account-menu-${appearance.name.lowercase()}.png",
                                )
                                .outputStream()
                                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    }
                    assertTrue(
                        node(R.string.sign_out).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    )
                    instrumentation.waitForIdleSync()
                    assertEquals(0, connects)
                    assertEquals(1, signOuts)
                    scenario.onActivity { menu.dismiss() }
                }
            }
        } finally {
            settings.appearance = previous
            settings.reduceMotion = previousMotion
            Ui.configure(context)
        }
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
}
