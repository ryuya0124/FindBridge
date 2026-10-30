package dev.findbridge.android

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Temporary in-memory fixtures; accounts, encrypted stores and cookies are unchanged. */
class DeviceRowsUiTest {
    @Test
    fun longNamesDoNotMoveTheBatteryOrChevron() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val apple = AppleRepository.get(context)
        val google = GoogleRepository.get(context)
        assumeFalse(
            "Preserve owner-connected accounts",
            apple.hasSession || google.connected || LoginVault(context).hasSaved,
        )
        val sync = LocationSyncRepository.get(context)
        assumeFalse(
            sync.people.isNotEmpty() || sync.items.isNotEmpty() || sync.pencils.isNotEmpty()
        )
        val settings = AppearanceSettings(context)
        val oldAppearance = settings.appearance
        val connected =
            GoogleRepository::class.java.getDeclaredField("connected").apply { isAccessible = true }
        val devices =
            GoogleRepository::class.java.getDeclaredField("devices").apply { isAccessible = true }
        val oldDevices = google.devices
        val fixture =
            GoogleProtocol.devices(
                JSONObject(
                    """{"account":"fixture@example.invalid","devices":[{"index":0,"name":"TEST FIXTURE · Android phone with a long device name","battery":47,"latitude":35.68,"longitude":139.76},{"index":1,"name":"TEST FIXTURE · Tablet","battery":9}]}"""
                )
            )
        try {
            for (mode in listOf(Appearance.LIGHT, Appearance.DARK)) {
                settings.appearance = mode
                val drawn = CountDownLatch(1)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        connected.setBoolean(google, true)
                        devices.set(google, fixture)
                        activity.onChanged()
                        activity.window.decorView.viewTreeObserver.registerFrameCommitCallback {
                            drawn.countDown()
                        }
                        activity.window.decorView.invalidate()
                    }
                    assertTrue(drawn.await(5, TimeUnit.SECONDS))
                    scenario.onActivity { activity ->
                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            val mask =
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                                    android.view.WindowInsetsController
                                        .APPEARANCE_LIGHT_NAVIGATION_BARS
                            assertEquals(
                                if (mode == Appearance.DARK) 0 else mask,
                                activity.window.insetsController!!.systemBarsAppearance and mask,
                            )
                        }
                        fun descendants(view: View): List<View> =
                            listOf(view) +
                                if (view is ViewGroup)
                                    (0 until view.childCount).flatMap {
                                        descendants(view.getChildAt(it))
                                    }
                                else emptyList()
                        val views = descendants(activity.window.decorView)
                        val name =
                            views.filterIsInstance<TextView>().first {
                                it.text.toString() == fixture[0].name
                            }
                        val battery =
                            views.filterIsInstance<TextView>().first { it.text.toString() == "47%" }
                        val namePosition = IntArray(2)
                        name.getLocationOnScreen(namePosition)
                        val batteryPosition = IntArray(2)
                        battery.getLocationOnScreen(batteryPosition)
                        assertEquals(
                            namePosition[1] + name.baseline,
                            batteryPosition[1] + battery.baseline,
                        )
                        assertTrue(namePosition[0] + name.width <= batteryPosition[0])
                        val row = name.parent.parent as ViewGroup
                        val chevron =
                            (0 until row.childCount)
                                .map { row.getChildAt(it) }
                                .filterIsInstance<TextView>()
                                .single { it.text == "›" }
                        assertTrue(
                            kotlin.math.abs(row.height / 2 - chevron.top - chevron.height / 2) <= 1
                        )
                    }
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(context.cacheDir, "rows-${mode.name.lowercase()}.png")
                            .outputStream()
                            .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                    scenario.onActivity {
                        connected.setBoolean(google, false)
                        devices.set(google, oldDevices)
                    }
                }
            }
        } finally {
            connected.setBoolean(google, false)
            devices.set(google, oldDevices)
            settings.appearance = oldAppearance
            Ui.configure(context)
        }
    }
}
