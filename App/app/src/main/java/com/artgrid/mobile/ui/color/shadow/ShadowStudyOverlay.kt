/**
 * ShadowStudyOverlay.kt
 * Responsibility : Draw construction guides and map handle ids to normalised layout updates.
 * Pattern used : Stateless drawing from [ShadowStudyLayout] + pixel hit tests for dragging.
 * Dependencies : Compose Canvas, [ShadowConstructionMath].
 */
package com.artgrid.mobile.ui.color.shadow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun ShadowStudyGuideLayer(
    layout: ShadowStudyLayout,
    lightType: LightSourceType,
    modifier: Modifier = Modifier,
) {
    val strokeVp = Stroke(width = 2.dp.value * 3f) // dp not available in drawScope without density — use hairline below
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val hair = (1.5f / density).coerceAtLeast(1f)

        fun nm(o: Offset): Offset = Offset(o.x * w, o.y * h)

        val lightPx = nm(layout.light)
        val footPx = nm(layout.floorFoot)
        val vpLPx = nm(layout.vpLeft)
        val vpRPx = nm(layout.vpRight)
        val hy = layout.horizonY.coerceIn(0.02f, 0.98f) * h

        val colVertical = Color(0xFFFFC107).copy(alpha = 0.92f)
        val colVp = Color(0xFF4FC3F7).copy(alpha = 0.85f)
        val colHorizon = Color(0xFF81C784).copy(alpha = 0.9f)
        val colSun = Color.White.copy(alpha = 0.38f)
        val colRay = Color(0xFFFF8A65).copy(alpha = 0.88f)
        val colShelf = Color(0xFFE1BEE7).copy(alpha = 0.9f)
        val colHandle = Color(0xFFFFEB3B).copy(alpha = 0.95f)

        // Vertical trace (light ↔ floor foot) — “drop” through the picture plane.
        drawLine(colVertical, lightPx, footPx, strokeWidth = hair * 2.5f)

        // Vanishing-point spokes through the light (wall / depth families).
        clipInfiniteLineToRect(Vec2(lightPx.x, lightPx.y), Vec2(vpLPx.x, vpLPx.y), w, h)?.let { (a, b) ->
            drawLine(colVp, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = hair * 2f)
        }
        clipInfiniteLineToRect(Vec2(lightPx.x, lightPx.y), Vec2(vpRPx.x, vpRPx.y), w, h)?.let { (a, b) ->
            drawLine(colVp, Offset(a.x, a.y), Offset(b.x, b.y), strokeWidth = hair * 2f)
        }

        // Horizon (elevation reference for wall receivers).
        drawLine(colHorizon, Offset(0f, hy), Offset(w, hy), strokeWidth = hair * 2f)

        val dPx = vec2Normalize(
            Vec2(layout.sunShadowDirection.x * w, layout.sunShadowDirection.y * h),
        )
        if (lightType == LightSourceType.SUN) {
            val perp = Vec2(-dPx.y, dPx.x)
            val origin = Vec2(lightPx.x, lightPx.y)
            val stepPx = 36.dp.toPx()
            val kMax = ((maxOf(w, h) / stepPx).toInt() + 8).coerceAtMost(40)
            for (k in -kMax..kMax) {
                val o = origin + perp * (k * stepPx)
                val seg = clipInfiniteLineToRect(
                    Vec2(o.x, o.y),
                    Vec2(o.x + dPx.x, o.y + dPx.y),
                    w,
                    h,
                ) ?: continue
                drawLine(
                    colSun,
                    Offset(seg.first.x, seg.first.y),
                    Offset(seg.second.x, seg.second.y),
                    strokeWidth = hair,
                )
            }
        } else {
            // Local sources: radial “cone” hints plus shelf corners when enabled.
            val spokes = 12
            for (i in 0 until spokes) {
                val ang = 2f * kotlin.math.PI.toFloat() * i / spokes
                val dir = Vec2(cos(ang), sin(ang))
                val ray = clipRayToRect(Vec2(lightPx.x, lightPx.y), dir, w, h) ?: continue
                drawLine(
                    colRay.copy(0.35f),
                    Offset(ray.first.x, ray.first.y),
                    Offset(ray.second.x, ray.second.y),
                    strokeWidth = hair,
                )
            }
        }

        if (layout.shelfEnabled && layout.shelfCorners.size == 4) {
            val pts = layout.shelfCorners.map { nm(it) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1..3) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, color = colShelf, style = Stroke(width = hair * 2f))
            if (lightType != LightSourceType.SUN) {
                for (c in pts) {
                    val dir = Vec2(c.x - lightPx.x, c.y - lightPx.y)
                    val seg = clipRayToRect(Vec2(lightPx.x, lightPx.y), dir, w, h) ?: continue
                    drawLine(
                        colRay,
                        Offset(seg.first.x, seg.first.y),
                        Offset(seg.second.x, seg.second.y),
                        strokeWidth = hair * 2f,
                    )
                }
            } else {
                for (c in pts) {
                    val seg = clipInfiniteLineToRect(
                        Vec2(c.x, c.y),
                        Vec2(c.x + dPx.x, c.y + dPx.y),
                        w,
                        h,
                    ) ?: continue
                    drawLine(
                        colSun.copy(0.55f),
                        Offset(seg.first.x, seg.first.y),
                        Offset(seg.second.x, seg.second.y),
                        strokeWidth = hair * 2f,
                    )
                }
            }
        }

        // Handles
        for ((id, o) in layout.handles()) {
            val p = nm(o)
            val r = 10.dp.toPx()
            val fill = when (id) {
                ShadowHandleId.Light -> Color(0xFFFFD54F)
                ShadowHandleId.FloorFoot -> Color(0xFFFFB74D)
                ShadowHandleId.VpLeft, ShadowHandleId.VpRight -> Color(0xFF4FC3F7)
                ShadowHandleId.Horizon -> Color(0xFF81C784)
                ShadowHandleId.SunShadowDir -> Color.White
                else -> colHandle
            }
            drawCircle(fill, r, p)
            drawCircle(Color.Black.copy(0.55f), r, p, style = Stroke(width = hair * 1.5f))
        }
    }
}

