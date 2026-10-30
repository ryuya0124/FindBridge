package dev.findbridge.android

import android.content.Context
import org.json.JSONObject

/** Deliberately not a data class: toString() must never expand account credentials into logs. */
internal class SavedLogin(val account: String, val password: String)

/**
 * Passwords never enter Intent extras, ordinary preferences, URLs, logs, or application backups.
 */
internal class LoginVault(context: Context, namespace: String = "apple_credentials") {
    private val store = SecureSessionStore(context, namespace)
    private val policy =
        context.applicationContext.getSharedPreferences(namespace + "_policy", Context.MODE_PRIVATE)
    val hasSaved
        get() = policy.getBoolean("saved", false)

    val automatic
        get() = policy.getBoolean("automatic", false)

    val paused
        get() = policy.getBoolean("paused", false)

    fun load(): SavedLogin? {
        val result =
            store.load()?.let {
                val account = it.optString("account")
                val password = it.optString("password")
                if (account.isBlank() || password.isEmpty()) null else SavedLogin(account, password)
            }
        if (result == null && hasSaved) pause()
        return result
    }

    fun save(login: SavedLogin, automatic: Boolean) {
        store.save(JSONObject().put("account", login.account).put("password", login.password))
        check(
            policy
                .edit()
                .putBoolean("saved", true)
                .putBoolean("automatic", automatic)
                .putBoolean("paused", false)
                .putLong("attemptedAt", 0L)
                .commit()
        )
    }

    fun automaticAllowed(now: Long = System.currentTimeMillis()): Boolean =
        hasSaved &&
            automatic &&
            !paused &&
            (policy.getLong("attemptedAt", 0L) == 0L ||
                now - policy.getLong("attemptedAt", 0L) >= 300_000L)

    fun markAttempt() {
        policy.edit().putLong("attemptedAt", System.currentTimeMillis()).apply()
    }

    fun markSuccess() {
        policy.edit().putBoolean("paused", false).apply()
    }

    fun pause() {
        policy.edit().putBoolean("paused", true).apply()
    }

    fun clear() {
        check(policy.edit().clear().commit())
        store.clear()
    }

    companion object {
        private var staged: SavedLogin? = null

        @Synchronized
        fun stage(login: SavedLogin) {
            staged = login
        }

        @Synchronized fun takeStaged(): SavedLogin? = staged.also { staged = null }

        @Synchronized
        fun clearStaged() {
            staged = null
        }
    }
}
