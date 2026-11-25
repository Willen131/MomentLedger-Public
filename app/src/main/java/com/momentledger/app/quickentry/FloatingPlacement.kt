package com.momentledger.app.quickentry

internal data class FloatingPoint(val x: Int, val y: Int)

internal enum class FloatingEdge { LEFT, RIGHT }

internal data class FloatingArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)

    fun clamp(x: Int, y: Int, childWidth: Int, childHeight: Int) = FloatingPoint(
        x.coerceIn(left, (right - childWidth).coerceAtLeast(left)),
        y.coerceIn(top, (bottom - childHeight).coerceAtLeast(top))
    )

    fun edge(x: Int, childWidth: Int, threshold: Int): FloatingEdge? {
        val fromLeft = (x - left).coerceAtLeast(0)
        val fromRight = (right - x - childWidth).coerceAtLeast(0)
        if (minOf(fromLeft, fromRight) > threshold) return null
        return if (fromLeft <= fromRight) FloatingEdge.LEFT else FloatingEdge.RIGHT
    }
}
