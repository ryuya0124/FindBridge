package dev.findbridge.android

import android.content.DialogInterface
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import dev.findbridge.android.MaterialControls.circularIndicator
import dev.findbridge.android.MaterialControls.linearIndicator
import dev.findbridge.android.MaterialControls.materialSlider
import dev.findbridge.android.MaterialControls.materialSwitch
import dev.findbridge.android.update.UpdateActivity
import dev.findbridge.android.update.UpdatePhase
import dev.findbridge.android.update.UpdateRepository
import dev.findbridge.android.update.UpdateScheduler
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Presentation fixtures only; never signs in or sends device commands. */
class MaterialControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var previousAutomatic = false
    private var previousPreviews = false
    private var previousInterval = 12
    private var isolated = false

    @Before
    fun disposableOnly() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedSessionFixture") == "true"
        )
        assertFalse(LoginVault(context).hasSaved || AppleRepository.get(context).hasSession)
        val updates = UpdateRepository.get(context)
        previousAutomatic = updates.automatic
        previousPreviews = updates.previews
        previousInterval = updates.intervalHours
        isolated = true
        updates.setAutomatic(false)
        drain(updates)
    }

    @After
    fun restoreUpdateSettings() {
        if (!isolated) return
        val updates = UpdateRepository.get(context)
        updates.setAutomatic(false)
        drain(updates)
        updates.prefs
            .edit()
            .putBoolean("automatic", previousAutomatic)
            .putBoolean("previews", previousPreviews)
            .putInt("intervalHours", previousInterval)
            .apply()
        UpdateScheduler.configure(context)
    }

    private fun drain(updates: UpdateRepository) {
        val done = CountDownLatch(1)
        updates.work { done.countDown() }
        assertTrue(done.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun standardControlsUseMaterial3AndKeepNativeBehavior() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val token = android.util.TypedValue()
                assertTrue(
                    activity.theme.resolveAttribute(
                        com.google.android.material.R.attr.isMaterial3Theme,
                        token,
                        true,
                    )
                )
                assertNotEquals(0, token.data)
                val toggle = activity.materialSwitch()
                assertTrue(toggle is MaterialSwitch)
                var changes = 0
                toggle.setOnCheckedChangeListener { _, _ -> changes++ }
                toggle.performClick()
                assertTrue(toggle.isChecked)
                assertEquals(1, changes)
                assertTrue(activity.materialSlider() is Slider)
                assertTrue(activity.linearIndicator() is LinearProgressIndicator)
                assertTrue(activity.circularIndicator() is CircularProgressIndicator)
                assertTrue(activity.linearIndicator().isIndeterminate)
                assertFalse(activity.linearIndicator(false).isIndeterminate)
            }
        }
    }

    @Test
    fun soundConfirmationUsesAMaterialDialogAndCancelDoesNotSend() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                var sent = false
                val dialog = SoundConfirmation.show(activity, "Public fixture") { sent = true }
                val value = android.util.TypedValue()
                assertTrue(
                    dialog.context.theme.resolveAttribute(
                        com.google.android.material.R.attr.isMaterial3Theme,
                        value,
                        true,
                    )
                )
                assertNotEquals(0, value.data)
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                assertFalse(sent)
            }
        }
    }

    @Test
    fun updateChoicesShareTheGlassMenuAndSurviveStatusChangesInBothAppearances() {
        val settings = AppearanceSettings(context)
        val oldAppearance = settings.appearance
        val repository = UpdateRepository.get(context)
        try {
            for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
                settings.appearance = appearance
                ActivityScenario.launch(UpdateActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.findViewById<View>(UpdateActivity.FREQUENCY_ID).apply {
                            requestRectangleOnScreen(
                                android.graphics.Rect(0, 0, width, height),
                                true,
                            )
                            performClick()
                        }
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        val rows = choices()
                        assertEquals(4, rows.childCount)
                        assertEquals(
                            1,
                            (0 until rows.childCount).count {
                                rows.getChildAt(it).createAccessibilityNodeInfo().isChecked
                            },
                        )
                        assertTrue(ancestors(rows).any { it.background is GlassDrawable })
                        repository.phase(UpdatePhase.CHECKING)
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertEquals(4, choices().childCount)
                        repository.phase(UpdatePhase.CURRENT)
                    }
                    instrumentation.waitForIdleSync()
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        File(
                                context.getExternalFilesDir(null),
                                "update-choices-${appearance.name.lowercase()}.png",
                            )
                            .outputStream()
                            .use {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                        bitmap.recycle()
                    }
                    scenario.onActivity { choices().getChildAt(2).performClick() }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        assertFalse(activity.isFinishing)
                        assertEquals(UpdatePhase.CURRENT, repository.state.phase)
                        activity.findViewById<View>(UpdateActivity.PREVIEWS_ID).performClick()
                    }
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertEquals(2, choices().childCount)
                        choices().getChildAt(0).performClick()
                    }
                }
            }
        } finally {
            settings.appearance = oldAppearance
            Ui.configure(context)
        }
    }

    private fun choices(): LinearLayout =
        WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
            it.findViewById<LinearLayout>(R.id.display_choices)
        } ?: error("No shared glass choice menu")

    private fun ancestors(view: View): List<View> =
        listOf(view) + ((view.parent as? View)?.let(::ancestors) ?: emptyList())
}
