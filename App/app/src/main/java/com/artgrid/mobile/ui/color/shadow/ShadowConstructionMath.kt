/**
 * ShadowConstructionMath.kt
 * Responsibility : Pure 2D helpers to clip infinite lines and rays to a viewport for drawing.
 * Pattern used : Parametric line clipping (Liang–Barsky style on a long segment).
 * Dependencies : None.
 */
package com.artgrid.mobile.ui.color.shadow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2): Vec2 = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2): Vec2 = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float): Vec2 = Vec2(x * s, y * s)
}

internal fun vec2Length(v: Vec2): Float {
    val h = v.x * v.x + v.y * v.y
    if (h <= 1e-12f) return 0f
    return sqrt(h)
}

internal fun vec2Normalize(v: Vec2): Vec2 {
    val len = vec2Length(v)
    if (len <= 1e-12f) return Vec2(1f, 0f)
    return Vec2(v.x / len, v.y / len)
}

/**
 * Clips the infinite line through [p0] and [p1] to the axis-aligned rectangle
 * [0, width] × [0, height]. Returns null if the line is degenerate or misses the rect.
 */
internal fun clipInfiniteLineToRect(
    p0: Vec2,
    p1: Vec2,
    width: Float,
    height: Float,
): Pair<Vec2, Vec2>? {
    val dx = p1.x - p0.x
    val dy = p1.y - p0.y
    if (abs(dx) < 1e-6f && abs(dy) < 1e-6f) return null

    val huge = max(width, height) * 4f + 1f
    val d = vec2Normalize(Vec2(dx, dy))
    val a = Vec2(p0.x - d.x * huge, p0.y - d.y * huge)
    val b = Vec2(p0.x + d.x * huge, p0.y + d.y * huge)
    return liangBarskyClip(a, b, 0f, 0f, width, height)
}

/**
 * Ray from [origin] in direction [dir] (not required to be unit). Returns the segment
 * from the origin to the first exit point of the rectangle, or null if the ray does not
 * intersect the forward half-space inside the rect in a stable way.
 */
internal fun clipRayToRect(
    origin: Vec2,
    dir: Vec2,
    width: Float,
    height: Float,
): Pair<Vec2, Vec2>? {
    val d = vec2Normalize(dir)
    if (vec2Length(d) < 1e-6f) return null
    val huge = max(width, height) * 4f + 1f
    val end = Vec2(origin.x + d.x * huge, origin.y + d.y * huge)
    val clipped = liangBarskyClip(origin, end, 0f, 0f, width, height) ?: return null
    // Shorten to start at origin if the clip starts before origin along the ray.
    val (c0, c1) = clipped
    val t0 = projectParam(origin, d, c0)
    val t1 = projectParam(origin, d, c1)
    val tMin = max(0f, min(t0, t1))
    val tMax = max(t0, t1)
    if (tMax < 0f) return null
    val start = Vec2(origin.x + d.x * tMin, origin.y + d.y * tMin)
    val stop = Vec2(origin.x + d.x * tMax, origin.y + d.y * tMax)
    return start to stop
}

private fun projectParam(origin: Vec2, dirUnit: Vec2, p: Vec2): Float {
    // Solve origin + t * dirUnit ≈ p (least squares on 2D, exact when colinear)
    val vx = p.x - origin.x
    val vy = p.y - origin.y
    return vx * dirUnit.x + vy * dirUnit.y
}

/**
 * Liang–Barsky clipping of segment [p0,p1] to the rectangle [xmin,ymin]-[xmax,ymax].
 */
internal fun liangBarskyClip(
    p0: Vec2,
    p1: Vec2,
    xmin: Float,
    ymin: Float,
    xmax: Float,
    ymax: Float,
): Pair<Vec2, Vec2>? {
    val dx = p1.x - p0.x
    val dy = p1.y - p0.y
    var u1 = 0f
    var u2 = 1f

    fun clipTest(p: Float, q: Float): Boolean {
        if (abs(p) < 1e-9f) return q >= 0f
        val r = q / p
        when {
            p < 0 -> when {
                r > u2 -> return false
                r > u1 -> u1 = r
            }
            p > 0 -> when {
                r < u1 -> return false
                r < u2 -> u2 = r
            }
        }
        return true
    }

    if (!clipTest(-dx, p0.x - xmin)) return null
    if (!clipTest(dx, xmax - p0.x)) return null
    if (!clipTest(-dy, p0.y - ymin)) return null
    if (!clipTest(dy, ymax - p0.y)) return null
    if (u1 > u2) return null
    val a = Vec2(p0.x + u1 * dx, p0.y + u1 * dy)
    val b = Vec2(p0.x + u2 * dx, p0.y + u2 * dy)
    return a to b
}
