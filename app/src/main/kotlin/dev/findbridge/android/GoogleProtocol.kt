package dev.findbridge.android

import android.net.Uri
import java.security.MessageDigest
import org.json.JSONObject

internal object GoogleProtocol {
    const val FIND_URL = "https://www.google.com/android/find/"
    const val MAX_SNAPSHOT_CHARS = 240_000
    const val REGISTER = 1
    const val REFRESH = 2
    const val CLEAR = 3
    const val STOP = 4
    const val DATA = 5
    const val ERROR = 6
    const val CLEARED = 7
    val dataOrigins = setOf("https://www.google.com")

    fun isDataPage(address: String): Boolean = runCatching {
        val u = Uri.parse(address)
        isPage(address) &&
            u.host == "www.google.com" &&
            u.path.orEmpty().trimEnd('/') == "/android/find"
    }
        .getOrDefault(false)

    fun isPage(address: String): Boolean = runCatching {
        val u = Uri.parse(address)
        u.scheme == "https" &&
            u.userInfo == null &&
            (u.port == -1 || u.port == 443) &&
            (u.host == "accounts.google.com" ||
                u.host == "myaccount.google.com" ||
                (u.host == "www.google.com" &&
                    u.path.orEmpty().trimEnd('/') in setOf("/android/find", "/android/find/about")))
    }
        .getOrDefault(false)

    fun devices(snapshot: JSONObject): List<AppleDevice> {
        val records = snapshot.optJSONArray("devices") ?: return emptyList()
        require(records.length() <= 500)
        val account = snapshot.optString("account")
        return (0 until records.length()).mapNotNull { i ->
            val r = records.optJSONObject(i) ?: return@mapNotNull null
            val name = r.optString("name").take(200)
            val slot = r.optInt("index", -1)
            if (name.isBlank() || slot !in 0..499) return@mapNotNull null
            val id =
                MessageDigest.getInstance("SHA-256")
                    .digest("$account:$slot:$name".toByteArray())
                    .take(12)
                    .joinToString("") { "%02x".format(it) }
            val lat =
                r.optDouble("latitude", Double.NaN).takeIf { it.isFinite() && it in -90.0..90.0 }
            val lon =
                r.optDouble("longitude", Double.NaN).takeIf { it.isFinite() && it in -180.0..180.0 }
            AppleDevice(
                id = "google:$slot:$id",
                name = name,
                model = "Google Find Hub",
                kind = "Android",
                family = false,
                latitude = if (lon != null) lat else null,
                longitude = if (lat != null) lon else null,
                accuracy = r.optDouble("accuracy", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                timestamp = r.optLong("timestamp", 0).coerceAtLeast(0),
                online = r.optBoolean("online", false),
                battery = r.optDouble("battery", -1.0).takeIf { it in 0.0..100.0 }?.div(100),
                charging = r.optBoolean("charging", false),
                address = "",
                provider = DeviceProvider.GOOGLE,
                reportedStatus = r.optString("status").take(200),
            )
        }
    }
}
