/**
 * ShadowConstructionMathTest.kt
 * Responsibility : Regression tests for line clipping helpers used by the shadow overlay.
 * Pattern used : JUnit4 assertions on pure geometry.
 * Dependencies : JUnit, [ShadowConstructionMath].
 */
package com.artgrid.mobile.ui.color.shadow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowConstructionMathTest {

    @Test
    fun liangBarskyClip_horizontalSegmentInside() {
        val seg = liangBarskyClip(
            p0 = Vec2(10f, 50f),
            p1 = Vec2(200f, 50f),
            xmin = 0f,
            ymin = 0f,
            xmax = 100f,
            ymax = 100f,
        )
        assertNotNull(seg)
        val (a, b) = seg!!
        assertEquals(10f, a.x, 1e-4f)
        assertEquals(50f, a.y, 1e-4f)
        assertEquals(100f, b.x, 1e-4f)
        assertEquals(50f, b.y, 1e-4f)
    }

    @Test
    fun clipInfiniteLineToRect_diagonalCrossesFullWidth() {
        val seg = clipInfiniteLineToRect(
            p0 = Vec2(0f, 0f),
            p1 = Vec2(10f, 10f),
            width = 100f,
            height = 100f,
        )
        assertNotNull(seg)
        val (a, b) = seg!!
        val minX = minOf(a.x, b.x)
        val maxX = maxOf(a.x, b.x)
        assertTrue(minX <= 1f)
        assertTrue(maxX >= 99f)
    }

    @Test
    fun clipRayToRect_forwardHitsOppositeEdge() {
        val seg = clipRayToRect(
            origin = Vec2(10f, 10f),
            dir = Vec2(1f, 0f),
            width = 100f,
            height = 100f,
        )
        assertNotNull(seg)
        val (a, b) = seg!!
        assertEquals(10f, a.x, 1e-3f)
        assertEquals(10f, a.y, 1e-3f)
        assertEquals(100f, b.x, 1e-3f)
        assertEquals(10f, b.y, 1e-3f)
    }
}
