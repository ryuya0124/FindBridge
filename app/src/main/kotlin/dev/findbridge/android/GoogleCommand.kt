package dev.findbridge.android

import android.os.SystemClock
import java.security.SecureRandom
import org.json.JSONObject

internal enum class GoogleCommandType {
    RING,
    STOP_SOUND,
    MARK_LOST,
}

/** Transient, single-use handoff. Only a random ticket goes into an Activity Intent. */
internal object GoogleCommandStore {
    private class Entry(
        val account: String,
        val generation: Int,
        val issuedAt: Long,
        val data: JSONObject,
        var taken: Boolean = false,
    )

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun put(
        account: String,
        generation: Int,
        device: AppleDevice,
        type: GoogleCommandType,
        draft: LostModeDraft? = null,
    ): String {
        require(account.isNotBlank() && device.provider == DeviceProvider.GOOGLE)
        require(device.id.matches(Regex("google:[0-9]{1,3}:[0-9a-f]{24}")))
        when (type) {
            GoogleCommandType.RING -> require(device.canPlaySound && !device.soundPlaying)
            GoogleCommandType.STOP_SOUND -> require(device.canPlaySound && device.soundPlaying)
            GoogleCommandType.MARK_LOST -> require(draft != null && validLostDraft(device, draft))
        }
        // There is only one user-confirmed operation at a time. Superseded drafts vanish.
        entries.clear()
        val ticket =
            ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val data =
            JSONObject()
                .put("ticket", ticket)
                .put("account", account)
                .put("deviceId", device.id)
                .put("index", device.id.split(':')[1].toInt())
                .put("type", type.name)
        if (draft != null) data.put("phone", draft.phone).put("message", draft.message)
        entries[ticket] = Entry(account, generation, SystemClock.elapsedRealtime(), data)
        return ticket
    }

    fun validLostDraft(device: AppleDevice, draft: LostModeDraft): Boolean =
        device.provider == DeviceProvider.GOOGLE &&
            device.lostModeSupported &&
            device.lostModeState == LostModeState.OFF &&
            draft.passcode.isEmpty() &&
            draft.phone.length <= 100 &&
            draft.phone.none { it.isISOControl() } &&
            draft.message.length <= 80 &&
            draft.message.none { it.isISOControl() && it != '\n' }

    @Synchronized
    fun take(ticket: String, account: String, generation: Int): JSONObject? {
        val entry = entries[ticket] ?: return null
        if (entry.taken || !authorized(ticket, account, generation)) return null
        entry.taken = true
        return JSONObject(entry.data.toString())
    }

    @Synchronized
    fun authorized(ticket: String, account: String, generation: Int): Boolean =
        entries[ticket]?.let {
            it.account.equals(account, true) &&
                it.generation == generation &&
                SystemClock.elapsedRealtime() - it.issuedAt in 0..300_000
        } == true

    @Synchronized
    fun revoke(ticket: String) {
        entries.remove(ticket)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