internal fun ShadowStudyLayout.handleOffsetNormalized(id: ShadowHandleId): Offset? =
    handles().find { it.first == id }?.second

internal fun nearestShadowHandle(
    tapPx: Offset,
    layout: ShadowStudyLayout,
    widthPx: Float,
    heightPx: Float,
    hitRadiusPx: Float,
): ShadowHandleId? {
    if (widthPx <= 0f || heightPx <= 0f) return null
    var best: ShadowHandleId? = null
    var bestD = hitRadiusPx * hitRadiusPx
    for ((id, o) in layout.handles()) {
        val hx = o.x * widthPx
        val hy = o.y * heightPx
        val dx = tapPx.x - hx
        val dy = tapPx.y - hy
        val d2 = dx * dx + dy * dy
        if (d2 <= bestD) {
            bestD = d2
            best = id
        }
    }
    return best
}

internal fun ShadowStudyLayout.withDragDelta(
    id: ShadowHandleId,
    deltaPx: Offset,
    widthPx: Float,
    heightPx: Float,
): ShadowStudyLayout {
    if (widthPx <= 0f || heightPx <= 0f) return this
    val cur = handleOffsetNormalized(id) ?: return this
    val px = Offset(cur.x * widthPx + deltaPx.x, cur.y * heightPx + deltaPx.y)
    val nm = when (id) {
        ShadowHandleId.VpLeft, ShadowHandleId.VpRight ->
            Offset(px.x / widthPx, px.y / heightPx)
        ShadowHandleId.Horizon ->
            Offset(0.92f, (px.y / heightPx).coerceIn(0.02f, 0.98f))
        ShadowHandleId.SunShadowDir ->
            Offset(px.x / widthPx, px.y / heightPx)
        ShadowHandleId.Light, ShadowHandleId.FloorFoot,
        ShadowHandleId.ShelfCorner0, ShadowHandleId.ShelfCorner1,
        ShadowHandleId.ShelfCorner2, ShadowHandleId.ShelfCorner3,
        ->
            Offset(
                (px.x / widthPx).coerceIn(0f, 1f),
                (px.y / heightPx).coerceIn(0f, 1f),
            )
    }
    return withHandle(id, nm)
}
