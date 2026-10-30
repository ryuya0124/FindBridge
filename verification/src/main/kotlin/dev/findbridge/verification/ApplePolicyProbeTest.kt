package dev.findbridge.verification

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Only non-secret policy booleans and elapsed time; never decrypts credentials or sessions. */
class ApplePolicyProbeTest {
    @Test
    fun recordsReconnectPolicyWithoutChangingIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("applepolicy") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val policy = context.getSharedPreferences("apple_credentials_policy", Context.MODE_PRIVATE)
        val session = context.getSharedPreferences("apple_session", Context.MODE_PRIVATE)
        val attempted = policy.getLong("attemptedAt", 0)
        val outcome =
            JSONObject()
                .put("saved", policy.getBoolean("saved", false))
                .put("automatic", policy.getBoolean("automatic", false))
                .put("paused", policy.getBoolean("paused", false))
                .put(
                    "attemptAgeMs",
                    if (attempted == 0L) -1 else System.currentTimeMillis() - attempted,
                )
                .put("encryptedSessionExists", session.contains("data"))
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("applePolicy", outcome.toString()) },
        )
    }
}
