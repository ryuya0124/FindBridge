package dev.findbridge.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import org.json.JSONObject

/** Only initClient and refreshClient are permitted. Mutating device APIs are absent. */
internal class AppleRepository private constructor(context: Context) {
    fun interface Listener {
        fun onChanged()
    }

    private val store = SecureSessionStore(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    private var session = store.load()
    private var generation = 0
    var devices: List<AppleDevice> = emptyList()
        private set

    var familyMembers: List<FamilyMember> = emptyList()
        private set

    var loading = false
        private set

    var error = ""
        private set

    var nativeTransportVerified = false
        private set

    var updatedAt = 0L
        private set

    val hasSession: Boolean
        get() = session != null

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun changed() {
        listeners.toList().forEach { it.onChanged() }
    }

    fun acceptAuthenticatedResponse(envelope: String, userAgent: String): Boolean {
        return runCatching {
                require(envelope.length <= responseLimit)
                val message = JSONObject(envelope)
                val url = message.getString("url")
                require(UrlPolicy.isReadEndpoint(url))
                val response = message.getJSONObject("response")
                require(response.optJSONArray("content") != null)
                val originalBody = JSONObject(message.optString("body", "{}"))
                val client = originalBody.optJSONObject("clientContext") ?: JSONObject()
                client.put("shouldLocate", true).put("selectedDevice", "all").put("fmly", true)
                val body = JSONObject().put("clientContext", client)
                response.optJSONObject("serverContext")?.let { body.put("serverContext", it) }
                val refreshUrl = url.replace("/initClient", "/refreshClient")
                val cookies = CookieManager.getInstance().getCookie(refreshUrl).orEmpty()
                session =
                    JSONObject()
                        .put("url", refreshUrl)
                        .put("cookie", cookies)
                        .put("body", body)
                        .put("userAgent", userAgent)
                        .put("savedAt", System.currentTimeMillis())
                generation++
                applyResponse(response)
                val snapshot = JSONObject(session.toString())
                executor.execute { runCatching { store.save(snapshot) } }
                true
            }
            .getOrDefault(false)
    }

    private fun applyResponse(response: JSONObject) {
        val content = response.optJSONArray("content") ?: return
        familyMembers = FamilyMember.fromUserInfo(response.optJSONObject("userInfo"))
        val owners = familyMembers.associateBy { it.id }

        devices =
            (0 until content.length()).mapNotNull { index ->
                content
                    .optJSONObject(index)
                    ?.takeIf { it.optString("id").isNotEmpty() }
                    ?.let { AppleDevice.fromJson(it, owners) }
            }
        updatedAt = System.currentTimeMillis()
        error = ""
        changed()
    }

    fun refresh() {
        val current = session ?: return
        if (loading) return
        val snapshot = JSONObject(current.toString())
        snapshot.getJSONObject("body").getJSONObject("clientContext").put("fmly", true)
        val requestGeneration = generation
        loading = true
        changed()
        executor.execute {
            runCatching {
                val response = request(snapshot)
                check(response.optJSONArray("content") != null)
                response.optJSONObject("serverContext")?.let {
                    snapshot.getJSONObject("body").put("serverContext", it)
                }
                store.save(snapshot)
                main.post {
                    if (generation == requestGeneration) {
                        session = snapshot
                        loading = false
                        nativeTransportVerified = true
                        applyResponse(response)
                    }
                }
            }
                .onFailure { failure ->
                    main.post {
                        if (generation == requestGeneration) {
                            loading = false
                            error =
                                if (failure is AuthenticationExpiredException) {
                                    "Appleへのサインインが必要です"
                                } else {
                                    "更新できませんでした。接続を確認して再試行してください。"
                                }
                            changed()
                        }
                    }
                }
        }
    }

    private fun request(snapshot: JSONObject): JSONObject {
        val address = snapshot.getString("url")
        require(UrlPolicy.isReadEndpoint(address))
        val connection = URI(address).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
        connection.setRequestProperty("Origin", "https://www.icloud.com")
        connection.setRequestProperty("Referer", "https://www.icloud.com/")
        connection.setRequestProperty("User-Agent", snapshot.optString("userAgent"))
        connection.setRequestProperty("Cookie", snapshot.optString("cookie"))
        connection.setRequestProperty("Accept", "application/json")
        connection.doOutput = true
        try {
            connection.outputStream.use {
                it.write(snapshot.getJSONObject("body").toString().toByteArray(Charsets.UTF_8))
            }
            when (connection.responseCode) {
                200 -> Unit
                401,
                403,
                421,
                450 -> throw AuthenticationExpiredException()
                else -> error("Apple service unavailable")
            }
            val buffer = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count == -1) break
                    buffer.write(chunk, 0, count)
                    check(buffer.size() <= responseLimit)
                }
            }
            return JSONObject(buffer.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }

    fun logout() {
        generation++
        session = null
        devices = emptyList()
        familyMembers = emptyList()
        loading = false
        error = ""
        nativeTransportVerified = false
        // Queue after any pending save so an in-flight response cannot resurrect a logged-out
        // session.
        executor.execute { store.clear() }
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        WebStorage.getInstance().deleteAllData()
        changed()
    }

    private class AuthenticationExpiredException : Exception()

    companion object {
        private const val responseLimit = 4_000_000
        @Volatile private var instance: AppleRepository? = null

        fun get(context: Context): AppleRepository =
            instance
                ?: synchronized(this) {
                    instance ?: AppleRepository(context.applicationContext).also { instance = it }
                }
    }
}
