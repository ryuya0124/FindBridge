package dev.findbridge.android

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

@RunWith(AndroidJUnit4::class)
class RegressionUiTest {
    @Test
    fun mapSettingsReceiveRealTouchesWhereTheyOverlapTheDeviceSheetAndTabs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = AppearanceSettings(instrumentation.targetContext)
        val previous = settings.reduceMotion
        settings.reduceMotion = true
        fun find(view: View, predicate: (View) -> Boolean): View? {
            if (predicate(view)) return view
            if (view is ViewGroup)
                for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let {
                    return it
                }
            return null
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(500)
                scenario.onActivity { activity ->
                    assumeTrue(
                        activity.findViewById<SheetGestureLayout>(MainActivity.SHEET_ID).dragEnabled
                    )
                    find(activity.window.decorView) {
                            it.contentDescription == activity.getString(R.string.people)
                        }!!
                        .performClick()
                    val map =
                        MainActivity::class
                            .java
                            .getDeclaredField("map")
                            .apply { isAccessible = true }
                            .get(activity) as? MapLibreMap
                    map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(36.2, 138.2), 4.5))
                    activity.findViewById<View>(MainActivity.MAP_OPTIONS_ID).performClick()
                }
                SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    val panel = activity.findViewById<View>(MainActivity.PANEL_ID)
                    (find(panel) { it is ScrollView } as ScrollView).let {
                        it.scrollTo(0, it.getChildAt(0).height)
                    }
                }
                SystemClock.sleep(250)
                val point = Rect()
                scenario.onActivity { activity ->
                    val panel = activity.findViewById<View>(MainActivity.PANEL_ID)
                    val row = panel.findViewById<View>(R.id.display_language)
                    assertTrue(
                        "The lower settings row must remain visible after scrolling",
                        row.getGlobalVisibleRect(point),
                    )
                    assertTrue(point.height() > 30 * activity.resources.displayMetrics.density)
                    val sheet = Rect()
                    activity.findViewById<View>(MainActivity.SHEET_ID).getGlobalVisibleRect(sheet)
                    assertTrue(
                        "The regression touch must be below the device sheet's top",
                        point.centerY() >= sheet.top,
                    )
                }
                if (
                    InstrumentationRegistry.getArguments().getString("screenshots") == "true" &&
                        !LocationSyncRepository.get(instrumentation.targetContext).configured
                ) {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    File(instrumentation.targetContext.cacheDir, "modal-layer-qa.png")
                        .outputStream()
                        .use {
                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    screenshot.recycle()
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
                    SystemClock.sleep(60)
                }
                SystemClock.sleep(250)
                scenario.onActivity { activity ->
                    val next = activity.findViewById<View>(MainActivity.PANEL_ID)
                    assertNotNull("The language picker must open", next)
                    assertEquals(activity.getString(R.string.map_view), next.accessibilityPaneTitle)
                }
                val choices =
                    instrumentation.uiAutomation.rootInActiveWindow
                        .findAccessibilityNodeInfosByText("日本語")
                assertTrue(
                    "The language choices must open above the unchanged settings panel",
                    choices.isNotEmpty(),
                )
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.panel_done).performClick()
                }
            }
        } finally {
            settings.reduceMotion = previous
        }
    }
}
