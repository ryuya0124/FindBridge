package dev.findbridge.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FamilyMetadataLiveTest {
    @Test
    fun accountReturnsFamilyMetadata() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val finished = CountDownLatch(1)
        lateinit var repository: AppleRepository
        val listener = AppleRepository.Listener { if (!repository.loading) finished.countDown() }
        instrumentation.runOnMainSync {
            repository = AppleRepository.get(instrumentation.targetContext)
            assertTrue(repository.hasSession)
            repository.addListener(listener)
            repository.refresh()
        }
        assertTrue(finished.await(40, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { repository.removeListener(listener) }
        assertTrue(repository.nativeTransportVerified)
        assertTrue(
            "This account should include family members",
            repository.familyMembers.isNotEmpty(),
        )
        assertTrue(
            "Family devices must be matched to their owners",
            repository.devices.any { it.family && it.ownerName.isNotEmpty() },
        )
        println(
            "Device count=${repository.devices.size}; family device count=${repository.devices.count { it.family }}"
        )
    }
}
