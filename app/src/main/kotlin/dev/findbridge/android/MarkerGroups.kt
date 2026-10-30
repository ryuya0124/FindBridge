package dev.findbridge.android

import kotlin.math.floor

internal data class MarkerPoint(val id: String, val x: Double, val y: Double)

/** Screen-space groups; original device coordinates are never changed. */
internal object MarkerGroups {
    fun collect(points: List<MarkerPoint>, distance: Double): List<List<MarkerPoint>> {
        require(distance.isFinite() && distance > 0)
        val ordered = points.filter { it.x.isFinite() && it.y.isFinite() }.sortedBy { it.id }
        val parents = IntArray(ordered.size) { it }
        fun root(value: Int): Int {
            var index = value
            while (parents[index] != index) {
                parents[index] = parents[parents[index]]
                index = parents[index]
            }
            return index
        }
        val cells = mutableMapOf<Pair<Long, Long>, MutableList<Int>>()
        ordered.forEachIndexed { index, point ->
            val x = floor(point.x / distance).toLong()
            val y = floor(point.y / distance).toLong()
            for (dx in -1L..1L) for (dy in -1L..1L) for (other in
                cells[x + dx to y + dy].orEmpty()) {
                val deltaX = point.x - ordered[other].x
                val deltaY = point.y - ordered[other].y
                if (deltaX * deltaX + deltaY * deltaY < distance * distance)
                    parents[root(index)] = root(other)
            }
            cells.getOrPut(x to y) { mutableListOf() }.add(index)
        }
        return ordered.indices
            .groupBy { root(it) }
            .values
            .map { indices -> indices.map(ordered::get) }
    }
}
