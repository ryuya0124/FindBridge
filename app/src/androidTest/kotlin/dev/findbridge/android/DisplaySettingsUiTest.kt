package dev.findbridge.android

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.findbridge.android.Ui.dp
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

class DisplaySettingsUiTest {
    @Test
    fun doneStaysVisibleAndChoicesRemainReadableInBothAppearances() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeFalse(
            "UI fixtures must not use owner accounts",
            AppleRepository.get(context).hasSession ||
                LoginVault(context).hasSaved ||
                GoogleRepository.get(context).connected,
        )
        val settings = AppearanceSettings(context)
        val oldAppearance = settings.appearance
        val oldMotion = settings.reduceMotion
        try {
            settings.reduceMotion = true
            for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
                settings.appearance = appearance
                val options = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
                ActivityScenario.launch(MainActivity::class.java, options).use { scenario ->
                    instrumentation.waitForIdleSync()
                    var before = Rect()
                    scenario.onActivity { activity ->
                        activity.findViewById<View>(MainActivity.MAP_OPTIONS_ID).performClick()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val panel = activity.findViewById<View>(MainActivity.PANEL_ID)
                        before = bounds(panel.findViewById(R.id.panel_done))
                        val options = descendants(panel).filterIsInstance<MapModePicker>().single()
                        assertEquals(3, options.childCount)
                        assertEquals(1, (0 until 3).count { options.getChildAt(it).isSelected })
                        for (index in 0 until 3) {
                            val option = options.getChildAt(index)
                            assertTrue(bounds(option).height() >= activity.dp(96))
                            assertTrue(option.createAccessibilityNodeInfo().isCheckable)
                        }
                        descendants(panel)
                            .filterIsInstance<ScrollView>()
                            .single()
                            .scrollTo(0, Int.MAX_VALUE)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val panel = activity.findViewById<View>(MainActivity.PANEL_ID)
                        assertEquals(
                            "Done must not scroll with the settings",
                            before,
                            bounds(panel.findViewById(R.id.panel_done)),
                        )
                        assertEquals(activity.dp(44), before.height())
                    }
                    if (InstrumentationRegistry.getArguments().getString("screenshots") == "true") {
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            val drawn = CountDownLatch(1)
                            scenario.onActivity { activity ->
                                activity.window.decorView.viewTreeObserver
                                    .registerFrameCommitCallback {
                                        drawn.countDown()
                                    }
                                activity.window.decorView.invalidate()
                            }
                            assertTrue(
                                "The settings frame must be committed",
                                drawn.await(5, TimeUnit.SECONDS),
                            )
                        }
                        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                            File(context.cacheDir, "display-${appearance.name.lowercase()}.png")
                                .outputStream()
                                .use {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            bitmap.recycle()
                        }
                    }
                    scenario.onActivity { activity ->
                        activity.findViewById<View>(R.id.panel_done).performClick()
                        assertNull(activity.findViewById(MainActivity.PANEL_ID))
                    }
                }
            }
        } finally {
            settings.appearance = oldAppearance
            settings.reduceMotion = oldMotion
            Ui.configure(context)
        }
    }

    private fun bounds(view: View): Rect = Rect().also { assertTrue(view.getGlobalVisibleRect(it)) }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()
}
