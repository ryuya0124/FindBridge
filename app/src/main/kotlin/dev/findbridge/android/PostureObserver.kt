package dev.findbridge.android

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

internal class PostureObserver(private val activity: Activity, changed: (FoldRegion?) -> Unit) {
    private val adapter = WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity))
    private val listener =
        Consumer<WindowLayoutInfo> { info ->
            val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            changed(
                feature?.let {
                    val b = it.bounds
                    FoldRegion(
                        ScreenRect(b.left, b.top, b.right, b.bottom),
                        it.orientation == FoldingFeature.Orientation.VERTICAL,
                        it.isSeparating || it.state == FoldingFeature.State.HALF_OPENED,
                    )
                }
            )
        }

    fun start() {
        adapter.addWindowLayoutInfoListener(activity, activity.mainExecutor, listener)
    }

    fun stop() {
        adapter.removeWindowLayoutInfoListener(listener)
    }

    companion object {
        /** Credential entry and authentication stay entirely on one physical pane. */
        @android.annotation.SuppressLint("RtlHardcoded")
        fun placeInPane(root: FrameLayout, content: View, fold: FoldRegion?) {
            if (root.width == 0 || root.height == 0) return
            var area =
                ScreenRect(
                    root.paddingLeft,
                    root.paddingTop,
                    root.width - root.paddingRight,
                    root.height - root.paddingBottom,
                )
            fold?.let {
                val b = it.bounds
                if (it.vertical && b.left > area.left && b.right < area.right)
                    area = area.copy(left = b.right)
                else if (
                    !it.vertical && it.separating && b.top > area.top && b.bottom < area.bottom
                )
                    area =
                        if (area.bottom - b.bottom >= b.top - area.top) area.copy(top = b.bottom)
                        else area.copy(bottom = b.top)
            }
            val p = content.layoutParams as FrameLayout.LayoutParams
            val x = area.left - root.paddingLeft
            val y = area.top - root.paddingTop
            if (
                p.width == area.width &&
                    p.height == area.height &&
                    p.leftMargin == x &&
                    p.topMargin == y
            )
                return
            p.width = area.width
            p.height = area.height
            p.leftMargin = x
            p.topMargin = y
            p.gravity = Gravity.TOP or Gravity.LEFT
            content.layoutParams = p
        }
    }
}
