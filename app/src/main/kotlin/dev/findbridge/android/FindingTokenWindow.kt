package dev.findbridge.android

import kotlin.math.floor
import kotlin.math.max

/** Static 26B123 bucket model. The actual peer's roll interval must be supplied explicitly. */
internal data class FindingTokenWindow(
    val index: ULong,
    val startReferenceSeconds: Double,
    val endUnixSeconds: Double,
) {
    companion object {
        private const val REFERENCE_UNIX_OFFSET = 978_307_200.0

        fun calculate(
            baseReferenceSeconds: Double,
            dateReferenceSeconds: Double,
            minutes: Long,
        ): FindingTokenWindow {
            require(baseReferenceSeconds.isFinite() && dateReferenceSeconds.isFinite())
            require(minutes > 0)
            val duration = minutes.toDouble() * 60.0
            val anchor = floor(baseReferenceSeconds / duration) * duration
            val quotient = (max(baseReferenceSeconds, dateReferenceSeconds) - anchor) / duration
            require(quotient.isFinite() && quotient >= 0 && quotient < ULong.MAX_VALUE.toDouble())
            val index = quotient.toULong() + 1uL
            check(index != 0uL)
            val start = anchor + duration * (index - 1uL).toDouble()
            val boundaryUnix = REFERENCE_UNIX_OFFSET + start + duration
            require(start.isFinite() && boundaryUnix.isFinite())
            return FindingTokenWindow(
                index,
                start,
                Math.nextAfter(boundaryUnix, Double.NEGATIVE_INFINITY),
            )
        }
    }
}
