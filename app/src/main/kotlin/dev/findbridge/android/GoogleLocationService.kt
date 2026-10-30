package dev.findbridge.android

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/** Bound only while the native map is visible. Only normalized device data leaves :google. */
class GoogleLocationService : Service() {
    private lateinit var session: GoogleSession
    private var client: Messenger? = null
    private var generation = 0
    private val dataCallback: (String) -> Unit = { reply(GoogleProtocol.DATA, it) }
    private val errorCallback: () -> Unit = { reply(GoogleProtocol.ERROR) }
    private val messenger =
        Messenger(
            Handler(Looper.getMainLooper()) { message ->
                generation = message.data.getInt("generation")
                client = message.replyTo
                when (message.what) {
                    GoogleProtocol.REGISTER ->
                        session.snapshot?.let { reply(GoogleProtocol.DATA, it) }
                    GoogleProtocol.REFRESH -> session.refresh(message.data.getInt("index", -1))
                    GoogleProtocol.CLEAR -> session.clear { reply(GoogleProtocol.CLEARED) }
                    GoogleProtocol.STOP -> {
                        session.stop()
                        client = null
                    }
                }
                true
            }
        )

    override fun onCreate() {
        super.onCreate()
        session = GoogleSession.get(this)
        session.onData = dataCallback
        session.onError = errorCallback
    }

    private fun reply(code: Int, snapshot: String? = null) {
        runCatching {
            client?.send(
                Message.obtain(null, code).apply {
                    data =
                        Bundle().apply {
                            putInt("generation", generation)
                            putString("snapshot", snapshot)
                        }
                }
            )
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        session.stop()
        client = null
        return false
    }

    override fun onDestroy() {
        if (session.onData === dataCallback) session.onData = null
        if (session.onError === errorCallback) session.onError = null
        session.stop()
        super.onDestroy()
    }
}
