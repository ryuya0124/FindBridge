package dev.findbridge.android

import android.content.DialogInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Only a local counter is called. No repository or real sound endpoint is used. */
@RunWith(AndroidJUnit4::class)
class SoundConfirmationTest {
    @Test
    fun playbackRequiresTheExplicitPositiveAction() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            val requests = AtomicInteger()
            scenario.onActivity { activity ->
                val cancelled =
                    SoundConfirmation.show(activity, "Fixture device") {
                        requests.incrementAndGet()
                    }
                assertEquals(0, requests.get())
                cancelled.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, requests.get())
            scenario.onActivity { activity ->
                val confirmed =
                    SoundConfirmation.show(activity, "Fixture device") {
                        requests.incrementAndGet()
                    }
                assertEquals(0, requests.get())
                confirmed.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                confirmed.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(1, requests.get())
        }
    }
}
