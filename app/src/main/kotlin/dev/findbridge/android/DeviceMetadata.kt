package dev.findbridge.android

import org.json.JSONObject

/** Optional service-reported information. Missing values stay absent. */
internal data class DeviceMetadata(
    val manufacturer: String = "",
    val modelNumber: String = "",
    val operatingSystem: String = "",
    val serialNumber: String = "",
    val imei: String = "",
    val meid: String = "",
    val carrier: String = "",
    val network: String = "",
    val firstRegistered: String = "",
    val activatedAt: String = "",
    val connectionState: String = "",
    val batteryState: String = "",
) {
    fun rows(): List<Pair<Int, String>> =
        listOf(
                R.string.device_manufacturer to manufacturer,
                R.string.device_model_number to modelNumber,
                R.string.device_os to operatingSystem,
                R.string.device_serial to serialNumber,
                R.string.device_imei to imei,
                R.string.device_meid to meid,
                R.string.device_carrier to carrier,
                R.string.device_network to network,
                R.string.device_first_registered to firstRegistered,
                R.string.device_activated_at to activatedAt,
                R.string.device_connection to connectionState,
            )
            .filter { it.second.isNotBlank() }

    companion object {
        fun fromGoogle(json: JSONObject?): DeviceMetadata =
            DeviceMetadata(
                manufacturer = text(json, "manufacturer"),
                modelNumber = text(json, "modelNumber"),
                operatingSystem = text(json, "operatingSystem"),
                serialNumber = text(json, "serialNumber"),
                imei = text(json, "imei"),
                meid = text(json, "meid"),
                carrier = text(json, "carrier"),
                network = text(json, "network"),
                firstRegistered = text(json, "firstRegistered"),
                activatedAt = text(json, "activatedAt"),
                connectionState = text(json, "connectionState"),
                batteryState = text(json, "batteryState"),
            )

        fun fromApple(json: JSONObject): DeviceMetadata =
            DeviceMetadata(
                manufacturer = text(json, "manufacturer"),
                modelNumber = text(json, "rawDeviceModel"),
                operatingSystem = text(json, "osVersion"),
                serialNumber = text(json, "serialNumber"),
                imei = text(json, "imei"),
                meid = text(json, "meid"),
                batteryState =
                    when (text(json, "batteryStatus").lowercase()) {
                        "charging" -> "charging"
                        "charged",
                        "full" -> "full"
                        "notcharging",
                        "not_charging" -> "not_charging"
                        else -> ""
                    },
            )

        private fun text(json: JSONObject?, key: String): String =
            (json?.opt(key) as? String)
                ?.trim()
                ?.take(200)
                ?.takeIf { value -> value.none { it.isISOControl() && it != '\n' } }
                .orEmpty()
    }
}
