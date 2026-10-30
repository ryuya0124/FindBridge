package dev.findbridge.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceMetadataTest {
    @Test
    fun googleMetadataResolvesUnknownNamesWithoutChangingTheirIdentity() {
        val record = JSONObject().put("index", 0).put("name", "不明なデバイス")
        val snapshot =
            JSONObject()
                .put("account", "fixture@example.invalid")
                .put("devices", JSONArray().put(record))
        val before = GoogleProtocol.devices(snapshot).single()
        record
            .put("model", "Google Pixel 9")
            .put("charging", true)
            .put("battery", 54)
            .put(
                "details",
                JSONObject()
                    .put("manufacturer", "Google")
                    .put("imei", "000000000000001")
                    .put("meid", "00000000000001")
                    .put("firstRegistered", "2026/10/1")
                    .put("network", "Fixture Wi-Fi")
                    .put("carrier", "Fixture Carrier")
                    .put("batteryState", "charging"),
            )
        val after = GoogleProtocol.devices(snapshot).single()
        assertEquals(before.id, after.id)
        assertEquals("Google Pixel 9", after.name)
        assertEquals("Google", after.metadata.manufacturer)
        assertEquals("000000000000001", after.metadata.imei) // Deliberately synthetic.
        assertEquals("00000000000001", after.metadata.meid)
        assertEquals("2026/10/1", after.metadata.firstRegistered)
        assertEquals("Fixture Carrier", after.metadata.carrier)
        assertEquals("Fixture Wi-Fi", after.metadata.network)
        assertTrue(after.charging)
        assertEquals(.54, after.battery!!, 0.0)
        record.put("name", "My fixture phone")
        assertEquals("My fixture phone", GoogleProtocol.devices(snapshot).single().name)
    }

    @Test
    fun unavailableFieldsAreAbsentAndJsonNullNeverBecomesDisplayText() {
        assertTrue(DeviceMetadata.fromGoogle(null).rows().isEmpty())
        val metadata =
            DeviceMetadata.fromGoogle(
                JSONObject()
                    .put("imei", JSONObject.NULL)
                    .put("manufacturer", JSONObject())
                    .put("carrier", "bad\u0000value")
                    .put("network", "a".repeat(1000))
            )
        assertEquals("", metadata.imei)
        assertEquals("", metadata.manufacturer)
        assertEquals("", metadata.carrier)
        assertEquals(200, metadata.network.length)
    }
}
