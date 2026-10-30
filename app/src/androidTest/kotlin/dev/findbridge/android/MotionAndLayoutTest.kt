package dev.findbridge.android

import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotionAndLayoutTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(24)
        }
        assertTrue(message, condition())
    }

    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)?.let {
                return it
            }
        return null
    }

    @Test
    fun tabPillActuallyMovesAndRapidRetargetingEndsAtTheLastTab() {
        val settings = AppearanceSettings(instrumentation.targetContext)
        val previous = settings.reduceMotion
        settings.reduceMotion = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(700)
                var from = 0f
                var destination = 0f
                lateinit var pill: View
                val intermediate = CountDownLatch(1)
                var frameCount = 0
                var lastPosition = 0f
                scenario.onActivity { activity ->
                    assumeTrue(!NativeMotion.reduced(activity))
                    pill = activity.findViewById(MainActivity.TAB_SELECTION_ID)
                    assumeTrue((pill.parent as View).width > (pill.parent as View).height)
                    from = pill.translationX
                    val until = SystemClock.uptimeMillis() + 5000
                    Choreographer.getInstance()
                        .postFrameCallback(
                            object : Choreographer.FrameCallback {
                                override fun doFrame(frameTimeNanos: Long) {
                                    frameCount++
                                    lastPosition = pill.translationX
                                    if (lastPosition > from + 1f && lastPosition < destination - 1f)
                                        intermediate.countDown()
                                    if (
                                        SystemClock.uptimeMillis() < until && intermediate.count > 0
                                    )
                                        Choreographer.getInstance().postFrameCallback(this)
                                }
                            }
                        )
                    find(activity.window.decorView) {
                            it.contentDescription == activity.getString(R.string.me)
                        }!!
                        .performClick()
                    destination = pill.width * 3f
                    assertEquals(
                        "The pill must not jump to its destination",
                        from,
                        pill.translationX,
                        .1f,
                    )
                }
                val sawIntermediate = intermediate.await(5, TimeUnit.SECONDS)
                assertTrue(
                    "Tab pill must produce intermediate frames (frames=$frameCount, from=$from, target=$destination, last=$lastPosition)",
                    sawIntermediate,
                )
                scenario.onActivity { activity ->
                    for (id in listOf(R.string.items, R.string.people, R.string.devices)) find(
                            activity.window.decorView
                        ) {
                            it.contentDescription == activity.getString(id)
                        }!!
                        .performClick()
                }
                var settled = false
                await("Repeated taps must settle on Devices") {
                    scenario.onActivity { settled = abs(pill.translationX - pill.width) < 2f }
                    settled
                }
            }
        } finally {
            settings.reduceMotion = previous
        }
    }

    @Test
    fun sheetSpringProducesIntermediateFramesAndCanBeInterruptedByDragging() {
        val settings = AppearanceSettings(instrumentation.targetContext)
        val previous = settings.reduceMotion
        settings.reduceMotion = false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(700)
                var initial = 0
                var moving = false
                lateinit var sheet: SheetGestureLayout
                var density = 1f
                scenario.onActivity { activity ->
                    sheet = activity.findViewById(MainActivity.SHEET_ID)
                    assumeTrue(sheet.dragEnabled && !NativeMotion.reduced(activity))
                    density = activity.resources.displayMetrics.density
                    initial = sheet.height
                    activity.findViewById<View>(MainActivity.SHEET_HANDLE_ID).performClick()
                    assertEquals(initial, sheet.height)
                }
                await("Expanding sheet must animate") {
                    scenario.onActivity { moving = sheet.height > initial + 30 * density }
                    moving
                }
                var x = 0f
                var y = 0f
                scenario.onActivity {
                    val position = IntArray(2)
                    sheet.getLocationOnScreen(position)
                    x = position[0] + sheet.width / 2f
                    y = position[1] + 12 * density
                }
                val down = SystemClock.uptimeMillis()
                fun send(action: Int, at: Float) {
                    val event =
                        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, at, 0)
                    instrumentation.sendPointerSync(event)
                    event.recycle()
                }
                send(MotionEvent.ACTION_DOWN, y)
                repeat(9) { index ->
                    SystemClock.sleep(18)
                    send(MotionEvent.ACTION_MOVE, y + (index + 1) * 20 * density)
                }
                send(MotionEvent.ACTION_UP, y + 180 * density)
                var finalHeight = 0
                await("Released sheet must settle at a stop") {
                    scenario.onActivity { finalHeight = sheet.height }
                    abs(finalHeight - initial) < 2 * density ||
                        abs(finalHeight - 138 * density) < 2 * density
                }
            }
        } finally {
            settings.reduceMotion = previous
        }
    }

    @Test
    fun reduceMotionUsesImmediateTransitionsAndButtonsHaveNativeIcons() {
        val settings = AppearanceSettings(instrumentation.targetContext)
        val previous = settings.reduceMotion
        settings.reduceMotion = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(200)
                scenario.onActivity { activity ->
                    assertTrue(
                        activity.findViewById<View>(MainActivity.MAP_OPTIONS_ID) is MapControlButton
                    )
                    assertTrue(
                        activity.findViewById<View>(MainActivity.RECENTER_ID) is MapControlButton
                    )
                    find(activity.window.decorView) {
                            it.contentDescription == activity.getString(R.string.people)
                        }!!
                        .performClick()
                    val pill = activity.findViewById<View>(MainActivity.TAB_SELECTION_ID)
                    assertEquals(0f, pill.translationX, .1f)
                    assertEquals(0f, pill.translationY, .1f)
                    assertEquals(1f, activity.findViewById<View>(MainActivity.SHEET_ID).alpha, 0f)
                    activity.findViewById<View>(MainActivity.MAP_OPTIONS_ID).performClick()
                    assertNotNull(activity.findViewById<View>(MainActivity.PANEL_ID))
                    @Suppress("DEPRECATION") activity.onBackPressed()
                    assertNull(activity.findViewById<View>(MainActivity.PANEL_ID))
                }
            }
        } finally {
            settings.reduceMotion = previous
        }
    }
}
