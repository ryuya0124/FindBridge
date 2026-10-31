package dev.findbridge.android

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import org.maplibre.android.maps.MapView

/** Requests the panel's native cadence during movement and releases that vote when idle. */
internal class MapFrameRate(private val activity: Activity, private val map: MapView) {
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private var moving = false
    private var registered = false
    private val originalRate = activity.window.attributes.preferredRefreshRate
    private val changed =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = update()

            override fun onDisplayRemoved(displayId: Int) = update()

            override fun onDisplayChanged(displayId: Int) = update()
        }

    fun start() {
        if (registered) return
        registered = true
        displays.registerDisplayListener(changed, Handler(Looper.getMainLooper()))
        update()
    }

    fun setMoving(value: Boolean) {
        if (moving == value) return
        moving = value
        update()
    }

    private fun update() {
        val display = map.display ?: return
        val mode = display.mode
        val fastest =
            display.supportedModes
                .filter {
                    it.physicalWidth == mode.physicalWidth &&
                        it.physicalHeight == mode.physicalHeight
                }
                .maxOfOrNull { it.refreshRate } ?: display.refreshRate
        // Surface synchronization paces rendering. Avoid the library's extra
        // sleep before buffer swap, which can make a texture miss display frames.
        val vote = if (moving) fastest else originalRate
        if (activity.window.attributes.preferredRefreshRate != vote) {
            activity.window.attributes =
                activity.window.attributes.apply { preferredRefreshRate = vote }
        }
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            val requested = if (moving) fastest else 0f
            if (map.requestedFrameRate != requested) map.setRequestedFrameRate(requested)
        }
    }

    fun stop() {
        if (registered) displays.unregisterDisplayListener(changed)
        registered = false
        setMoving(false)
    }
}
