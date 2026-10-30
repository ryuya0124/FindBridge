package dev.findbridge.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.Executors
import org.json.JSONObject

/** Main-process state. Google cookies, passwords and unlock keys never cross this interface. */
internal class GoogleRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = SecureSessionStore(app, "google_connection")
    private val identityStore = SecureSessionStore(app, "google_identity")
    private val main = Handler(Looper.getMainLooper())
    private val restoredIdentity = identityStore.load()
    private var verifiedAccount = restoredIdentity?.optString("account").orEmpty()
    var signedIn = restoredIdentity?.optBoolean("verified", false) == true
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<AppleRepository.Listener>()
    private var endpoint: Messenger? = null
    private var bound = false
    private var foreground = false
    @Volatile private var generation = 0
    private var clearing = false
    private var lastRefresh = 0L
    private var pendingIndex: Int? = null
    var connected =
        store.load()?.let {
            signedIn &&
                it.optBoolean("connected", false) &&
                it.optString("account").equals(verifiedAccount, true)
        } == true
        private set

    var devices: List<AppleDevice> = emptyList()
        private set

    var loading = false
        private set

    var needsSignIn = false
        private set

    private val incoming =
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                if (message.data.getInt("generation", -1) != generation) return@Handler true
                when (message.what) {
                    GoogleProtocol.DATA -> accept(message.data.getString("snapshot").orEmpty())
                    GoogleProtocol.ERROR -> {
                        loading = false
                        needsSignIn = true
                        changed()
                    }
                    GoogleProtocol.CLEARED -> {
                        clearing = false
                        loading = false
                        release()
                        changed()
                    }
                }
                true
            }
        )
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                endpoint = Messenger(binder)
                if (clearing) send(GoogleProtocol.CLEAR)
                else if (foreground && connected) {
                    val pending = pendingIndex
                    if (pending != null) {
                        pendingIndex = null
                        send(GoogleProtocol.REFRESH, pending)
                    } else if (loading) {
                        send(GoogleProtocol.REFRESH)
                    } else if (
                        lastRefresh == 0L ||
                            android.os.SystemClock.elapsedRealtime() - lastRefresh >= 120_000L
                    )
                        refresh()
                    else send(GoogleProtocol.REGISTER)
                } else release()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                endpoint = null
                loading = false
                changed()
            }
        }

    fun addListener(listener: AppleRepository.Listener) {
        listeners += listener
    }

    fun removeListener(listener: AppleRepository.Listener) {
        listeners -= listener
    }

    private fun changed() {
        listeners.toList().forEach { it.onChanged() }
    }

    /** A verified Google identity is not a Find Hub connection. Tokens are never persisted. */
    fun acceptIdentity(identity: GoogleIdentity, completed: (Boolean) -> Unit) {
        val requestGeneration = generation
        worker.execute {
            val previous = identityStore.load()
            val changedIdentity =
                previous == null ||
                    previous.optString("subject") != identity.subject ||
                    !previous.optString("account").equals(identity.email, true)
            val saved = runCatching {
                if (changedIdentity) store.clear()
                identityStore.save(
                    JSONObject()
                        .put("subject", identity.subject)
                        .put("account", identity.email)
                        .put("verified", true)
                )
            }
                .isSuccess
            main.post {
                if (generation != requestGeneration) {
                    completed(false)
                    return@post
                }
                if (saved) {
                    verifiedAccount = identity.email
                    if (changedIdentity) {
                        GoogleCommandStore.clear()
                        generation++
                        connected = false
                        devices = emptyList()
                        loading = false
                        needsSignIn = false
                        lastRefresh = 0L
                    }
                    signedIn = true
                    changed()
                }
                completed(saved)
            }
        }
    }

    fun accept(text: String) {
        if (text.length > GoogleProtocol.MAX_SNAPSHOT_CHARS || clearing) return
        val snapshot = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (!snapshot.optBoolean("verified")) return
        if (
            !signedIn ||
                verifiedAccount.isBlank() ||
                !snapshot.optString("account").equals(verifiedAccount, true)
        )
            return
        val parsed = runCatching { GoogleProtocol.devices(snapshot) }.getOrNull() ?: return
        connected = true
        needsSignIn = false
        val requestedIndex = snapshot.optInt("requestedIndex", -1)
        val existing = devices.associateBy { it.id }
        devices =
            if (requestedIndex >= 0)
                parsed.map {
                    if (it.id.split(':').getOrNull(1)?.toIntOrNull() == requestedIndex) it
                    else existing[it.id] ?: it
                }
            else parsed
        loading = !snapshot.optBoolean("complete", true)
        lastRefresh = android.os.SystemClock.elapsedRealtime()
        val metadata =
            JSONObject().put("connected", true).put("account", snapshot.optString("account"))
        worker.execute { runCatching { store.save(metadata) } }
        if (foreground) bind()
        changed()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value && connected) bind()
        else if (!value && !clearing) {
            if (loading) lastRefresh = 0L
            loading = false
            pendingIndex = null
            send(GoogleProtocol.STOP)
            release()
        }
    }

    private fun bind() {
        if (bound) return
        bound =
            app.bindService(
                Intent(app, GoogleLocationService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
    }

    private fun release() {
        if (bound) app.unbindService(connection)
        bound = false
        endpoint = null
    }

    private fun send(code: Int, index: Int = -1) {
        runCatching {
            endpoint?.send(
                Message.obtain(null, code).apply {
                    replyTo = incoming
                    data =
                        Bundle().apply {
                            putInt("generation", generation)
                            putInt("index", index)
                        }
                }
            )
        }
    }

    fun refreshIfDue() {
        if (lastRefresh == 0L || android.os.SystemClock.elapsedRealtime() - lastRefresh >= 120_000L)
            refresh()
    }

    fun invalidateRead() {
        lastRefresh = 0L
        if (foreground) refresh()
    }

    fun refresh(id: String? = null) {
        if (!foreground || !connected || clearing || loading) return
        val index =
            id?.takeIf { it.startsWith("google:") }?.split(':')?.getOrNull(1)?.toIntOrNull() ?: -1
        loading = true
        changed()
        if (endpoint == null) {
            pendingIndex = index
            bind()
        } else send(GoogleProtocol.REFRESH, index)
    }

    fun disconnect() {
        GoogleCommandStore.clear()
        generation++
        connected = false
        signedIn = false
        verifiedAccount = ""
        devices = emptyList()
        needsSignIn = false
        clearing = true
        loading = true
        pendingIndex = null
        worker.execute {
            store.clear()
            identityStore.clear()
        }
        bind()
        send(GoogleProtocol.CLEAR)
        changed()
    }

    fun commandTicket(
        device: AppleDevice,
        type: GoogleCommandType,
        draft: LostModeDraft? = null,
    ): String? {
        if (!signedIn || !connected || clearing || devices.none { it.id == device.id }) return null
        val current = devices.first { it.id == device.id }
        return runCatching {
            GoogleCommandStore.put(verifiedAccount, generation, current, type, draft)
        }
            .getOrNull()
    }

    fun takeCommand(ticket: String): JSONObject? =
        if (signedIn && connected && !clearing)
            GoogleCommandStore.take(ticket, verifiedAccount, generation)
        else null

    fun authorizeCommand(ticket: String): Boolean =
        signedIn &&
            connected &&
            !clearing &&
            GoogleCommandStore.authorized(ticket, verifiedAccount, generation)

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // Retains only applicationContext.
        @Volatile
        private var instance: GoogleRepository? = null

        fun get(context: Context): GoogleRepository =
            instance
                ?: synchronized(this) {
                    instance ?: GoogleRepository(context).also { instance = it }
                }
    }
}
