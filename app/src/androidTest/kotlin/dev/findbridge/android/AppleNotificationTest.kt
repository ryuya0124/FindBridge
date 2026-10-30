package dev.findbridge.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppleNotificationTest {
    @Test
    fun notificationRequiresBothCapabilitiesAndConfirmsOnlyTheTargetFlag() {
        val record =
            JSONObject()
                .put("id", "fixture-device")
                .put("features", JSONObject().put("NWF", true).put("LKL", true))
        val device = AppleDevice.fromJson(record)
        assertTrue(device.notifyWhenFoundSupported)
        val body =
            AppleNotification.body(
                device,
                true,
                JSONObject().put("serverContext", JSONObject().put("fixture", true)),
            )
        assertEquals("fixture-device", body.getString("device"))
        assertTrue(body.getBoolean("locFoundEnabled"))
        assertTrue(body.getJSONObject("serverContext").getBoolean("fixture"))
        assertFalse(body.has("lostModeEnabled"))
        assertFalse(body.has("playSound"))
        val response =
            JSONObject()
                .put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("id", "fixture-device").put("locFoundEnabled", true)),
                )
        assertEquals(
            AppleNotification.Result.SAVED,
            AppleNotification.result(response, "fixture-device", true),
        )
        assertEquals(
            AppleNotification.Result.UNKNOWN,
            AppleNotification.result(response, "other-device", true),
        )
        assertEquals(
            AppleNotification.Result.UNKNOWN,
            AppleNotification.result(response, "fixture-device", false),
        )
        response.getJSONArray("content").getJSONObject(0).put("locFoundEnabled", JSONObject())
        assertEquals(
            AppleNotification.Result.UNKNOWN,
            AppleNotification.result(response, "fixture-device", false),
        )
        assertEquals(
            AppleNotification.Result.AUTH_REQUIRED,
            AppleNotification.result(JSONObject().put("statusCode", "450"), "fixture-device", true),
        )
        record.getJSONObject("features").put("LKL", false)
        assertFalse(AppleDevice.fromJson(record).notifyWhenFoundSupported)
        assertThrows(IllegalArgumentException::class.java) {
            AppleNotification.body(device.copy(provider = DeviceProvider.GOOGLE), true, null)
        }
    }
}
