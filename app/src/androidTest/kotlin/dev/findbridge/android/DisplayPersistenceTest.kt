package dev.findbridge.android

import android.app.LocaleManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

class DisplayPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun choiceDismissalAndThemeAndLanguageChangesKeepSettingsOpen() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 33)
        assumeFalse(AppleRepository.get(context).hasSession || LoginVault(context).hasSaved)
        val settings = AppearanceSettings(context)
        val oldAppearance = settings.appearance
        val oldMotion = settings.reduceMotion
        val locales = context.getSystemService(LocaleManager::class.java)
        val oldLocales = locales.applicationLocales
        try {
            settings.reduceMotion = true
            settings.appearance = Appearance.LIGHT
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var parent: View
                scenario.onActivity { activity ->
                    activity.findViewById<View>(MainActivity.MAP_OPTIONS_ID).performClick()
                    parent = activity.findViewById(MainActivity.PANEL_ID)
                }
                val drawn = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.window.decorView.viewTreeObserver.registerFrameCommitCallback {
                        drawn.countDown()
                    }
                    activity.window.decorView.invalidate()
                }
                assertTrue(drawn.await(5, TimeUnit.SECONDS))
                openMenu(scenario, R.id.display_language)
                assertTrue(
                    instrumentation.uiAutomation.rootInActiveWindow
                        .findAccessibilityNodeInfosByText("日本語")
                        .isNotEmpty()
                )
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertSame(
                        "Closing a choice menu must retain its parent panel",
                        parent,
                        activity.findViewById(MainActivity.PANEL_ID),
                    )
                }
                openMenu(scenario, R.id.display_appearance)
                val dark = context.getString(R.string.dark)
                awaitRestoredPanel(scenario) { choose(dark) }
                scenario.onActivity { activity ->
                    assertTrue(Ui.dark)
                    assertEquals(
                        activity.getString(R.string.map_view),
                        activity.findViewById<View>(MainActivity.PANEL_ID).accessibilityPaneTitle,
                    )
                }
                openMenu(scenario, R.id.display_language)
                val nextLanguage = if (oldLocales.toLanguageTags() == "de") "日本語" else "Deutsch"
                awaitRestoredPanel(scenario) { choose(nextLanguage) }
                scenario.onActivity { activity ->
                    val expectedTag = if (nextLanguage == "Deutsch") "de" else "ja"
                    assertEquals(expectedTag, AppLanguage.selected(activity))
                    assertNotNull(
                        "Locale recreation must restore the settings panel",
                        activity.findViewById<View>(R.id.panel_done),
                    )
                    activity.findViewById<View>(R.id.panel_done).performClick()
                    assertNull(activity.findViewById(MainActivity.PANEL_ID))
                }
            }
        } finally {
            settings.appearance = oldAppearance
            settings.reduceMotion = oldMotion
            locales.applicationLocales = oldLocales
            Ui.configure(context)
        }
    }

    @Test
    fun appleLoginSettingsStayInTheAppleSection() {
        assumeFalse(AppleRepository.get(context).hasSession || LoginVault(context).hasSaved)
        val settings = AppearanceSettings(context)
        val previous = settings.reduceMotion
        settings.reduceMotion = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView)
                        .first { it.contentDescription == activity.getString(R.string.me) }
                        .performClick()
                    val labels = descendants(activity.window.decorView).filterIsInstance<TextView>()
                    val apple = labels.indexOfFirst {
                        it.text.toString() == activity.getString(R.string.login_details)
                    }
                    val google = labels.indexOfFirst { it.text.toString() == "Google Find Hub" }
                    val app = labels.indexOfFirst {
                        it.text.toString() == activity.getString(R.string.app_settings)
                    }
                    assertTrue(apple >= 0 && apple < google && google < app)
                    assertTrue(labels[apple].text.contains("Apple"))
                }
            }
        } finally {
            settings.reduceMotion = previous
        }
    }

    private fun openMenu(scenario: ActivityScenario<MainActivity>, id: Int) {
        // Wait for the popup's window, rather than querying the previous accessibility root.
        instrumentation.uiAutomation.executeAndWaitForEvent(
            { scenario.onActivity { it.findViewById<View>(id).performClick() } },
            { event ->
                event.eventType ==
                    android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            },
            5000,
        )
        instrumentation.uiAutomation.waitForIdle(100, 5000)
    }

    private fun choose(label: String) {
        instrumentation.uiAutomation.waitForIdle(100, 5000)
        val matches =
            instrumentation.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(label)
        val node = matches.firstOrNull { it.isClickable } ?: matches.firstOrNull()?.parent
        assertNotNull("The choice $label must be available", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun awaitRestoredPanel(scenario: ActivityScenario<MainActivity>, action: () -> Unit) {
        var previous: MainActivity? = null
        scenario.onActivity { previous = it }
        val restored = CountDownLatch(1)
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        val listener = ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && activity !== previous && stage == Stage.RESUMED) {
                activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener {
                    if (activity.findViewById<View>(MainActivity.PANEL_ID) != null)
                        restored.countDown()
                }
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(listener) }
        try {
            action()
            assertTrue(
                "Settings must survive Activity recreation",
                restored.await(8, TimeUnit.SECONDS),
            )
        } finally {
            instrumentation.runOnMainSync { monitor.removeLifecycleCallback(listener) }
        }
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
}
