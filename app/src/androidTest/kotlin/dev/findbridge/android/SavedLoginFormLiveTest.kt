package dev.findbridge.android

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The owner's opt-in reconnect test. Credentials stay in the vault and protected native form. */
@RunWith(AndroidJUnit4::class)
class SavedLoginFormLiveTest {
    @Test
    fun connectWithAnEmptyPasswordFieldReusesSavedLoginAndReturnsFromOfficialAuthentication() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("autologin") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val vault = LoginVault(context)
        assertTrue("Owner-saved login is required", vault.hasSaved)
        val saved = vault.load()!!
        val connected = CountDownLatch(1)
        val cleared = CountDownLatch(1)
        lateinit var repository: AppleRepository
        val listener = AppleRepository.Listener {
            if (repository.hasSession && !repository.loading) {
                if (repository.nativeTransportVerified && repository.devices.isNotEmpty())
                    connected.countDown()
                else repository.refresh()
            }
        }
        instrumentation.runOnMainSync {
            repository = AppleRepository.get(context)
            repository.logout()
            CookieManager.getInstance().removeAllCookies { cleared.countDown() }
            repository.addListener(listener)
        }
        assertTrue(cleared.await(10, TimeUnit.SECONDS))
        try {
            val activity =
                instrumentation.startActivitySync(
                    Intent(context, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ) as LoginActivity
            try {
                val deadline = SystemClock.uptimeMillis() + 10000
                var restored = false
                while (!restored && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        restored =
                            activity
                                .findViewById<EditText>(LoginActivity.ACCOUNT_ID)
                                .text
                                .toString() == saved.account
                    }
                    SystemClock.sleep(100)
                }
                assertTrue("The saved login must load into the native form", restored)
                instrumentation.runOnMainSync {
                    assertTrue(
                        activity.findViewById<EditText>(LoginActivity.PASSWORD_ID).text.isEmpty()
                    )
                    activity.findViewById<View>(LoginActivity.CONTINUE_ID).performClick()
                }
                assertTrue(
                    "Saved-form reconnect must finish through official Apple authentication",
                    connected.await(120, TimeUnit.SECONDS),
                )
                assertTrue(repository.devices.any { it.located })
                assertTrue(repository.familyMembers.isNotEmpty())
            } finally {
                instrumentation.runOnMainSync {
                    if (!activity.isDestroyed && !activity.isFinishing) activity.finish()
                }
            }
        } finally {
            instrumentation.runOnMainSync { repository.removeListener(listener) }
        }
    }
}
