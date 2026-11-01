package dev.findbridge.android.update

import androidx.test.platform.app.InstrumentationRegistry
import dev.findbridge.android.LoginVault
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit network fixture: downloads the public rc9, then rejects installing that older APK. */
class UpdateDownloadTest {
    @Test
    fun downloadCompletionChecksThePrivateCopyAndRejectsAnOlderRelease() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedUpdateNetworkFixture") ==
                "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse(LoginVault(context).hasSaved)
        val repository = UpdateRepository.get(context)
        // Use the public control path so a preceding activity's queued metadata check cannot
        // overwrite this deliberately older release after the fixture has been staged.
        repository.setAutomatic(false)
        val release =
            UpdateRelease(
                "3.3.0-rc9",
                "https://github.com/ryuya0124/FindBridge/releases/download/v3.3.0-rc9/FindBridge-3.3.0-rc9-arm64-release.apk",
                "d61315e24ec730fd953c1a23d3a966fd15a014b8d1f041f94fe9d999210faef3",
                7_241_301,
                "Public older release fixture",
            )
        val ready = CountDownLatch(1)
        repository.work {
            repository.prefs
                .edit()
                .clear()
                .putBoolean("automatic", false)
                .putString("release", release.json())
                .putString("phase", "AVAILABLE")
                .commit()
            ready.countDown()
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS))
        try {
            repository.download()
            val deadline = android.os.SystemClock.uptimeMillis() + 90_000
            while (
                repository.state.phase != UpdatePhase.FAILED &&
                    android.os.SystemClock.uptimeMillis() < deadline
            ) android.os.SystemClock.sleep(100)
            assertEquals(UpdatePhase.FAILED, repository.state.phase)
            assertEquals(0L, repository.prefs.getLong("downloadId", 0))
            assertFalse(repository.apk.exists())
            assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        } finally {
            val done = CountDownLatch(1)
            repository.cancel()
            repository.work {
                repository.prefs.edit().clear().commit()
                done.countDown()
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
        }
    }
}
