package dev.findbridge.android

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only, explicit opt-in. Never opens or operates a Google authentication screen. */
class GoogleNativeCatalogLiveTest {
    @Test
    fun existingGoogleSessionRefreshesAndStopsLoadingInTheNativeScreen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("googleread") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = GoogleRepository.get(instrumentation.targetContext)
        assertTrue(
            "The owner must already have completed Google identity and Web sign-in",
            repository.signedIn && repository.connected,
        )
        val complete = CountDownLatch(1)
        val listener = AppleRepository.Listener {
            if (!repository.loading && repository.devices.isNotEmpty()) complete.countDown()
        }
        instrumentation.runOnMainSync { repository.addListener(listener) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(
                    "The real catalog must finish its read-only refresh",
                    complete.await(100, TimeUnit.SECONDS),
                )
                assertFalse(repository.loading)
                assertFalse(repository.needsSignIn)
                assertTrue(repository.devices.isNotEmpty())
                assertTrue(
                    "The owner session must return at least one real position",
                    repository.devices.any { it.located },
                )
                scenario.onActivity { activity ->
                    MainActivity::class
                        .java
                        .getDeclaredMethod("switchTab", Int::class.javaPrimitiveType)
                        .apply { isAccessible = true }
                        .invoke(activity, 1)
                    fun containsLabel(view: View, text: String): Boolean =
                        view is TextView && view.text.toString() == text ||
                            view is ViewGroup &&
                                (0 until view.childCount).any {
                                    containsLabel(view.getChildAt(it), text)
                                }
                    assertTrue(
                        "Real Google devices must appear as native labels",
                        containsLabel(activity.window.decorView, repository.devices.first().name),
                    )
                    fun hasWebView(view: View): Boolean =
                        view is android.webkit.WebView ||
                            view is ViewGroup &&
                                (0 until view.childCount).any { hasWebView(view.getChildAt(it)) }
                    assertFalse(hasWebView(activity.window.decorView))
                    val markers =
                        MainActivity::class
                            .java
                            .getDeclaredField("markerById")
                            .apply { isAccessible = true }
                            .get(activity) as Map<*, *>
                    assertTrue(
                        "Google positions must be represented by native map markers",
                        repository.devices
                            .filter { it.located }
                            .all { device ->
                                device.id in markers ||
                                    (MainActivity::class
                                            .java
                                            .getDeclaredField("markerGroups")
                                            .apply { isAccessible = true }
                                            .get(activity) as Map<*, *>)
                                        .values
                                        .any { device.id in (it as List<*>) }
                            },
                    )
                }
                instrumentation.sendStatus(
                    0,
                    Bundle().apply {
                        putInt("googleDeviceCount", repository.devices.size)
                        putInt("googleLocatedCount", repository.devices.count { it.located })
                        putBoolean("googleLoading", repository.loading)
                    },
                )
            }
        } finally {
            instrumentation.runOnMainSync { repository.removeListener(listener) }
        }
    }
}
