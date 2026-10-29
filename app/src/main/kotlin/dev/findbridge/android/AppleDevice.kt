package dev.findbridge.android

import org.json.JSONObject

internal data class AppleDevice(
    val id: String,
    val name: String,
    val model: String,
    val kind: String,
    val family: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Double,
    val timestamp: Long,
    val online: Boolean,
    val battery: Double?,
    val charging: Boolean,
    val address: String,
) {
    val located: Boolean
        get() = latitude != null && longitude != null

    fun status(now: Long = System.currentTimeMillis()): String {
        if (!located) return if (online) "位置情報が見つかりません" else "オフライン"
        val minutes = ((now - timestamp) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 2 -> "たった今"
            minutes < 60 -> "${minutes}分前"
            minutes < 1440 -> "${minutes / 60}時間前"
            else -> "${minutes / 1440}日前"
        }
    }

    fun subtitle(): String = address.ifBlank {
        if (located) "位置情報を取得済み · ${status()}" else status()
    }

    fun batteryText(): String =
        battery
            ?.let {
                (if (charging) "⚡ " else "") + "${(it * 100).toInt()}%"
            }
            .orEmpty()

    companion object {
        fun fromJson(json: JSONObject): AppleDevice {
            val location = json.optJSONObject("location")
            val lat =
                location?.optDouble("latitude", Double.NaN)?.takeIf {
                    it.isFinite() && it in -90.0..90.0
                }
            val lon =
                location?.optDouble("longitude", Double.NaN)?.takeIf {
                    it.isFinite() && it in -180.0..180.0
                }
            val address = json.optJSONObject("address") ?: location?.optJSONObject("address")
            return AppleDevice(
                id = json.optString("id"),
                name = json.optString("name", "デバイス"),
                model = json.optString("deviceDisplayName", json.optString("rawDeviceModel")),
                kind = json.optString("deviceClass", "iPhone"),
                family = json.optBoolean("fmlyShare", false),
                latitude = if (lat != null && lon != null) lat else null,
                longitude = if (lat != null && lon != null) lon else null,
                accuracy = location?.optDouble("horizontalAccuracy", 0.0) ?: 0.0,
                timestamp = location?.optLong("timeStamp", 0) ?: 0,
                online = json.optString("deviceStatus") == "200",
                battery =
                    json.optDouble("batteryLevel", -1.0).takeIf {
                        it in 0.0..1.0 && !json.optString("batteryStatus").equals("Unknown", true)
                    },
                charging = json.optString("batteryStatus").equals("Charging", true),
                address =
                    address
                        ?.optString(
                            "formattedAddress",
                            address.optString("streetAddress", address.optString("city")),
                        )
                        .orEmpty(),
            )
        }
    }
}
