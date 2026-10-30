package dev.findbridge.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only. Run in a fresh app process after the owner has saved the test account locally. */
@RunWith(AndroidJUnit4::class)
class AutomaticLoginLiveTest {
    @Test
    fun encryptedCredentialsRestoreAnExpiredLocalSessionThroughOfficialAppleSignIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("autologin") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val vault = LoginVault(context)
        assertTrue("Owner-saved credentials must exist", vault.hasSaved)
        assertTrue("The owner must enable automatic sign-in", vault.automatic)
        val login = vault.load()
        assertNotNull(login)
        // This explicit opt-in represents the owner's reconnect action; normal retries stay
        // rate-limited.
        vault.save(login!!, true)
        val encrypted =
            File(context.applicationInfo.dataDir, "shared_prefs/apple_credentials.xml").readText()
        assertFalse(
            "Account must not be persisted in plaintext",
            encrypted.contains(login!!.account),
        )
        assertFalse(
            "Password must not be persisted in plaintext",
            encrypted.contains(login.password),
        )
        lateinit var repository: AppleRepository
        val ready = CountDownLatch(1)
        val listener = AppleRepository.Listener {
            if (
                repository.hasSession &&
                    repository.nativeTransportVerified &&
                    repository.devices.isNotEmpty()
            )
                ready.countDown()
        }
        val cookiesCleared = CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.webkit.CookieManager.getInstance().removeAllCookies {
                cookiesCleared.countDown()
            }
        }
        assertTrue(cookiesCleared.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            repository = AppleRepository.get(context)
            repository.addListener(listener)
            repository.logout()
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        try {
            assertTrue(
                "Official automatic sign-in and native refresh must complete",
                ready.await(120, TimeUnit.SECONDS),
            )
            assertTrue(repository.familyMembers.isNotEmpty())
            assertTrue(repository.devices.any { it.located })
            println("Encrypted login restore, official Apple authentication, native refresh: PASS")
        } finally {
            instrumentation.runOnMainSync { repository.removeListener(listener) }
        }
    }
}
