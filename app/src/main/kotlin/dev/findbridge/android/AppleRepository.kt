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

/** Reads device locations. Sound is a separate, explicit owner-confirmed request. */
internal class AppleRepository private constructor(context: Context) {
    private val application = context.applicationContext as android.app.Application

    fun interface Listener {
        fun onChanged()
    }

    private val store = SecureSessionStore(context)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()
    private var session = store.load()
    @Volatile private var generation = 0
    var sendingSound = false
        private set

    var sendingLostMode = false
        private set

    var notificationTarget: String? = null
        private set

    var notificationValue = false
        private set

    var devices: List<AppleDevice> = emptyList()
        private set

    var familyMembers: List<FamilyMember> = emptyList()
        private set

    var loading = false
        private set

    private var errorRes = 0
    val error
        get() = if (errorRes == 0) "" else AppLanguage.text(application, errorRes)

    var requiresSignIn = false
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
                loading = false
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
                    ?.let {
                        AppleDevice.fromJson(
                            it,
                            owners,
                            response
                                .optJSONObject("serverContext")
                                ?.optString("imageBaseUrl")
                                .orEmpty(),
                        )
                    }
            }
        updatedAt = System.currentTimeMillis()
        errorRes = 0
        requiresSignIn = false
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
                        LoginVault(application).markSuccess()
                        applyResponse(response)
                    }
                }
            }
                .onFailure { failure ->
                    main.post {
                        if (generation == requestGeneration) {
                            loading = false
                            requiresSignIn = failure is AuthenticationExpiredException
                            if (requiresSignIn) nativeTransportVerified = false
                            errorRes =
                                if (failure is AuthenticationExpiredException) {
                                    R.string.sign_in_required
                                } else {
                                    R.string.refresh_failed
                                }
                            changed()
                        }
                    }
                }
        }
    }

    /** Never invoked by refresh, sign-in, background timers or automatic retries. */
    fun playSound(id: String, completed: (Boolean) -> Unit) {
        val current = session
        if (current == null || sendingSound || devices.none { it.id == id && it.canPlaySound }) {
            completed(false)
            return
        }
        val snapshot = JSONObject(current.toString())
        snapshot.put("url", UrlPolicy.soundEndpoint(snapshot.getString("url")))
        snapshot.put(
            "body",
            AppleRequestBody.base(current.optJSONObject("body"))
                .put("device", id)
                .put("subject", "Find Bridge"),
        )
        val requestGeneration = generation
        sendingSound = true
        changed()
        executor.execute {
            val accepted = runCatching {
                check(generation == requestGeneration)
                request(snapshot, allowSound = true).optInt("statusCode", 200) == 200
            }
                .getOrDefault(false)
            main.post {
                sendingSound = false
                if (generation == requestGeneration) {
                    changed()
                    completed(accepted)
                }
            }
        }
    }

    /** Only an explicit native confirmation calls this. No automatic retry of a remote lock. */
    fun markLost(id: String, draft: LostModeDraft, completed: (LostModeResult) -> Unit) {
        val device = devices.firstOrNull { it.id == id }
        val current = session
        val body = device?.let {
            runCatching { LostMode.body(it, draft, current?.optJSONObject("body")) }.getOrNull()
        }
        if (current == null || device == null || body == null || sendingLostMode) {
            completed(LostModeResult.REJECTED)
            return
        }
        val snapshot =
            JSONObject(current.toString())
                .put("url", UrlPolicy.lostModeEndpoint(current.getString("url"), device.family))
                .put("body", body)
        val requestGeneration = generation
        sendingLostMode = true
        changed()
        executor.execute {
            val result = runCatching {
                check(requestGeneration == generation)
                LostMode.result(send(snapshot, RequestKind.LOST_MODE), id)
            }
                .getOrElse {
                    if (it is AuthenticationExpiredException) LostModeResult.AUTH_REQUIRED
                    else LostModeResult.UNKNOWN
                }
            main.post {
                sendingLostMode = false
                if (requestGeneration != generation) {
                    completed(LostModeResult.UNKNOWN)
                    return@post
                }
                if (result == LostModeResult.AUTH_REQUIRED) {
                    requiresSignIn = true
                    nativeTransportVerified = false
                }
                changed()
                completed(result)
                if (result == LostModeResult.REQUESTED) refresh()
            }
        }
    }

    private enum class RequestKind {
        READ,
        SOUND,
        LOST_MODE,
        NOTIFICATION,
    }

    private fun request(snapshot: JSONObject, allowSound: Boolean = false): JSONObject =
        send(snapshot, if (allowSound) RequestKind.SOUND else RequestKind.READ)

    private fun send(snapshot: JSONObject, kind: RequestKind): JSONObject {
        val address = snapshot.getString("url")
        require(
            when (kind) {
                RequestKind.READ -> UrlPolicy.isReadEndpoint(address)
                RequestKind.SOUND -> UrlPolicy.isSoundEndpoint(address)
                RequestKind.LOST_MODE -> UrlPolicy.isLostModeEndpoint(address)
                RequestKind.NOTIFICATION -> UrlPolicy.isNotificationEndpoint(address)
            }
        )
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
            return if (kind == RequestKind.SOUND && buffer.size() == 0) JSONObject()
            else JSONObject(buffer.toString(Charsets.UTF_8.name()))
        } finally {
            connection.disconnect()
        }
    }

    fun logout(afterCookiesCleared: (() -> Unit)? = null) {
        generation++
        session = null
        devices = emptyList()
        familyMembers = emptyList()
        loading = false
        errorRes = 0
        requiresSignIn = false
        nativeTransportVerified = false
        // Queue after any pending save so an in-flight response cannot resurrect a logged-out
        // session.
        executor.execute { store.clear() }
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            afterCookiesCleared?.invoke()
        }
        WebStorage.getInstance().deleteAllData()
        changed()
    }

    fun setNotifyWhenFound(
        id: String,
        enabled: Boolean,
        completed: (AppleNotification.Result) -> Unit,
    ) {
        val current = session
        val device = devices.firstOrNull { it.id == id }
        val body = device?.let {
            runCatching { AppleNotification.body(it, enabled, current?.optJSONObject("body")) }
                .getOrNull()
        }
        if (current == null || body == null || notificationTarget != null) {
            completed(AppleNotification.Result.FAILED)
            return
        }
        val snapshot =
            JSONObject(current.toString())
                .put("body", body)
                .put("url", UrlPolicy.notificationEndpoint(current.getString("url")))
        val requestGeneration = generation
        notificationTarget = id
        notificationValue = enabled
        changed()
        executor.execute {
            val result = runCatching {
                check(generation == requestGeneration)
                AppleNotification.result(send(snapshot, RequestKind.NOTIFICATION), id, enabled)
            }
                .getOrElse {
                    if (it is AuthenticationExpiredException) AppleNotification.Result.AUTH_REQUIRED
                    else AppleNotification.Result.UNKNOWN
                }
            main.post {
                notificationTarget = null
                if (generation != requestGeneration) return@post
                if (result == AppleNotification.Result.SAVED)
                    devices = devices.map {
                        if (it.id == id) it.copy(notifyWhenFound = enabled) else it
                    }
                if (result == AppleNotification.Result.AUTH_REQUIRED) {
                    requiresSignIn = true
                    nativeTransportVerified = false
                }
                changed()
                completed(result)
            }
        }
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
