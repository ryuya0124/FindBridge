package dev.findbridge.android

import kotlin.math.abs

/** Release velocity selects the adjacent stop; slow drags settle at the nearest stop. */
internal object SheetDetents {
    fun target(height: Float, velocity: Float, stops: List<Float>, flingThreshold: Float): Float {
        val ordered = stops.distinct().sorted()
        require(ordered.isNotEmpty())
        return when {
            velocity > flingThreshold -> ordered.firstOrNull { it > height + 1f } ?: ordered.last()
            velocity < -flingThreshold -> ordered.lastOrNull { it < height - 1f } ?: ordered.first()
            else -> ordered.minBy { abs(it - height) }
        }
    }

    fun resist(height: Float, minimum: Float, maximum: Float, extent: Float): Float {
        val boundary = height.coerceIn(minimum, maximum)
        val excess = height - boundary
        return boundary + excess * .28f / (1f + abs(excess) / extent)
    }
}
