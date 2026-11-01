package dev.findbridge.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Pure native presentation fixture: no authentication engine or credentials are instantiated. */
class InlineProgressLayoutTest {
    @Test
    fun lightAndDarkProgressFitInsideTheSheetWithoutCoveringTheMapOrTabs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedSessionFixture") == "true"
        )
        val context = instrumentation.targetContext
        val settings = AppearanceSettings(context)
        val previous = settings.appearance
        try {
            for (mode in listOf(Appearance.LIGHT, Appearance.DARK)) {
                settings.appearance = mode
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val laidOut = CountDownLatch(1)
                    scenario.onActivity { activity ->
                        // Presentation-only fixtures must survive asynchronous empty-catalog
                        // updates.
                        AppleRepository.get(context).removeListener(activity)
                        GoogleRepository.get(context).removeListener(activity)
                        LocationSyncRepository.get(context).removeListener(activity)
                        val body =
                            MainActivity::class
                                .java
                                .getDeclaredField("body")
                                .apply { isAccessible = true }
                                .get(activity) as LinearLayout
                        while (body.childCount > 1) body.removeViewAt(1)
                        body.addView(
                            InlineSignInView(activity, AppleSignInState.Working, {}, {}, {})
                        )
                        body.postOnAnimation { body.postOnAnimation { laidOut.countDown() } }
                    }
                    assertTrue(laidOut.await(5, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        val sheet = activity.findViewById<View>(MainActivity.SHEET_ID)
                        val progress = activity.findViewById<View>(InlineSignInView.PROGRESS_ID)
                        val row = activity.findViewById<View>(InlineSignInView.STATUS_ID)
                        val sheetBounds = Rect().also { sheet.getGlobalVisibleRect(it) }
                        val rowBounds = Rect().also { row.getGlobalVisibleRect(it) }
                        assertTrue(sheetBounds.contains(rowBounds))
                        assertEquals(row.height, rowBounds.height())
                        assertEquals(row.width, rowBounds.width())
                        assertTrue(progress.width > activity.resources.displayMetrics.density * 200)
                        assertTrue(progress.height > 0)
                        assertTrue(activity.findViewById<View>(MainActivity.MAP_ID).isShown)
                        assertTrue(
                            activity.findViewById<View>(MainActivity.TAB_SELECTION_ID).isShown
                        )
                        assertEquals(
                            0,
                            activity.window.attributes.flags and
                                WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    }
                    scenario.onActivity { activity ->
                        assertNotNull(activity.findViewById<View>(InlineSignInView.STATUS_ID))
                    }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(
                                context.getExternalFilesDir(null),
                                "inline-progress-${mode.name.lowercase()}.png",
                            )
                            .outputStream()
                            .use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            settings.appearance = previous
            Ui.configure(context)
        }
    }
}
