package dev.findbridge.verification

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Opt-in empty-emulator test of the published rc10 -> stable update, without synthetic metadata.
 */
class ReleasePublicUpdateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs
        get() = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    @Before
    fun disposableOnly() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedPublicUpdateFixture") ==
                "true"
        )
        assertFalse(
            context.getSharedPreferences("apple_credentials_policy", 0).getBoolean("saved", false)
        )
        assertFalse(context.getSharedPreferences("apple_session", 0).contains("data"))
    }

    @Test
    fun downloadsThePublishedStableUpdateFromTheExistingRelease() {
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("3.3.0-rc10", installed.versionName)
        prefs
            .edit()
            .clear()
            .putBoolean("automatic", true)
            .putBoolean("previews", true)
            .putInt("intervalHours", 24)
            .commit()
        context
            .getSharedPreferences("public_update_fixture", 0)
            .edit()
            .putString("marker", "keep-public-update-data")
            .commit()
        val main =
            instrumentation.startActivitySync(
                Intent()
                    .setClassName(
                        context.packageName,
                        "dev.findbridge.android.MainActivity",
                    )
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        try {
            val end = android.os.SystemClock.uptimeMillis() + 90_000
            while (
                prefs.getString("phase", "") !in listOf("READY", "FAILED") &&
                    android.os.SystemClock.uptimeMillis() < end
            ) android.os.SystemClock.sleep(100)
            assertEquals(
                "The public GitHub APK must download and verify",
                "READY",
                prefs.getString("phase", ""),
            )
            assertTrue(File(context.filesDir, "updates/ready.apk").isFile)
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
        context
            .getSystemService(JobScheduler::class.java)
            .schedule(
                JobInfo.Builder(
                        INSTALL_JOB,
                        ComponentName(
                            context.packageName,
                            "dev.findbridge.android.update.UpdateJobService",
                        ),
                    )
                    .setMinimumLatency(60_000)
                    .build()
            )
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("publishedUpdate", "downloaded-and-verified") },
        )
    }

    @Test
    fun stableUpdateKeepsSettingsAndCleansItsDownloadedApk() {
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("3.3.0", installed.versionName)
        assertEquals(18L, installed.longVersionCode)
        assertEquals(
            "keep-public-update-data",
            context.getSharedPreferences("public_update_fixture", 0).getString("marker", null),
        )
        assertEquals(24, prefs.getInt("intervalHours", -1))
        assertTrue(prefs.getBoolean("previews", false))
        assertTrue(prefs.getBoolean("automatic", false))
        val end = android.os.SystemClock.uptimeMillis() + 5_000
        while (
            prefs.getString("phase", "") != "IDLE" && android.os.SystemClock.uptimeMillis() < end
        ) android.os.SystemClock.sleep(50)
        assertEquals("IDLE", prefs.getString("phase", ""))
        assertEquals(0, prefs.getInt("sessionId", 0))
        assertFalse(File(context.filesDir, "updates/ready.apk").exists())
    }

    companion object {
        private const val INSTALL_JOB = 0x16fb0202
    }
}
