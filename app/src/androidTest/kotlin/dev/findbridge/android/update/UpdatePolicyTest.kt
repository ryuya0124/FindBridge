package dev.findbridge.android.update

import android.app.DownloadManager
import android.app.job.JobScheduler
import android.content.Intent
import android.widget.CompoundButton
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.findbridge.android.LoginVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class UpdatePolicyTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun disposableProfileOnly() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedUpdateFixture") == "true"
        )
        assertFalse(LoginVault(context).hasSaved)
    }

    @Test
    fun catalogSelectsNumericPreviewAndIgnoresDraftsAndOlderVersions() {
        val json =
            JSONArray()
                .put(release("3.3.0-rc9"))
                .put(release("3.3.0-rc11"))
                .put(release("3.3.0-rc12").put("draft", true))
                .put(release("3.3.0-rc10"))
        assertEquals(
            "3.3.0-rc11",
            UpdateRelease.latest(json.toString(), "3.3.0-rc9", true)?.version,
        )
        assertNull(UpdateRelease.latest(json.toString(), "3.3.0-rc11", true))
        assertNull(UpdateRelease.latest(json.toString(), "3.3.0-rc9", false))
        json.put(release("3.3.0", false))
        assertEquals("3.3.0", UpdateRelease.latest(json.toString(), "3.3.0-rc11", false)?.version)
    }

    @Test
    fun untrustedDownloadsOrMissingDigestNeverBecomeAnUpdate() {
        val valid = release("3.3.0-rc11")
        val badAssets =
            listOf(
                asset("3.3.0-rc11").put("browser_download_url", "https://evil.invalid/update.apk"),
                asset("3.3.0-rc11").put("digest", ""),
                asset("3.3.0-rc11").put("digest", "sha256:no"),
                asset("3.3.0-rc11").put("state", "new"),
                asset("3.3.0-rc11").put("size", UpdateRelease.MAX_APK_SIZE + 1),
                asset("3.3.0-rc11").put("name", "FindBridge-debug.apk"),
            )
        badAssets.forEach { a ->
            val json = JSONObject(valid.toString()).put("assets", JSONArray().put(a))
            assertNull(UpdateRelease.latest(JSONArray().put(json).toString(), "3.3.0-rc10", true))
        }
    }

    @Test
    fun stableChannelExcludesPreviewFlagEvenWithStableLookingTag() {
        val json = JSONArray().put(release("3.3.0").put("prerelease", true))
        assertNull(UpdateRelease.latest(json.toString(), "3.2.0", false))
        assertNotNull(UpdateRelease.latest(json.toString(), "3.2.0", true))
    }

    @Test
    fun storedReleaseCannotIntroduceArbitraryFilesOrUrls() {
        val value =
            UpdateRelease.latest(
                JSONArray().put(release("3.3.0-rc11")).toString(),
                "3.3.0-rc10",
                true,
            )!!
        assertEquals(value, UpdateRelease.decode(value.json()))
        assertNull(
            UpdateRelease.decode(
                JSONObject(value.json()).put("url", "file:///data/private.apk").toString()
            )
        )
        assertNull(
            UpdateRelease.decode(JSONObject(value.json()).put("version", "../../bad").toString())
        )
    }

    @Test
    fun unrelatedDownloadBroadcastCannotChangeOurState() {
        val repository = UpdateRepository.get(context)
        val before = repository.state
        UpdateDownloadReceiver()
            .onReceive(
                context,
                Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                    .putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, Long.MAX_VALUE),
            )
        assertEquals(before, repository.state)
    }

    @Test
    fun automaticSwitchControlsPersistentSystemJobAndNativeMaterialSwitch() {
        val repository = UpdateRepository.get(context)
        val previous = repository.automatic
        try {
            repository.setAutomatic(false)
            assertNull(
                context
                    .getSystemService(JobScheduler::class.java)
                    .getPendingJob(UpdateScheduler.CHECK_JOB)
            )
            ActivityScenario.launch(UpdateActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val toggle = activity.findViewById<CompoundButton>(UpdateActivity.AUTOMATIC_ID)
                    assertFalse(toggle.isChecked)
                    toggle.performClick()
                    val job =
                        context
                            .getSystemService(JobScheduler::class.java)
                            .getPendingJob(UpdateScheduler.CHECK_JOB)
                    assertNotNull(job)
                    assertTrue(job!!.isPersisted)
                    assertTrue(job.intervalMillis >= 12 * 60 * 60 * 1000L)
                    activity
                        .findViewById<CompoundButton>(UpdateActivity.AUTOMATIC_ID)
                        .performClick()
                    assertFalse(repository.automatic)
                    assertNull(
                        context
                            .getSystemService(JobScheduler::class.java)
                            .getPendingJob(UpdateScheduler.CHECK_JOB)
                    )
                }
            }
            val settled = java.util.concurrent.CountDownLatch(1)
            repository.work { settled.countDown() }
            assertTrue(settled.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNotEquals(UpdatePhase.CHECKING, repository.state.phase)
        } finally {
            repository.setAutomatic(false)
            repository.prefs.edit().putBoolean("automatic", previous).apply()
            UpdateScheduler.configure(context)
        }
    }

    @Test
    fun frequencyAndChannelChoicesRescheduleAndPersist() {
        val repository = UpdateRepository.get(context)
        val oldAutomatic = repository.automatic
        val oldInterval = repository.intervalHours
        val oldPreviews = repository.previews
        try {
            repository.setInterval(12)
            repository.setAutomatic(true)
            ActivityScenario.launch(UpdateActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    fun select(id: Int, index: Int) {
                        activity.findViewById<android.view.View>(id).performClick()
                        val dialog =
                            UpdateActivity::class
                                .java
                                .getDeclaredField("selection")
                                .apply { isAccessible = true }
                                .get(activity) as android.app.AlertDialog
                        dialog.listView.performItemClick(null, index, index.toLong())
                    }
                    val jobs = context.getSystemService(JobScheduler::class.java)
                    select(UpdateActivity.FREQUENCY_ID, 1)
                    assertEquals(6, repository.intervalHours)
                    assertEquals(
                        6 * 60 * 60 * 1000L,
                        jobs.getPendingJob(UpdateScheduler.CHECK_JOB)!!.intervalMillis,
                    )
                    select(UpdateActivity.FREQUENCY_ID, 0)
                    assertEquals(0, repository.intervalHours)
                    assertNull(jobs.getPendingJob(UpdateScheduler.CHECK_JOB))
                    select(UpdateActivity.FREQUENCY_ID, 3)
                    assertEquals(24, repository.intervalHours)
                    assertEquals(
                        24 * 60 * 60 * 1000L,
                        jobs.getPendingJob(UpdateScheduler.CHECK_JOB)!!.intervalMillis,
                    )
                    select(UpdateActivity.PREVIEWS_ID, 0)
                    assertFalse(repository.previews)
                    select(UpdateActivity.PREVIEWS_ID, 1)
                    assertTrue(repository.previews)
                    assertEquals(
                        24,
                        context.getSharedPreferences("app_updates", 0).getInt("intervalHours", -1),
                    )
                }
            }
        } finally {
            repository.setAutomatic(false)
            repository.prefs
                .edit()
                .putInt("intervalHours", oldInterval)
                .putBoolean("previews", oldPreviews)
                .putBoolean("automatic", oldAutomatic)
                .apply()
            UpdateScheduler.configure(context)
        }
    }

    private fun asset(version: String) =
        JSONObject()
            .put("name", "FindBridge-$version-arm64-release.apk")
            .put(
                "browser_download_url",
                "https://github.com/ryuya0124/FindBridge/releases/download/v$version/FindBridge-$version-arm64-release.apk",
            )
            .put("size", 100)
            .put("digest", "sha256:" + "a".repeat(64))
            .put("state", "uploaded")

    private fun release(version: String, preview: Boolean = true) =
        JSONObject()
            .put("tag_name", "v$version")
            .put("draft", false)
            .put("prerelease", preview)
            .put("assets", JSONArray().put(asset(version)))
}
