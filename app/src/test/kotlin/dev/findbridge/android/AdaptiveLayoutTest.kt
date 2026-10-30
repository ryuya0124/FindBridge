package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class AdaptiveLayoutTest {
    private fun intersects(a: ScreenRect, b: ScreenRect) =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    @Test
    fun phoneUsesBottomSheetAndTabletUsesLeadingSidebar() {
        val phone = AdaptiveLayout.calculate(412, 915, 1f, ScreenInsets(top = 28, bottom = 24), 390)
        assertEquals(LayoutMode.PHONE, phone.mode)
        assertTrue(phone.sheet.bottom < phone.tabs.top)
        assertTrue(phone.sheet.top > phone.controls.bottom)
        val tablet =
            AdaptiveLayout.calculate(1280, 800, 1f, ScreenInsets(top = 24, bottom = 24), 390)
        assertEquals(LayoutMode.SIDEBAR, tablet.mode)
        assertTrue(tablet.sheet.right < 640)
        assertTrue(tablet.camera.left > tablet.sheet.right)
        assertFalse(intersects(tablet.sheet, tablet.tabs))
    }

    @Test
    fun outerLandscapeUsesTrailingRailAndKeepsMapControlsBesideContent() {
        val plan = AdaptiveLayout.calculate(915, 412, 1f, ScreenInsets(left = 30, bottom = 24), 390)
        assertEquals(LayoutMode.RAIL, plan.mode)
        assertTrue(plan.tabs.width < plan.tabs.height)
        assertTrue(plan.controls.right < plan.tabs.left)
        assertFalse(intersects(plan.tabs, plan.controls))
        assertTrue(plan.camera.left + plan.camera.right < plan.map.width)
    }

    @Test
    fun bookHingeDoesNotCrossCardsButtonsOrTabs() {
        val hinge = ScreenRect(440, 0, 460, 740)
        val plan =
            AdaptiveLayout.calculate(
                900,
                740,
                1f,
                ScreenInsets(top = 28, bottom = 24),
                390,
                FoldRegion(hinge, true, true),
            )
        for (view in listOf(plan.sheet, plan.tabs, plan.controls, plan.popupBounds)) assertFalse(
            intersects(view, hinge)
        )
        assertTrue(plan.camera.left > hinge.right)
        assertTrue(plan.sheet.right < hinge.left)
    }

    @Test
    fun tabletopSeparatesMapAndListButAFlatHorizontalCreaseDoesNot() {
        val hinge = ScreenRect(0, 430, 720, 448)
        val fold = FoldRegion(hinge, false, true)
        val plan =
            AdaptiveLayout.calculate(720, 1000, 1f, ScreenInsets(top = 24, bottom = 24), 390, fold)
        assertEquals(LayoutMode.TABLETOP, plan.mode)
        assertEquals(hinge.top, plan.map.bottom)
        assertTrue(plan.sheet.top > hinge.bottom)
        for (view in listOf(plan.sheet, plan.tabs, plan.controls, plan.popupBounds)) assertFalse(
            intersects(view, hinge)
        )
        val flat =
            AdaptiveLayout.calculate(
                720,
                1000,
                1f,
                ScreenInsets(),
                390,
                fold.copy(separating = false),
            )
        assertNotEquals(LayoutMode.TABLETOP, flat.mode)
    }

    @Test
    fun resizingNeverLeavesNegativeOrOffscreenCards() {
        for (width in listOf(320, 480, 600, 840, 1280)) for (height in listOf(320, 600, 900)) {
            val plan =
                AdaptiveLayout.calculate(
                    width,
                    height,
                    1f,
                    ScreenInsets(top = 24, bottom = 24),
                    700,
                )
            for (rect in listOf(plan.sheet, plan.tabs, plan.controls)) {
                assertTrue(rect.width > 0 && rect.height > 0)
                assertTrue(
                    rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height
                )
            }
        }
    }
}
