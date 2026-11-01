package dev.findbridge.android

import android.view.Window
import android.view.WindowManager
import java.util.WeakHashMap

/**
 * Multiple sensitive surfaces may share a window; the last one restores its original protection.
 */
internal class SecureWindowLease private constructor(private val window: Window) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        val entry = owners[window] ?: return
        entry.count--
        if (entry.count == 0) {
            owners.remove(window)
            if (!entry.originallySecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        private data class Entry(var count: Int, val originallySecure: Boolean)

        private val owners = WeakHashMap<Window, Entry>()

        fun acquire(window: Window): SecureWindowLease {
            val entry =
                owners.getOrPut(window) {
                    Entry(
                        0,
                        window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
                    )
                }
            entry.count++
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            return SecureWindowLease(window)
        }
    }
}
