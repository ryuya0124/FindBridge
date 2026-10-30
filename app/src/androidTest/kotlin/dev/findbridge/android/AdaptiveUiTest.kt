package dev.findbridge.android

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

@RunWith(AndroidJUnit4::class)
class AdaptiveUiTest {
    @Test
    fun mapAndNavigationRemainUsableAtTheCurrentWindowSize() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            SystemClock.sleep(900)
            scenario.onActivity { activity ->
                val sheet = activity.findViewById<SheetGestureLayout>(MainActivity.SHEET_ID)
                val map = activity.findViewById<MapView>(MainActivity.MAP_ID)
                val tabs =
                    activity.findViewById<View>(MainActivity.TAB_SELECTION_ID).parent
                        as NativeTabBar
                val control = activity.findViewById<View>(MainActivity.MAP_OPTIONS_ID)
                val root = map.parent as View
                val bounds = Rect(0, 0, root.width, root.height)
                fun rect(view: View): Rect {
                    val xy = IntArray(2)
                    view.getLocationOnScreen(xy)
                    val origin = IntArray(2)
                    root.getLocationOnScreen(origin)
                    return Rect(
                        xy[0] - origin[0],
                        xy[1] - origin[1],
                        xy[0] - origin[0] + view.width,
                        xy[1] - origin[1] + view.height,
                    )
                }
                for (view in listOf(sheet, tabs, control)) {
                    assertTrue(
                        "Interactive views must stay inside the current window",
                        bounds.contains(rect(view)),
                    )
                    assertTrue(view.width > 0 && view.height > 0)
                }
                assertFalse(
                    "Map controls must not be covered by the list",
                    Rect.intersects(rect(control), rect(sheet)),
                )
                assertFalse(
                    "Tabs must not be covered by the list",
                    Rect.intersects(rect(tabs), rect(sheet)),
                )
                if (map.height < root.height * .8f) {
                    assertFalse(sheet.dragEnabled)
                    assertTrue(
                        "Tabletop list must stay below the map pane",
                        rect(sheet).top >= rect(map).bottom,
                    )
                } else if (root.width / activity.resources.displayMetrics.density >= 600) {
                    assertFalse(sheet.dragEnabled)
                    assertTrue(
                        "Wide layouts must keep visible map space beside the list",
                        sheet.width < root.width * .65f,
                    )
                }
            }
        }
    }
}
