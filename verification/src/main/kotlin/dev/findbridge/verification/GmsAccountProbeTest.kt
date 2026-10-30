package dev.findbridge.verification

import android.accounts.Account
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in broker capability probe under the real product UID/signature. Never logs a token. */
@android.annotation.SuppressLint(
    "ResourceType"
) // Stable native View IDs in the separately built target APK.
class GmsAccountProbeTest {
    @Test
    fun requestsFindHubAuthorizationThroughGms() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gmsprobe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val host =
            instrumentation.startActivitySync(
                Intent()
                    .setClassName(context.packageName, "dev.findbridge.android.LoginActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        val completed = CountDownLatch(1)
        var accountName = ""
        val until = SystemClock.uptimeMillis() + 10_000
        while (accountName.isEmpty() && SystemClock.uptimeMillis() < until) {
            instrumentation.runOnMainSync {
                accountName = host.findViewById<EditText>(1101)?.text?.toString().orEmpty()
            }
            SystemClock.sleep(100)
        }
        assertTrue("The owner's designated saved account must exist", accountName.isNotEmpty())
        val outcome = JSONObject().put("service", "android_device_manager")
        try {
            instrumentation.runOnMainSync {
                Identity.getAuthorizationClient(host)
                    .authorize(
                        AuthorizationRequest.builder()
                            .setAccount(Account(accountName, "com.google"))
                            .setRequestedScopes(
                                listOf(
                                    Scope("https://www.googleapis.com/auth/android_device_manager")
                                )
                            )
                            .build()
                    )
                    .addOnSuccessListener { result ->
                        outcome.put("hasToken", !result.accessToken.isNullOrEmpty())
                        outcome.put("hasResolution", result.hasResolution())
                        completed.countDown()
                    }
                    .addOnFailureListener { failure ->
                        outcome.put("exceptionType", failure.javaClass.simpleName)
                        if (failure is ApiException) outcome.put("statusCode", failure.statusCode)
                        completed.countDown()
                    }
            }
            assertTrue(
                "GMS must return a token, resolution or failure",
                completed.await(60, TimeUnit.SECONDS),
            )
            // A resolution is not a completed authorization or a Find Hub read.
            instrumentation.sendStatus(
                0,
                Bundle().apply { putString("gmsDiagnostic", outcome.toString()) },
            )
        } finally {
            instrumentation.runOnMainSync {
                if (!host.isFinishing && !host.isDestroyed) host.finish()
            }
        }
    }
}
