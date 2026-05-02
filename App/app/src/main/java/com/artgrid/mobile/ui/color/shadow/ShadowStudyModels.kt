/**
 * ShadowStudyModels.kt
 * Responsibility : Domain types for the shadow-construction study overlay (artist guides).
 * Pattern used : Immutable state snapshot + light-source taxonomy for presets.
 * Dependencies : None (pure Kotlin).
 */
package com.artgrid.mobile.ui.color.shadow

import androidx.compose.ui.geometry.Offset

/**
 * Lighting taxonomy for presets and copy only. All three share the same 2D construction
 * handles; behaviour differs for ray families (parallel vs radial) and default layout.
 */
enum class LightSourceType(
    val displayLabel: String,
    val helperText: String,
) {
    SUN(
        displayLabel = "Sun",
        helperText = "Distant light: shadow families are parallel on each receiver plane in correct linear perspective. Here you set a 2D shadow direction as a practical stand-in for the sun’s azimuth projection.",
    ),
    STREET_LAMP(
        displayLabel = "Street lamp",
        helperText = "Local fixture, usually elevated. Uses the same radial rays as a bulb; place the light high and widen the shelf quad to match the pole or canopy.",
    ),
    BULB(
        displayLabel = "Bulb / indoor",
        helperText = "Point-like source: shadow rays on planes radiate from the light through each obstacle corner (classic atelier construction).",
    ),
}

/** Draggable construction handles on the image overlay. */
enum class ShadowHandleId {
    Light,
    FloorFoot,
    VpLeft,
    VpRight,
    Horizon,
    SunShadowDir,
    ShelfCorner0,
    ShelfCorner1,
    ShelfCorner2,
    ShelfCorner3,
}

/**
 * Normalised layout (0–1 inside the image view). Vanishing points may sit outside 0–1.
 */
internal data class ShadowStudyLayout(
    val light: Offset = Offset(0.5f, 0.22f),
    val floorFoot: Offset = Offset(0.5f, 0.78f),
    val vpLeft: Offset = Offset(-0.2f, 0.52f),
    val vpRight: Offset = Offset(1.2f, 0.52f),
    val horizonY: Float = 0.42f,
    /** Unit direction in normalised space for parallel (sun) shadow hatching. */
    val sunShadowDirection: Offset = Offset(0.55f, 0.35f),
    val shelfEnabled: Boolean = false,
    val shelfCorners: List<Offset> = defaultShelfCorners(),
) {
    fun handles(): List<Pair<ShadowHandleId, Offset>> = buildList {
        add(ShadowHandleId.Light to light)
        add(ShadowHandleId.FloorFoot to floorFoot)
        add(ShadowHandleId.VpLeft to vpLeft)
        add(ShadowHandleId.VpRight to vpRight)
        add(ShadowHandleId.Horizon to Offset(0.92f, horizonY.coerceIn(0.02f, 0.98f)))
        add(ShadowHandleId.SunShadowDir to Offset(light.x + sunShadowDirection.x, light.y + sunShadowDirection.y))
        if (shelfEnabled && shelfCorners.size == 4) {
            add(ShadowHandleId.ShelfCorner0 to shelfCorners[0])
            add(ShadowHandleId.ShelfCorner1 to shelfCorners[1])
            add(ShadowHandleId.ShelfCorner2 to shelfCorners[2])
            add(ShadowHandleId.ShelfCorner3 to shelfCorners[3])
        }
    }

    fun withHandle(id: ShadowHandleId, value: Offset): ShadowStudyLayout = when (id) {
        ShadowHandleId.Light -> copy(light = clamp01(value))
        ShadowHandleId.FloorFoot -> copy(floorFoot = clamp01(value))
        ShadowHandleId.VpLeft -> copy(vpLeft = value)
        ShadowHandleId.VpRight -> copy(vpRight = value)
        ShadowHandleId.Horizon -> copy(horizonY = value.y.coerceIn(0.02f, 0.98f))
        ShadowHandleId.SunShadowDir -> {
            val dir = Offset(value.x - light.x, value.y - light.y)
            copy(sunShadowDirection = dir)
        }
        ShadowHandleId.ShelfCorner0 -> replaceShelfCorner(0, value)
        ShadowHandleId.ShelfCorner1 -> replaceShelfCorner(1, value)
        ShadowHandleId.ShelfCorner2 -> replaceShelfCorner(2, value)
        ShadowHandleId.ShelfCorner3 -> replaceShelfCorner(3, value)
    }

    private fun replaceShelfCorner(index: Int, value: Offset): ShadowStudyLayout {
        if (shelfCorners.size != 4) return this
        val next = shelfCorners.toMutableList()
        next[index] = clamp01(value)
        return copy(shelfCorners = next)
    }

    companion object {
        fun forType(type: LightSourceType): ShadowStudyLayout = when (type) {
            LightSourceType.SUN -> ShadowStudyLayout(
                light = Offset(0.55f, 0.18f),
                floorFoot = Offset(0.55f, 0.82f),
                vpLeft = Offset(-0.25f, 0.5f),
                vpRight = Offset(1.25f, 0.5f),
                horizonY = 0.45f,
                sunShadowDirection = Offset(0.45f, 0.5f),
            )
            LightSourceType.STREET_LAMP -> ShadowStudyLayout(
                light = Offset(0.5f, 0.08f),
                floorFoot = Offset(0.5f, 0.85f),
                vpLeft = Offset(-0.15f, 0.55f),
                vpRight = Offset(1.15f, 0.55f),
                horizonY = 0.48f,
                sunShadowDirection = Offset(0.2f, 0.55f),
                shelfEnabled = true,
                shelfCorners = defaultShelfCorners(center = Offset(0.35f, 0.42f)),
            )
            LightSourceType.BULB -> ShadowStudyLayout(
                light = Offset(0.5f, 0.2f),
                floorFoot = Offset(0.5f, 0.8f),
                vpLeft = Offset(-0.1f, 0.52f),
                vpRight = Offset(1.1f, 0.52f),
                horizonY = 0.44f,
                sunShadowDirection = Offset(0.35f, 0.4f),
                shelfEnabled = true,
                shelfCorners = defaultShelfCorners(center = Offset(0.28f, 0.48f)),
            )
        }
    }
}

private fun defaultShelfCorners(center: Offset = Offset(0.32f, 0.45f)): List<Offset> {
    val s = 0.08f
    return listOf(
        Offset(center.x - s, center.y - s * 0.6f),
        Offset(center.x + s, center.y - s * 0.6f),
        Offset(center.x + s, center.y + s * 0.6f),
        Offset(center.x - s, center.y + s * 0.6f),
    )
}

private fun clamp01(o: Offset): Offset = Offset(o.x.coerceIn(0f, 1f), o.y.coerceIn(0f, 1f))
