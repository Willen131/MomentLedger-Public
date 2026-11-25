package com.momentledger.app.quickentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingPlacementTest {
    @Test
    fun dragStaysWithinBothVisibleAxes() {
        val area = FloatingArea(12, 45, 408, 780)
        assertEquals(FloatingPoint(12, 45), area.clamp(-100, 5, 72, 72))
        assertEquals(FloatingPoint(336, 708), area.clamp(900, 900, 72, 72))
    }

    @Test
    fun onlyNearbyEdgesFold() {
        val area = FloatingArea(0, 0, 400, 800)
        assertEquals(FloatingEdge.LEFT, area.edge(26, 72, 30))
        assertEquals(FloatingEdge.RIGHT, area.edge(300, 72, 30))
        assertNull(area.edge(160, 72, 30))
    }

    @Test
    fun viewportSmallerThanWidgetKeepsStartVisible() {
        val area = FloatingArea(20, 10, 60, 70)
        assertEquals(FloatingPoint(20, 10), area.clamp(100, 200, 170, 150))
        assertEquals(FloatingEdge.LEFT, area.edge(20, 170, 30))
    }
}
