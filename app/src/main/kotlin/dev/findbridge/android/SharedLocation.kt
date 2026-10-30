package dev.findbridge.android

import org.json.JSONObject

internal enum class LocationCategory {
    PEOPLE,
    ITEMS,
}

internal data class SharedLocation(
    val id: String,
    val name: String,
    val category: LocationCategory,
    val kind: String,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long,
    val accuracy: Double,
    val emoji: String,
    val model: String,
    val ownerName: String,
    val address: String,
    val source: String,
) {
    val located
        get() = latitude != null && longitude != null

    val pencil
        get() = listOf(kind, model, name).any { "pencil" in it.lowercase() }

    fun asDevice() =
        AppleDevice(
            id,
            name,
            model,
            if (category == LocationCategory.PEOPLE) "Person" else if (pencil) "Pencil" else kind,
            false,
            latitude,
            longitude,
            accuracy,
            timestamp,
            located,
            null,
            false,
            address,
            ownerName = ownerName,
            provider = DeviceProvider.SHARED,
        )

    companion object {
        fun parse(
            json: JSONObject,
            category: LocationCategory,
            defaultSource: String,
        ): SharedLocation? {
            val id = json.optString("id", json.optString("identifier", json.optString("baUUID")))
            if (id.isBlank()) return null
            val location =
                json.optJSONObject("location") ?: json.optJSONObject("crowdSourcedLocation") ?: json
            val lat =
                location.optDouble("latitude", Double.NaN).takeIf {
                    it.isFinite() && it in -90.0..90.0
                }
            val lon =
                location.optDouble("longitude", Double.NaN).takeIf {
                    it.isFinite() && it in -180.0..180.0
                }
            val rawTime = location.optDouble("timeStamp", location.optDouble("timestamp", 0.0))
            val time =
                if (rawTime in 1.0..10_000_000_000.0) (rawTime * 1000).toLong()
                else rawTime.toLong()
            return SharedLocation(
                id,
                json.optString("name"),
                category,
                json.optString(
                    "kind",
                    json.optString(
                        "deviceClass",
                        json.optString(
                            "rawDeviceModel",
                            if (category == LocationCategory.PEOPLE) "Person" else "Tag",
                        ),
                    ),
                ),
                if (lat != null && lon != null) lat else null,
                if (lat != null && lon != null) lon else null,
                time,
                location.optDouble("horizontalAccuracy", 0.0),
                json.optString("emoji"),
                json.optString("model", json.optString("deviceDisplayName")),
                json.optString("ownerName"),
                json.optString("address"),
                json.optString("source", defaultSource),
            )
        }
    }
}
