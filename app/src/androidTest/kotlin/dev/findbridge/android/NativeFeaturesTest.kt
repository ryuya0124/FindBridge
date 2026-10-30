package dev.findbridge.android

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeFeaturesTest {
    @Test
    fun familyOwnerUsesPrsIdAndMissingPositionStaysMissing() {
        val members =
            FamilyMember.fromUserInfo(
                JSONObject(
                    """{"membersInfo":{"owner":{"firstName":"Fixture","lastName":"Member"}}}"""
                )
            )
        val device =
            AppleDevice.fromJson(
                JSONObject(
                    """{"id":"fixture","prsId":"owner","fmlyShare":false,"locationEnabled":false}"""
                ),
                members.associateBy { it.id },
            )
        assertTrue(device.family)
        assertEquals("Fixture Member", device.ownerName)
        assertEquals("位置情報がオフ", device.status())
        assertFalse(device.located)
        assertFalse(AppleDevice.fromJson(JSONObject("""{"id":"own","fmlyShare":true}""")).family)
    }

    @Test
    fun nativeTabsLoadOwnerSelectedSnapshotAndPencilGoesInDevices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sync = LocationSyncRepository.get(context)
        assumeFalse("Do not overwrite an existing owner's source", sync.configured)
        val file = File(context.cacheDir, "test-location-fixture.json")
        file.writeText(
            """{"source":"TEST FIXTURE","people":[{"id":"test-person","name":"Fixture Person","location":{"latitude":35,"longitude":139,"timestamp":1700000000}}],"items":[{"id":"test-tag","name":"Fixture AirTag","kind":"Tag"},{"id":"test-pencil","name":"Fixture Apple Pencil Pro","kind":"Pencil"}]}"""
        )
        val loaded = CountDownLatch(1)
        val listener = AppleRepository.Listener { if (!sync.loading) loaded.countDown() }
        try {
            instrumentation.runOnMainSync {
                sync.addListener(listener)
                sync.importFile(Uri.fromFile(file))
            }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            assertEquals(1, sync.people.size)
            assertEquals(1, sync.items.size)
            assertEquals(1, sync.pencils.size)
            assertEquals(1700000000000, sync.people.single().timestamp)
            assertFalse(sync.items.single().located)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    fun find(view: View, predicate: (View) -> Boolean): View? {
                        if (predicate(view)) return view
                        if (view is ViewGroup)
                            for (i in 0 until view.childCount) find(view.getChildAt(i), predicate)
                                ?.let {
                                    return it
                                }
                        return null
                    }
                    fun tab(name: String) {
                        find(activity.window.decorView) { it.contentDescription == name }!!
                            .performClick()
                    }
                    fun label(name: String) =
                        find(activity.window.decorView) {
                            it is TextView && it.text.toString() == name
                        }
                    tab("人を探す")
                    assertNotNull(label("Fixture Person"))
                    (label("Fixture Person")!!.parent.parent as View).performClick()
                    assertNull(activity.findViewById<View>(MainActivity.SOUND_ID))
                    tab("持ち物")
                    assertNotNull(label("Fixture AirTag"))
                    assertNull(label("Fixture Apple Pencil Pro"))
                    tab("デバイス")
                    assertNotNull(label("Fixture Apple Pencil Pro"))
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                sync.removeListener(listener)
                sync.disconnect()
            }
            file.delete()
        }
    }

    @Test
    fun darkAndAerialStylesAreIndependentAndValid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dark = JSONObject(MapStyles.create(context, MapMode.STANDARD, true))
        val light = JSONObject(MapStyles.create(context, MapMode.STANDARD, false))
        assertNotEquals(
            light.getJSONArray("layers").toString(),
            dark.getJSONArray("layers").toString(),
        )
        val aerial = JSONObject(MapStyles.create(context, MapMode.AERIAL, false))
        assertEquals(2, aerial.getJSONArray("layers").length())
        assertEquals(
            "raster",
            aerial.getJSONObject("sources").getJSONObject("gsi-aerial").getString("type"),
        )
        assertTrue(
            JSONObject(MapStyles.create(context, MapMode.HYBRID, true))
                .getJSONArray("layers")
                .length() > 2
        )
    }
}
