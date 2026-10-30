package dev.findbridge.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GoogleAccountIsolationTest {
    @Test
    fun accountSwitchDropsPreviousDevicesAndRejectsMismatchedWebSessions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        // Separate preference files preserve the owner's identity and active cookies.
        val prefix = "test-google-isolation-"
        val isolated =
            object : ContextWrapper(target) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(name: String, mode: Int) =
                    target.getSharedPreferences(prefix + name, mode)
            }
        val constructor =
            GoogleRepository::class.java.getDeclaredConstructor(Context::class.java).apply {
                isAccessible = true
            }
        val repository = constructor.newInstance(isolated)
        fun identity(subject: String, email: String) {
            val done = CountDownLatch(1)
            var saved = false
            instrumentation.runOnMainSync {
                repository.acceptIdentity(GoogleIdentity(subject, email)) {
                    saved = it
                    done.countDown()
                }
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(saved)
        }
        fun catalog(email: String, name: String) =
            JSONObject()
                .put("verified", true)
                .put("complete", true)
                .put("account", email)
                .put("devices", JSONArray().put(JSONObject().put("index", 0).put("name", name)))
                .toString()
        try {
            identity("fixture-a", "first@example.invalid")
            instrumentation.runOnMainSync {
                repository.accept(catalog("first@example.invalid", "First fixture"))
                assertTrue(repository.connected)
                assertEquals("First fixture", repository.devices.single().name)
                repository.accept(catalog("second@another.example", "Wrong fixture"))
                assertEquals("First fixture", repository.devices.single().name)
                val full = JSONObject(catalog("first@example.invalid", "First fixture"))
                full
                    .getJSONArray("devices")
                    .getJSONObject(0)
                    .put("latitude", 35)
                    .put("longitude", 139)
                full
                    .getJSONArray("devices")
                    .put(
                        JSONObject()
                            .put("index", 1)
                            .put("name", "Other fixture")
                            .put("latitude", 36)
                            .put("longitude", 140)
                    )
                repository.accept(full.toString())
                val partial = JSONObject(full.toString()).put("requestedIndex", 0)
                partial.getJSONArray("devices").getJSONObject(0).put("latitude", 35.5)
                partial.getJSONArray("devices").getJSONObject(1).remove("latitude")
                partial.getJSONArray("devices").getJSONObject(1).remove("longitude")
                repository.accept(partial.toString())
                assertEquals(35.5, repository.devices[0].latitude!!, 0.0)
                assertEquals(36.0, repository.devices[1].latitude!!, 0.0)
            }
            identity("fixture-b", "second@another.example")
            instrumentation.runOnMainSync {
                assertTrue(repository.signedIn)
                assertFalse(repository.connected)
                assertTrue(repository.devices.isEmpty())
                repository.accept(catalog("first@example.invalid", "Late old fixture"))
                assertFalse(repository.connected)
                assertTrue(repository.devices.isEmpty())
                repository.accept(catalog("SECOND@ANOTHER.EXAMPLE", "Second fixture"))
                assertTrue(repository.connected)
                assertEquals("Second fixture", repository.devices.single().name)
            }
        } finally {
            val worker =
                GoogleRepository::class
                    .java
                    .getDeclaredField("worker")
                    .apply { isAccessible = true }
                    .get(repository) as ExecutorService
            worker.shutdown()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
            target.deleteSharedPreferences(prefix + "google_identity")
            target.deleteSharedPreferences(prefix + "google_connection")
        }
    }
}
