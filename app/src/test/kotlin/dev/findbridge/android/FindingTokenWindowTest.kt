package dev.findbridge.android

import org.junit.Assert.*
import org.junit.Test

class FindingTokenWindowTest {
    @Test
    fun staticModelBoundariesUseReferenceEpochAndOneBasedIndices() {
        val minutes = 10_080L // The inspected Mac fallback, not an inferred iPhone setting.
        val duration = minutes * 60.0
        val cases =
            listOf(
                Case(123.5, 123.5, minutes, 1uL, 0.0),
                Case(123.5, -500.0, minutes, 1uL, 0.0),
                Case(123.5, duration - 1, minutes, 1uL, 0.0),
                Case(123.5, duration, minutes, 2uL, duration),
                Case(duration + 123.5, 2 * duration, minutes, 2uL, 2 * duration),
                Case(-1.0, -1.0, minutes, 1uL, -duration),
                Case(-1.0, 0.0, minutes, 2uL, 0.0),
                Case(123.5, 180.0, 1, 2uL, 180.0),
            )
        for (case in cases) {
            val window = FindingTokenWindow.calculate(case.base, case.date, case.minutes)
            assertEquals(case.index, window.index)
            assertEquals(case.start, window.startReferenceSeconds, 0.0)
            val boundary = 978_307_200.0 + case.start + case.minutes * 60.0
            assertEquals(boundary, Math.nextUp(window.endUnixSeconds), 0.0)
        }
    }

    @Test
    fun noDefaultIntervalOrInvalidDatesCanSelectATokenSilently() {
        for (date in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) {
                FindingTokenWindow.calculate(0.0, date, 1)
            }
        }
        for (minutes in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                FindingTokenWindow.calculate(0.0, 1.0, minutes)
            }
        }
    }

    private data class Case(
        val base: Double,
        val date: Double,
        val minutes: Long,
        val index: ULong,
        val start: Double,
    )
}
