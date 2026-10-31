package dev.findbridge.android

import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AuthLoadingDeadlineTest {
    @Test
    fun foregroundWaitExpiresWithoutAnyPageOrJavaScriptEvent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expired = CountDownLatch(1)
        lateinit var deadline: AuthLoadingDeadline
        instrumentation.runOnMainSync {
            deadline = AuthLoadingDeadline(100) { expired.countDown() }
            deadline.start()
            deadline.setActive(true)
        }
        try {
            assertTrue(
                "A missing script must not leave the loading cover indefinitely",
                expired.await(2, TimeUnit.SECONDS),
            )
        } finally {
            instrumentation.runOnMainSync { deadline.stop() }
        }
    }

    @Test
    fun backgroundAndCompletedAuthenticationDoNotExpire() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expired = CountDownLatch(1)
        lateinit var deadline: AuthLoadingDeadline
        instrumentation.runOnMainSync {
            deadline = AuthLoadingDeadline(100) { expired.countDown() }
            deadline.start()
        }
        try {
            assertFalse(expired.await(200, TimeUnit.MILLISECONDS))
            instrumentation.runOnMainSync {
                deadline.setActive(true)
                deadline.stop()
            }
            assertFalse(expired.await(200, TimeUnit.MILLISECONDS))
        } finally {
            instrumentation.runOnMainSync { deadline.stop() }
        }
    }
}
