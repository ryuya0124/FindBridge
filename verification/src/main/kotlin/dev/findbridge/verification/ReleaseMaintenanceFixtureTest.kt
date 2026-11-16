package dev.findbridge.verification

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Check packaged translations and R8 UI on an explicitly disposable emulator. */
class ReleaseMaintenanceFixtureTest {
    @Test
    fun allEightPackagedLocalesAndTheNativeScreenRemainUsable() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString("isolatedReleaseMaintenanceFixture") ==
                "true"
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse(
            context.getSharedPreferences("apple_credentials_policy", 0).getBoolean("saved", false)
        )
        assertFalse(context.getSharedPreferences("apple_session", 0).contains("data"))
        val devices = context.resources.getIdentifier("devices", "string", context.packageName)
        assertNotEquals(0, devices)
        val expected =
            mapOf(
                "en" to "Devices",
                "ja" to "デバイス",
                "zh-Hans" to "设备",
                "zh-Hant" to "裝置",
                "ko" to "기기",
                "es" to "Dispositivos",
                "fr" to "Appareils",
                "de" to "Geräte",
            )
        for ((tag, label) in expected) {
            val configuration =
                Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(tag))
                }
            assertEquals(
                tag,
                label,
                context.createConfigurationContext(configuration).getString(devices),
            )
        }
        val activity =
            instrumentation.startActivitySync(
                Intent()
                    .setClassName(context.packageName, "dev.findbridge.android.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        try {
            instrumentation.runOnMainSync {
                assertFalse(activity.isFinishing)
                assertFalse(hasWebView(activity.window.decorView))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun hasWebView(view: View): Boolean =
        view is WebView ||
            (view is ViewGroup && (0 until view.childCount).any { hasWebView(view.getChildAt(it)) })
}
