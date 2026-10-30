package dev.findbridge.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in recovery of the exact warm-session path; read-only Apple requests, no sounds. */
@RunWith(AndroidJUnit4::class)
class WarmSessionRecoveryLiveTest {
    @Test
    fun successfulRecentLoginDoesNotBlockRecoveryOfAnExpiredNativeSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("warmauth") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val vault = LoginVault(context)
        assertTrue(vault.hasSaved && vault.automatic)
        vault.markAttempt()
        vault.markSuccess()
        assertTrue(
            "A successful native session must not leave a five-minute retry delay",
            vault.automaticAllowed(),
        )
        lateinit var repository: AppleRepository
        val recovered = CountDownLatch(1)
        val listener = AppleRepository.Listener {
            if (
                !repository.loading &&
                    !repository.requiresSignIn &&
                    repository.nativeTransportVerified &&
                    repository.devices.isNotEmpty()
            )
                recovered.countDown()
        }
        instrumentation.runOnMainSync {
            repository = AppleRepository.get(context)
            assertTrue(repository.hasSession)
            val field =
                AppleRepository::class.java.getDeclaredField("session").apply {
                    isAccessible = true
                }
            val expired = JSONObject((field.get(repository) as JSONObject).toString())
            expired.put("cookie", "findbridge_expired_fixture=1")
            field.set(repository, expired)
            AppleRepository::class
                .java
                .getDeclaredField("nativeTransportVerified")
                .apply { isAccessible = true }
                .setBoolean(repository, false)
            repository.addListener(listener)
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
        try {
            assertTrue(
                "Warm-session expiry must trigger real sign-in and native refresh",
                recovered.await(120, TimeUnit.SECONDS),
            )
            assertTrue(repository.devices.any { it.located })
            assertFalse(repository.requiresSignIn)
            assertTrue(vault.automaticAllowed())
            println(
                "Warm expired session recovered through official Apple authentication and native refresh: PASS"
            )
        } finally {
            instrumentation.runOnMainSync { repository.removeListener(listener) }
        }
    }
}
