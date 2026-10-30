package dev.findbridge.android

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationAndVaultTest {
    @Test
    fun eightLanguagesHaveNativeLabelsAndFormattedRelativeTimes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expected =
            mapOf(
                "ja" to "デバイス",
                "en" to "Devices",
                "zh-Hans" to "设备",
                "zh-Hant" to "裝置",
                "ko" to "기기",
                "es" to "Dispositivos",
                "fr" to "Appareils",
                "de" to "Geräte",
            )
        expected.forEach { (tag, label) ->
            val config =
                Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(tag))
                }
            val localized = context.createConfigurationContext(config)
            assertEquals(label, localized.getString(R.string.devices))
            for (count in listOf(1, 2, 15)) {
                val result =
                    localized.resources.getQuantityString(R.plurals.minutes_ago, count, count)
                assertFalse(result.contains("%"))
                assertTrue(result.isNotEmpty())
            }
            assertTrue(localized.getString(R.string.owner_devices, "TEST").contains("TEST"))
            for (mode in MapMode.entries) {
                val style = org.json.JSONObject(MapStyles.create(localized, mode, false))
                assertTrue(style.getJSONArray("layers").length() > 0)
                if (tag != "ja") assertFalse(style.toString().contains("name:ja"))
            }
        }
    }

    @Test
    fun credentialsAreEncryptedAndTamperingFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "credential_storage_test"
        val vault = LoginVault(context, namespace)
        val login = SavedLogin("fixture@example.invalid", "test-only-secret-not-a-real-password")
        try {
            vault.clear()
            vault.save(login, true)
            val restored = vault.load()!!
            assertEquals(login.account, restored.account)
            assertEquals(login.password, restored.password)
            val xml =
                File(context.applicationInfo.dataDir, "shared_prefs/$namespace.xml").readText()
            assertFalse(xml.contains(login.account))
            assertFalse(xml.contains(login.password))
            assertTrue(vault.automaticAllowed())
            vault.markAttempt()
            assertFalse(vault.automaticAllowed())
            val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
            prefs.edit().putString("data", "AA==").commit()
            assertNull(vault.load())
            assertTrue(vault.paused)
            assertFalse(vault.automaticAllowed())
        } finally {
            vault.clear()
        }
    }
}
