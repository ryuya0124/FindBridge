package dev.findbridge.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** A failure deadline for a loading cover, independent of page scripts and redirects. */
internal class AuthLoadingDeadline(
    private val limitMillis: Long = 45_000L,
    private val expired: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var waiting = false
    private var remaining = limitMillis
    private var startedAt: Long? = null
    private val timeout = Runnable {
        if (waiting && active) {
            stop()
            expired()
        }
    }

    fun start() {
        stop()
        waiting = true
        remaining = limitMillis
        schedule()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        startedAt?.let {
            remaining = (remaining - (SystemClock.uptimeMillis() - it)).coerceAtLeast(0)
        }
        startedAt = null
        handler.removeCallbacks(timeout)
        active = value
        schedule()
    }

    private fun schedule() {
        if (waiting && active && startedAt == null) {
            startedAt = SystemClock.uptimeMillis()
            handler.postDelayed(timeout, remaining)
        }
    }

    fun stop() {
        waiting = false
        startedAt = null
        handler.removeCallbacks(timeout)
    }
}
