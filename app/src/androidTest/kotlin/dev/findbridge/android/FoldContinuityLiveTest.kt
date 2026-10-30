package dev.findbridge.android

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Host changes the actual emulator posture at each READY status. No production test hooks. */
@RunWith(AndroidJUnit4::class)
class FoldContinuityLiveTest {
    @Test
    fun selectedDetailsSurviveClosingAndOpeningTheDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("fold") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sync = LocationSyncRepository.get(context)
        assumeFalse(sync.configured)
        val file = File(context.cacheDir, "fold-fixture.json")
        file.writeText(
            """{"source":"TEST FIXTURE","items":[{"id":"fold-pencil","name":"Fold Fixture Pencil","kind":"Pencil"}]}"""
        )
        val ready = CountDownLatch(1)
        val listener = AppleRepository.Listener { if (!sync.loading) ready.countDown() }
        fun find(view: View, text: String): View? {
            if (view is TextView && view.text.toString() == text) return view
            if (view is ViewGroup)
                for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let {
                    return it
                }
            return null
        }
        try {
            instrumentation.runOnMainSync {
                sync.addListener(listener)
                sync.importFile(Uri.fromFile(file))
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                SystemClock.sleep(1200)
                scenario.onActivity { activity ->
                    val row = find(activity.window.decorView, "Fold Fixture Pencil")!!
                    (row.parent.parent as View).performClick()
                }
                SystemClock.sleep(700)
                for (stage in listOf("CLOSE", "OPEN")) {
                    var oldWidth = 0
                    var oldHeight = 0
                    scenario.onActivity { activity ->
                        val map = activity.findViewById<View>(MainActivity.MAP_ID)
                        val root = map.parent as View
                        oldWidth = root.width
                        oldHeight = root.height
                        assertNotNull(activity.findViewById<View>(MainActivity.SOUND_ID))
                    }
                    instrumentation.sendStatus(
                        1,
                        Bundle().apply { putString("stream", "\nREADY_FOR_$stage\n") },
                    )
                    val deadline = SystemClock.uptimeMillis() + 40000
                    var resized = false
                    while (!resized && SystemClock.uptimeMillis() < deadline) {
                        scenario.onActivity { activity ->
                            val root =
                                activity.findViewById<View>(MainActivity.MAP_ID).parent as View
                            resized = root.width != oldWidth || root.height != oldHeight
                        }
                        SystemClock.sleep(250)
                    }
                    assertTrue("Window must resize after $stage", resized)
                    SystemClock.sleep(1400)
                    scenario.onActivity { activity ->
                        val sound = activity.findViewById<View>(MainActivity.SOUND_ID)
                        assertNotNull("Selected details must survive $stage", sound)
                        assertFalse(sound.isEnabled)
                        assertNotNull(find(activity.window.decorView, "Fold Fixture Pencil"))
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                sync.removeListener(listener)
                sync.disconnect()
            }
            file.delete()
        }
    }
}
