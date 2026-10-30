package dev.findbridge.android

import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RefreshButtonTest {
    @Test
    fun loadingOnlyAnimatesTheIndicatorAndStopsWhenDetached() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = AppearanceSettings(context)
        val previous = settings.reduceMotion
        settings.reduceMotion = false
        val finished = CountDownLatch(1)
        val phases = mutableSetOf<Int>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        lateinit var button: RefreshButton
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        activity.findViewById<View>(MainActivity.REFRESH_ID) is RefreshButton
                    )
                    val root = activity.findViewById<FrameLayout>(android.R.id.content)
                    button =
                        RefreshButton(activity).apply {
                            refreshing = true
                            isEnabled = false
                        }
                    root.addView(button, FrameLayout.LayoutParams(132, 132))
                    repeat(3) { button.onVisibilityAggregated(true) }
                    val phase =
                        RefreshButton::class.java.getDeclaredField("phase").apply {
                            isAccessible = true
                        }
                    var frames = 0
                    val callback =
                        object : Choreographer.FrameCallback {
                            override fun doFrame(time: Long) {
                                try {
                                    phases += phase.getInt(button)
                                    assertEquals(
                                        "Loading never rotates the button or its glass",
                                        0f,
                                        button.rotation,
                                        0f,
                                    )
                                    assertEquals(0f, button.translationX, 0f)
                                    assertEquals(0f, button.translationY, 0f)
                                    if (++frames < 24)
                                        Choreographer.getInstance().postFrameCallback(this)
                                    else finished.countDown()
                                } catch (error: Throwable) {
                                    failure.set(error)
                                    finished.countDown()
                                }
                            }
                        }
                    Choreographer.getInstance().postFrameCallback(callback)
                }
                assertTrue(finished.await(5, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                assertTrue(
                    "The internal indicator must advance while the control stays fixed",
                    phases.size > 1,
                )
                scenario.onActivity {
                    button.refreshing = false
                    val animator =
                        RefreshButton::class.java.getDeclaredField("animator").apply {
                            isAccessible = true
                        }
                    assertNull(animator.get(button))
                    button.refreshing = true
                    (button.parent as android.view.ViewGroup).removeView(button)
                    assertNull("A detached button must stop its indicator", animator.get(button))
                    settings.reduceMotion = true
                    button.refreshing = false
                    button.refreshing = true
                    assertNull(animator.get(button))
                }
            }
        } finally {
            settings.reduceMotion = previous
        }
    }
}
