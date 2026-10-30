package dev.findbridge.verification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Exercises the signed R8 APK through its private read-only Messenger protocol. No login UI. */
class GoogleReadLiveTest {
    @Test
    fun signedReleaseReadsTheOwnerAuthorizedGoogleCatalogAndPositions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("googleread") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // The service has one native client. Do not race the visible Activity's
        // refresh with this separate, opt-in read-only instrumentation client.
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.values()
                .flatMap { monitor.getActivitiesInStage(it) }
                .distinct()
                .filter { it.javaClass.name == "dev.findbridge.android.MainActivity" }
                .forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        val prefs = context.getSharedPreferences("google_identity", Context.MODE_PRIVATE)
        val key =
            KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }
                .getKey("findbridge.google_identity.v1", null) as SecretKey
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)),
                )
            }
        val identity =
            JSONObject(
                String(
                    cipher.doFinal(Base64.decode(prefs.getString("data", ""), Base64.NO_WRAP)),
                    Charsets.UTF_8,
                )
            )
        assertTrue("The owner must already be signed in", identity.optBoolean("verified"))
        val expectedAccount = identity.getString("account")
        val finished = CountDownLatch(1)
        var endpoint: Messenger? = null
        var result: JSONObject? = null
        var failed = false
        var lastProgress = ""
        val generation = 117
        val incoming =
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    if (message.data.getInt("generation") == generation) {
                        if (message.what == 5) {
                            val snapshot = JSONObject(message.data.getString("snapshot").orEmpty())
                            val progress =
                                "${snapshot.optJSONArray("devices")?.length() ?: 0}:${snapshot.optBoolean("complete") }"
                            if (progress != lastProgress) {
                                lastProgress = progress
                                instrumentation.sendStatus(
                                    0,
                                    Bundle().apply {
                                        putString("googleProbePhase", "snapshot")
                                        putInt(
                                            "googleDeviceCount",
                                            snapshot.optJSONArray("devices")?.length() ?: 0,
                                        )
                                        putBoolean(
                                            "googleComplete",
                                            snapshot.optBoolean("complete"),
                                        )
                                    },
                                )
                            }
                            if (snapshot.optBoolean("complete")) {
                                result = snapshot
                                finished.countDown()
                            }
                        } else if (message.what == 6) {
                            failed = true
                            finished.countDown()
                        }
                    }
                    true
                }
            )
        fun send(code: Int) {
            endpoint?.send(
                Message.obtain(null, code).apply {
                    replyTo = incoming
                    data =
                        Bundle().apply {
                            putInt("generation", generation)
                            putInt("index", -1)
                        }
                }
            )
        }
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    endpoint = Messenger(binder)
                    instrumentation.sendStatus(
                        0,
                        Bundle().apply { putString("googleProbePhase", "bound") },
                    )
                    send(2)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    failed = true
                    finished.countDown()
                }
            }
        assertTrue(
            context.bindService(
                Intent()
                    .setClassName(
                        context.packageName,
                        "dev.findbridge.android.GoogleLocationService",
                    ),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        )
        try {
            assertTrue(
                "Signed-release Google refresh must finish",
                finished.await(100, TimeUnit.SECONDS),
            )
            assertFalse("A read-only refresh must not need new Google authentication", failed)
            val snapshot = requireNotNull(result)
            assertTrue(snapshot.optBoolean("verified"))
            assertTrue(
                "The Web session must match the verified GMS account",
                expectedAccount.equals(snapshot.optString("account"), true),
            )
            val devices = snapshot.getJSONArray("devices")
            val located =
                (0 until devices.length()).count { index ->
                    val device = devices.getJSONObject(index)
                    val lat = device.optDouble("latitude", Double.NaN)
                    val lon = device.optDouble("longitude", Double.NaN)
                    lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
                }
            assertTrue("A real Google catalog must be returned", devices.length() > 0)
            assertTrue("The owner session must return a real position", located > 0)
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putInt("googleDeviceCount", devices.length())
                    putInt("googleLocatedCount", located)
                    putBoolean("googleComplete", true)
                    putBoolean("googleAccountMatches", true)
                    val records = (0 until devices.length()).map { devices.getJSONObject(it) }
                    putInt("googleModelCount", records.count { it.optString("model").isNotBlank() })
                    putBoolean(
                        "pixel9ModelReturned",
                        records.any {
                            it.optString("model").contains("Pixel 9", true)
                        },
                    )
                    for (field in
                        listOf(
                            "imei",
                            "meid",
                            "manufacturer",
                            "firstRegistered",
                            "batteryState",
                            "network",
                            "carrier",
                        )) {
                        putInt(
                            "googleField_$field",
                            records.count {
                                it.optJSONObject("details")?.optString(field).orEmpty().isNotBlank()
                            },
                        )
                    }
                },
            )
        } finally {
            instrumentation.runOnMainSync {
                send(4)
                context.unbindService(connection)
            }
        }
    }
}
