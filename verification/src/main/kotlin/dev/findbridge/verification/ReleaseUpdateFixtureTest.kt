package dev.findbridge.verification

import android.app.Activity
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.CompoundButton
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Opt-in, empty emulator only. The future APK is local, signed with the same test target key. */
@android.annotation.SuppressLint("ResourceType")
class ReleaseUpdateFixtureTest {
    private val futureVersion
        get() = InstrumentationRegistry.getArguments().getString("fixtureVersionName") ?: "3.3.1"

    private val futureCode
        get() =
            InstrumentationRegistry.getArguments().getString("fixtureVersionCode")?.toLong() ?: 19L

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs
        get() = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    private val destination
        get() = File(context.filesDir, "updates/ready.apk")

    @Before
    fun disposableOnly() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedReleaseUpdateFixture") ==
                "true"
        )
        assertFalse(
            context.getSharedPreferences("apple_credentials_policy", 0).getBoolean("saved", false)
        )
        assertFalse(context.getSharedPreferences("apple_session", 0).contains("data"))
    }

    @Test
    fun rejectsTamperedAndOldApksBeforeCreatingAnInstallSession() {
        assertTrue(context.packageManager.canRequestPackageInstalls())
        for (name in listOf("future.apk", "installed.apk", "wrong-signer.apk")) {
            stage(name, badDigest = name == "future.apk")
            val activity = launch()
            try {
                instrumentation.runOnMainSync {
                    activity.findViewById<View>(INSTALL).performClick()
                }
                await { prefs.getString("phase", "") == "FAILED" }
                assertEquals(0, prefs.getInt("sessionId", 0))
                assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Test
    fun opensSystemConfirmationForManualUpdate() {
        stage("future.apk")
        val activity = launch()
        instrumentation.runOnMainSync { activity.findViewById<View>(INSTALL).performClick() }
        await {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let {
                it.contains("packageinstaller") || it.contains("permissioncontroller")
            } == true
        }
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("manualUpdate", "system-confirmation-visible") },
        )
        // The host confirms after this runner finishes, because self-update terminates its process.
    }

    @Test
    fun preparesAutomaticUpdateForBackgroundExecution() {
        stage("future.apk")
        prefs.edit().putBoolean("automatic", true).commit()
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
            Bundle().apply { putString("automaticUpdate", "staged-for-background-job") },
        )
    }

    @Test
    fun updatedPackagePreservesDataAndClearsTheOldUpdate() {
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertEquals(futureCode, version)
        assertEquals(
            "retained-through-update",
            context.getSharedPreferences("update_fixture_marker", 0).getString("marker", null),
        )
        await { prefs.getString("phase", "") == "IDLE" && prefs.getInt("sessionId", 0) == 0 }
        assertFalse(destination.exists())
    }

    @Test
    fun nativeSettingsKeepChoicesAndShowLocalizedStatus() {
        prefs
            .edit()
            .clear()
            .putBoolean("automatic", false)
            .putBoolean("previews", false)
            .putString("phase", "CURRENT")
            .commit()
        val appearance = context.getSharedPreferences("appearance", 0)
        val previousTheme = appearance.getString("theme", "SYSTEM")
        InstrumentationRegistry.getArguments().getString("fixtureTheme")?.let {
            appearance.edit().putString("theme", it).commit()
        }
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                assertFalse(activity.findViewById<CompoundButton>(AUTOMATIC).isChecked)
                assertNotNull(activity.findViewById<View>(PREVIEWS))
                assertFalse(prefs.getBoolean("previews", true))
                activity.findViewById<CompoundButton>(AUTOMATIC).performClick()
                assertNotNull(
                    context.getSystemService(JobScheduler::class.java).getPendingJob(CHECK_JOB)
                )
                activity.findViewById<CompoundButton>(AUTOMATIC).performClick()
                assertFalse(prefs.getBoolean("automatic", true))
            }
            await { prefs.getString("phase", "") != "CHECKING" }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                activity.findViewById<View>(FREQUENCY).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val menu = choices()
                assertEquals(4, menu.childCount)
                assertEquals(
                    1,
                    (0 until menu.childCount).count {
                        menu.getChildAt(it).createAccessibilityNodeInfo().isChecked
                    },
                )
            }
            // Wait for the popup's first layout and fade to reach the compositor before capture.
            await {
                var visible = false
                instrumentation.runOnMainSync {
                    val menu = choices()
                    visible =
                        menu.width > 0 &&
                            menu.isShown &&
                            generateSequence(menu as View) { it.parent as? View }
                                .all {
                                    it.alpha >= .99f
                                }
                }
                visible
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "update-settings.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            instrumentation.runOnMainSync { choices().getChildAt(3).performClick() }
            instrumentation.waitForIdleSync()
            assertEquals(24, prefs.getInt("intervalHours", -1))
            instrumentation.runOnMainSync {
                activity.findViewById<View>(PREVIEWS).performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(2, choices().childCount)
                choices().getChildAt(0).performClick()
            }
            assertFalse(prefs.getBoolean("previews", true))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            appearance.edit().putString("theme", previousTheme).commit()
        }
    }

    private fun choices(): LinearLayout {
        val id = context.resources.getIdentifier("display_choices", "id", context.packageName)
        assertNotEquals(0, id)
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
            it.findViewById<LinearLayout>(id)
        } ?: error("No glass choice menu in the signed release")
    }

    private fun stage(name: String, badDigest: Boolean = false) {
        context.packageManager.packageInstaller.mySessions.forEach {
            runCatching { context.packageManager.packageInstaller.abandonSession(it.sessionId) }
        }
        val source = File(context.getExternalFilesDir(null), name)
        assertTrue("Host must push the signed fixture APK", source.isFile)
        destination.parentFile!!.mkdirs()
        destination.delete()
        source.copyTo(destination)
        val digest =
            if (badDigest) "0".repeat(64)
            else
                MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") {
                    "%02x".format(it)
                }
        val release =
            JSONObject()
                .put("version", futureVersion)
                .put(
                    "url",
                    "https://github.com/ryuya0124/FindBridge/releases/download/v$futureVersion/FindBridge-$futureVersion-arm64-release.apk",
                )
                .put("sha256", digest)
                .put("size", source.length())
                .put("notes", "Local update fixture")
        prefs
            .edit()
            .clear()
            .putBoolean("automatic", false)
            .putString("phase", "READY")
            .putString("release", release.toString())
            .commit()
        context
            .getSharedPreferences("update_fixture_marker", 0)
            .edit()
            .putString("marker", "retained-through-update")
            .commit()
    }

    private fun launch(): Activity =
        instrumentation.startActivitySync(
            Intent()
                .setClassName(
                    context.packageName,
                    "dev.findbridge.android.update.UpdateActivity",
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    private fun await(predicate: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 15_000
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            android.os.SystemClock.sleep(50)
        }
        assertTrue("Expected update state was not reached", predicate())
    }

    companion object {
        private const val AUTOMATIC = 0x16fb0211
        private const val PREVIEWS = 0x16fb0212
        private const val FREQUENCY = 0x16fb0216
        private const val INSTALL = 0x16fb0213
        private const val CHECK_JOB = 0x16fb0201
        private const val INSTALL_JOB = 0x16fb0202
    }
}
