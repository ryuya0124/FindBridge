package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class SheetDetentsTest {
    private val stops = listOf(138f, 390f, 745f)

    @Test
    fun slowReleaseUsesNearestStopAndFlickUsesNextStop() {
        assertEquals(390f, SheetDetents.target(450f, 30f, stops, 520f))
        assertEquals(745f, SheetDetents.target(420f, 900f, stops, 520f))
        assertEquals(390f, SheetDetents.target(600f, -900f, stops, 520f))
        assertEquals(138f, SheetDetents.target(150f, -900f, stops, 520f))
    }

    @Test
    fun overscrollHasResistanceAndNeverEscapesItsElasticLimit() {
        assertEquals(300f, SheetDetents.resist(300f, 138f, 745f, 110f))
        val upper = SheetDetents.resist(1500f, 138f, 745f, 110f)
        val lower = SheetDetents.resist(-1000f, 138f, 745f, 110f)
        assertTrue(upper > 745f && upper < 776f)
        assertTrue(lower < 138f && lower > 107f)
    }
}
