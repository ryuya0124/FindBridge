package dev.findbridge.android

import android.content.Context
import org.json.JSONObject

internal enum class DeviceProvider {
    APPLE,
    GOOGLE,
    SHARED,
}

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
    val ownerId: String = "",
    val ownerName: String = "",
    val locationEnabled: Boolean = true,
    val provider: DeviceProvider = DeviceProvider.APPLE,
    val reportedStatus: String = "",
    val imageUrl: String = "",
    val metadata: DeviceMetadata = DeviceMetadata(),
    val lostModeSupported: Boolean = false,
    val lostModeState: LostModeState = LostModeState.OFF,
    val lostModeNeedsPasscode: Boolean = false,
    val lostModePasscodeLength: Int = 6,
    val lostModeMessageLimit: Int = 140,
    val soundSupported: Boolean = false,
    val soundPlaying: Boolean = false,
    val notifyWhenFoundSupported: Boolean = false,
    val notifyWhenFound: Boolean = false,
) {
    val located: Boolean
        get() = latitude != null && longitude != null

    val canPlaySound: Boolean
        get() =
            !kind.contains("pencil", true) &&
                (provider == DeviceProvider.APPLE ||
                    (provider == DeviceProvider.GOOGLE && soundSupported))

    enum class State {
        LOCATION_OFF,
        NO_LOCATION,
        OFFLINE,
        UNKNOWN_TIME,
        NOW,
        MINUTES,
        HOURS,
        DAYS,
    }

    fun status(now: Long = System.currentTimeMillis()): Pair<State, Long> {
        if (!located)
            return (when {
                !locationEnabled -> State.LOCATION_OFF
                online -> State.NO_LOCATION
                else -> State.OFFLINE
            }) to 0L
        if (timestamp <= 0) return State.UNKNOWN_TIME to 0L
        val minutes = ((now - timestamp) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 2 -> State.NOW to 0L
            minutes < 60 -> State.MINUTES to minutes
            minutes < 1440 -> State.HOURS to minutes / 60
            else -> State.DAYS to minutes / 1440
        }
    }

    fun statusText(context: Context, now: Long = System.currentTimeMillis()): String {
        val (state, count) = status(now)
        val plural =
            when (state) {
                State.MINUTES -> R.plurals.minutes_ago
                State.HOURS -> R.plurals.hours_ago
                State.DAYS -> R.plurals.days_ago
                else -> 0
            }
        if (plural != 0) {
            val amount = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return context.resources.getQuantityString(plural, amount, amount)
        }
        return context.getString(
            when (state) {
                State.LOCATION_OFF -> R.string.location_off
                State.NO_LOCATION -> R.string.no_location
                State.OFFLINE -> R.string.offline
                State.UNKNOWN_TIME -> R.string.unknown_time
                else -> R.string.just_now
            }
        )
    }

    fun displayName(context: Context) = name.ifBlank {
        context.getString(
            if (kind == "Person") R.string.unnamed
            else if ("tag" in kind.lowercase()) R.string.items else R.string.devices
        )
    }

    fun subtitle(context: Context): String =
        reportedStatus
            .ifBlank { address }
            .ifBlank {
                if (located) context.getString(R.string.located_status, statusText(context))
                else statusText(context)
            }

    fun batteryText(): String =
        battery
            ?.let {
                (if (charging) "⚡ " else "") + "${(it * 100).toInt()}%"
            }
            .orEmpty()

    companion object {
        fun fromJson(
            json: JSONObject,
            members: Map<String, FamilyMember> = emptyMap(),
            imageBaseUrl: String = "",
        ): AppleDevice {
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
            val ownerId = json.optString("prsId")
            val owner = members[ownerId]
            return AppleDevice(
                id = json.optString("id"),
                name = json.optString("name"),
                model = json.optString("deviceDisplayName", json.optString("rawDeviceModel")),
                kind = json.optString("deviceClass", "iPhone"),
                family = owner != null,
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
                ownerId = ownerId,
                ownerName = owner?.name.orEmpty(),
                locationEnabled = json.optBoolean("locationEnabled", true),
                metadata = DeviceMetadata.fromApple(json),
                lostModeSupported =
                    json.optBoolean("lostModeCapable") ||
                        json.optJSONObject("features")?.optBoolean("LST") == true,
                lostModeState = LostMode.state(json),
                lostModeNeedsPasscode = json.optJSONObject("features")?.optBoolean("KPD") == true,
                lostModePasscodeLength =
                    json.optInt("passcodeLength", 6).takeIf { it in 4..12 } ?: 6,
                lostModeMessageLimit =
                    json.optInt("maxMsgChar", 140).takeIf { it in 1..500 } ?: 140,
                notifyWhenFoundSupported =
                    json.optJSONObject("features")?.let {
                        it.optBoolean("NWF") && it.optBoolean("LKL")
                    } == true,
                notifyWhenFound = json.optBoolean("locFoundEnabled"),
                imageUrl =
                    DeviceImageUrls.appleDevice(
                        imageBaseUrl,
                        json.optString("deviceClass"),
                        json.optString("rawDeviceModel"),
                        if (json.isNull("deviceColor")) "" else json.optString("deviceColor"),
                        json.optString("deviceStatus") == "200",
                    ),
            )
        }
    }
}
