package dev.findbridge.android

import kotlin.math.max
import kotlin.math.min

internal data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width
        get() = right - left

    val height
        get() = bottom - top
}

internal data class FoldRegion(
    val bounds: ScreenRect,
    val vertical: Boolean,
    val separating: Boolean,
)

internal data class ScreenInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

internal enum class LayoutMode {
    PHONE,
    SIDEBAR,
    RAIL,
    TABLETOP,
}

internal data class LayoutPlan(
    val mode: LayoutMode,
    val sheet: ScreenRect,
    val tabs: ScreenRect,
    val controls: ScreenRect,
    val map: ScreenRect,
    val camera: ScreenInsets,
    val popupBounds: ScreenRect,
) {
    val sidebar
        get() = mode == LayoutMode.SIDEBAR || mode == LayoutMode.RAIL
}

/** All geometry uses the current app window, including multi-window bounds and physical folds. */
internal object AdaptiveLayout {
    fun calculate(
        width: Int,
        height: Int,
        density: Float,
        insets: ScreenInsets,
        sheetHeight: Int,
        fold: FoldRegion? = null,
    ): LayoutPlan {
        fun dp(value: Int) = (value * density).toInt()
        val safe = ScreenRect(insets.left, insets.top, width - insets.right, height - insets.bottom)
        val verticalFold = fold?.takeIf {
            it.vertical && it.bounds.left > safe.left && it.bounds.right < safe.right
        }
        val horizontalFold = fold?.takeIf {
            !it.vertical &&
                it.separating &&
                it.bounds.top > safe.top &&
                it.bounds.bottom < safe.bottom
        }
        val wide = safe.width >= dp(600)
        val rail =
            wide &&
                ((safe.height < dp(500)) ||
                    (verticalFold != null &&
                        width > height &&
                        safe.right - verticalFold.bounds.right >= dp(370)))
        val mode =
            when {
                horizontalFold != null -> LayoutMode.TABLETOP
                rail -> LayoutMode.RAIL
                wide -> LayoutMode.SIDEBAR
                else -> LayoutMode.PHONE
            }
        val tabHeight = dp(68)
        val tabBottom = safe.bottom - dp(8)
        var popup = safe
        val sheet: ScreenRect
        val tabs: ScreenRect
        val map: ScreenRect
        val camera: ScreenInsets
        if (mode == LayoutMode.TABLETOP) {
            val hinge = horizontalFold!!.bounds
            map = ScreenRect(0, 0, width, hinge.top)
            tabs =
                ScreenRect(
                    safe.left + dp(16),
                    tabBottom - tabHeight,
                    safe.right - dp(16),
                    tabBottom,
                )
            sheet =
                ScreenRect(
                    safe.left + dp(8),
                    hinge.bottom + dp(10),
                    safe.right - dp(8),
                    max(hinge.bottom + dp(11), tabs.top - dp(12)),
                )
            camera =
                ScreenInsets(safe.left + dp(30), safe.top + dp(45), insets.right + dp(30), dp(22))
            popup = ScreenRect(safe.left, safe.top, safe.right, hinge.top)
        } else if (wide) {
            val paneRight =
                verticalFold?.bounds?.left
                    ?: min(
                        safe.right,
                        safe.left +
                            max(dp(300), min(dp(360), (safe.width * .36f).toInt())) +
                            dp(16),
                    )
            val cardRight = min(paneRight - dp(16), safe.left + dp(376))
            val cardLeft = safe.left + dp(16)
            tabs =
                if (rail)
                    ScreenRect(
                        safe.right - dp(80),
                        max(safe.top + dp(12), safe.bottom - dp(280)),
                        safe.right - dp(16),
                        safe.bottom - dp(16),
                    )
                else ScreenRect(cardLeft, tabBottom - tabHeight, cardRight, tabBottom)
            sheet =
                ScreenRect(
                    cardLeft,
                    safe.top + dp(16),
                    cardRight,
                    if (rail) safe.bottom - dp(16) else tabs.top - dp(12),
                )
            map = ScreenRect(0, 0, width, height)
            val mapLeft = verticalFold?.bounds?.right ?: cardRight
            camera =
                ScreenInsets(
                    mapLeft + dp(22),
                    safe.top + dp(50),
                    insets.right + if (rail) dp(108) else dp(34),
                    insets.bottom + dp(34),
                )
            if (verticalFold != null)
                popup =
                    ScreenRect(verticalFold.bounds.right + dp(4), safe.top, safe.right, safe.bottom)
        } else {
            map = ScreenRect(0, 0, width, height)
            tabs =
                ScreenRect(
                    safe.left + dp(16),
                    tabBottom - tabHeight,
                    safe.right - dp(16),
                    tabBottom,
                )
            val maximum = max(dp(100), tabs.top - safe.top - dp(64))
            val actual = sheetHeight.coerceIn(min(dp(110), maximum), maximum + dp(24))
            sheet =
                ScreenRect(
                    safe.left + dp(8),
                    tabs.top - dp(16) - actual,
                    safe.right - dp(8),
                    tabs.top - dp(16),
                )
            camera =
                ScreenInsets(
                    safe.left + dp(40),
                    safe.top + dp(60),
                    insets.right + dp(40),
                    height - sheet.top + dp(20),
                )
        }
        val right = safe.right - if (rail) dp(100) else dp(16)
        val controls = ScreenRect(right - dp(44), safe.top + dp(12), right, safe.top + dp(108))
        return LayoutPlan(mode, sheet, tabs, controls, map, camera, popup)
    }
}
