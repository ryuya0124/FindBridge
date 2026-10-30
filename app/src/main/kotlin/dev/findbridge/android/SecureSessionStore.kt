package dev.findbridge.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Passwords are never stored. Session material is protected by Android Keystore AES-GCM. */
internal class SecureSessionStore(context: Context, namespace: String = "apple_session") {
    private val prefs =
        context.applicationContext.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias =
        if (namespace == "apple_session") "findbridge.apple.session.v2"
        else "findbridge.$namespace.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    @Synchronized
    fun save(session: JSONObject) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))
        check(
            prefs
                .edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .commit()
        )
    }

    @Synchronized
    fun load(): JSONObject? = runCatching {
        val bytes = prefs.getString("data", null) ?: return null
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)),
                )
            }
        JSONObject(String(cipher.doFinal(Base64.decode(bytes, Base64.NO_WRAP)), Charsets.UTF_8))
    }
        .getOrElse {
            clear()
            null
        }

    @android.annotation.SuppressLint(
        "ApplySharedPref"
    ) // Executed on the repository's worker, with ordered saves.
    @Synchronized
    fun clear() {
        prefs.edit().clear().commit()
    }
}
