package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class MarkerGroupsTest {
    @Test
    fun identicalPositionsBecomeOneGroupWithoutLosingMembers() {
        val points = (0 until 40).map { MarkerPoint("device-$it", 12.0, 25.0) }
        assertEquals(1, MarkerGroups.collect(points, 64.0).size)
        assertEquals(
            points.map { it.id }.toSet(),
            MarkerGroups.collect(points, 64.0).single().map { it.id }.toSet(),
        )
    }

    @Test
    fun zoomingApartSeparatesGroupsAndInputOrderDoesNotChangeMembership() {
        val points =
            listOf(
                MarkerPoint("a", -5.0, -5.0),
                MarkerPoint("b", 10.0, 10.0),
                MarkerPoint("c", 300.0, 300.0),
            )
        val near = MarkerGroups.collect(points, 64.0).map { it.map { p -> p.id }.toSet() }.toSet()
        assertEquals(setOf(setOf("a", "b"), setOf("c")), near)
        assertEquals(
            near,
            MarkerGroups.collect(points.reversed(), 64.0)
                .map { it.map { p -> p.id }.toSet() }
                .toSet(),
        )
        assertEquals(
            3,
            MarkerGroups.collect(points.map { it.copy(x = it.x * 10, y = it.y * 10) }, 64.0).size,
        )
    }

    @Test
    fun neighboringCellsAndNonFiniteProjectionAreHandled() {
        val points =
            listOf(
                MarkerPoint("a", 63.0, 63.0),
                MarkerPoint("b", 65.0, 65.0),
                MarkerPoint("invalid", Double.NaN, 0.0),
            )
        assertEquals(
            setOf("a", "b"),
            MarkerGroups.collect(points, 64.0).single().map { it.id }.toSet(),
        )
    }
}
