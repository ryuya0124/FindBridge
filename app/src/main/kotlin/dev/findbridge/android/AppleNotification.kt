package dev.findbridge.android

import org.json.JSONObject

internal object AppleRequestBody {
    fun base(sessionBody: JSONObject?): JSONObject {
        val client =
            sessionBody?.optJSONObject("clientContext")?.let { JSONObject(it.toString()) }
                ?: JSONObject()
                    .put("appName", "iCloud Find (Web)")
                    .put("appVersion", "2.0")
                    .put("apiVersion", "3.0")
                    .put("deviceListVersion", 1)
        val result =
            JSONObject().put("clientContext", client.put("fmly", true).put("inactiveTime", 0))
        sessionBody?.optJSONObject("serverContext")?.let {
            result.put("serverContext", JSONObject(it.toString()))
        }
        return result
    }
}

internal object AppleNotification {
    enum class Result {
        SAVED,
        AUTH_REQUIRED,
        FAILED,
        UNKNOWN,
    }

    fun body(device: AppleDevice, enabled: Boolean, sessionBody: JSONObject?): JSONObject {
        require(device.provider == DeviceProvider.APPLE && device.notifyWhenFoundSupported)
        return AppleRequestBody.base(sessionBody)
            .put("device", device.id)
            .put("locFoundEnabled", enabled)
    }

    fun result(response: JSONObject, id: String, enabled: Boolean): Result {
        val code = response.optString("statusCode")
        if (code in setOf("401", "403", "421", "450")) return Result.AUTH_REQUIRED
        if (code.isNotBlank() && code != "200") return Result.FAILED
        if (response.has("alert") && !response.isNull("alert")) return Result.UNKNOWN
        val content = response.optJSONArray("content") ?: return Result.UNKNOWN
        val target =
            (0 until content.length())
                .mapNotNull { content.optJSONObject(it) }
                .firstOrNull { it.optString("id") == id } ?: return Result.UNKNOWN
        return if ((target.opt("locFoundEnabled") as? Boolean) == enabled) Result.SAVED
        else Result.UNKNOWN
    }
}
