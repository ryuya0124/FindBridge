package dev.findbridge.android

import org.json.JSONObject

internal enum class LostModeState {
    OFF,
    PENDING,
    ENABLED,
}

internal enum class LostModeResult {
    REQUESTED,
    AUTH_REQUIRED,
    REJECTED,
    UNKNOWN,
}

/** Owner-entered, transient values. Never persist or log the lock code or contact details. */
internal class LostModeDraft(val phone: String, val message: String, val passcode: String = "") {
    override fun toString() = "LostModeDraft(redacted)"
}

internal object LostMode {
    fun body(
        device: AppleDevice,
        draft: LostModeDraft,
        sessionBody: JSONObject? = null,
    ): JSONObject {
        require(device.provider == DeviceProvider.APPLE && device.lostModeSupported)
        require(device.lostModeState == LostModeState.OFF)
        require(draft.phone.length <= 100 && draft.phone.none { it.isISOControl() })
        require(draft.message.length <= device.lostModeMessageLimit)
        require(draft.message.none { it.isISOControl() && it != '\n' })
        require(
            !device.lostModeNeedsPasscode ||
                (draft.passcode.length == device.lostModePasscodeLength &&
                    draft.passcode.all { it in '0'..'9' })
        )
        require(device.lostModeNeedsPasscode || draft.passcode.isEmpty())
        val clientContext =
            sessionBody?.optJSONObject("clientContext")?.let {
                JSONObject(it.toString())
            }
                ?: JSONObject()
                    .put("appName", "iCloud Find (Web)")
                    .put("appVersion", "2.0")
                    .put("apiVersion", "3.0")
                    .put("deviceListVersion", 1)
        clientContext.put("fmly", true).put("inactiveTime", 0)
        return JSONObject()
            .put("device", device.id)
            .put("lostModeEnabled", true)
            .put("trackingEnabled", true)
            .put("userText", draft.message.isNotBlank())
            .put("emailUpdates", false)
            .put("webTokenV2", false)
            .put("clientContext", clientContext)
            .apply {
                sessionBody?.optJSONObject("serverContext")?.let {
                    put("serverContext", JSONObject(it.toString()))
                }
                if (draft.phone.isNotBlank()) put("ownerNbr", draft.phone)
                if (draft.message.isNotBlank()) put("text", draft.message)
                if (device.lostModeNeedsPasscode) put("passcode", draft.passcode)
            }
    }

    fun state(device: JSONObject): LostModeState =
        when {
            device.optJSONObject("lostDevice")?.let {
                !it.optBoolean("stopLostMode") && it.optString("statusCode") == "205"
            } == true -> LostModeState.PENDING
            device.optBoolean("lostModeEnabled") &&
                device.optString("lostTimestamp").let {
                    it.isNotBlank() && it != "0" && it != "null"
                } -> LostModeState.ENABLED
            device.optBoolean("lostModeEnabled") -> LostModeState.PENDING
            else -> LostModeState.OFF
        }

    fun result(response: JSONObject, deviceId: String): LostModeResult {
        val globalCode = response.optString("statusCode")
        if (globalCode in setOf("401", "421", "450")) return LostModeResult.AUTH_REQUIRED
        if (globalCode.isNotBlank() && globalCode != "200") return LostModeResult.REJECTED
        if (response.has("alert") && !response.isNull("alert")) return LostModeResult.UNKNOWN
        val content = response.optJSONArray("content")
        val target = content?.let { records ->
            (0 until records.length())
                .mapNotNull { records.optJSONObject(it) }
                .firstOrNull { it.optString("id") == deviceId }
        }
        val actionCode = target?.optJSONObject("lostDevice")?.optString("statusCode")
        if (content != null && content.length() > 0 && target == null) return LostModeResult.UNKNOWN
        if (actionCode == "200" || actionCode == "205") return LostModeResult.REQUESTED
        if (!actionCode.isNullOrBlank()) return LostModeResult.REJECTED
        if (target != null && state(target) != LostModeState.OFF) return LostModeResult.REQUESTED
        return if (globalCode == "200") LostModeResult.REQUESTED else LostModeResult.UNKNOWN
    }
}
