/**
 * ColorPaletteData.kt
 * Responsibility : Curated artist colour palette groups for ColorPaletteScreen.
 *                  8 groups × 16 colours each. All values are ARGB Int.
 */
package com.artgrid.mobile.ui.color

data class ColorGroup(
    val name: String,
    val emoji: String,
    val colors: List<Int>, // ARGB Int values
)

// Helper to compactly write pigment colours
private fun c(hex: Long): Int = hex.toInt()

val ARTIST_PALETTE_GROUPS: List<ColorGroup> = listOf(

    ColorGroup("Warm", "🔥", listOf(
        c(0xFFFFFFFF), // placeholder replaced below
        c(0xFFFF1744), // Alizarin Crimson
        c(0xFFE53935), // Cadmium Red
        c(0xFFFF5722), // Vermilion
        c(0xFFFF6D00), // Cadmium Orange
        c(0xFFFFA000), // Amber
        c(0xFFFFD600), // Cadmium Yellow
        c(0xFFFFF176), // Naples Yellow
        c(0xFF9B2335), // Deep Alizarin
        c(0xFFBF360C), // Burnt Orange
        c(0xFFC62828), // Dark Red
        c(0xFFE65100), // Dark Orange
        c(0xFFFF8F00), // Dark Amber
        c(0xFFFFCA28), // Golden Yellow
        c(0xFFFFEE58), // Light Yellow
        c(0xFFF9A825), // Dark Golden Yellow
    ).drop(1)), // drop placeholder

    ColorGroup("Cool", "❄️", listOf(
        c(0xFF0D47A1), // Prussian Blue
        c(0xFF1565C0), // Ultramarine
        c(0xFF1976D2), // Cobalt Blue
        c(0xFF039BE5), // Cerulean Blue
        c(0xFF00ACC1), // Cyan Pigment
        c(0xFF00897B), // Viridian
        c(0xFF2E7D32), // Sap Green
        c(0xFF4527A0), // Indigo
        c(0xFF6A1B9A), // Dioxazine Purple
        c(0xFF37474F), // Payne's Grey
        c(0xFF1A237E), // Dark Indigo
        c(0xFF006064), // Deep Teal
        c(0xFF004D40), // Dark Viridian
        c(0xFF1B5E20), // Dark Sap Green
        c(0xFF0277BD), // Phthalo Blue
        c(0xFF00695C), // Phthalo Green
    )),

    ColorGroup("Earth", "🌍", listOf(
        c(0xFFD4A853), // Yellow Ochre
        c(0xFFC5873A), // Raw Sienna
        c(0xFF8D4E28), // Burnt Sienna
        c(0xFF6B3728), // Burnt Umber
        c(0xFF4E3524), // Raw Umber
        c(0xFF795548), // Warm Brown
        c(0xFF5D4037), // Dark Brown
        c(0xFF3E2723), // Van Dyke Brown
        c(0xFFA0522D), // Sienna
        c(0xFF8B6914), // Dark Yellow Ochre
        c(0xFFBF8E50), // Naples Yellow Earth
        c(0xFF9E7B4A), // Khaki Brown
        c(0xFF7B5E2A), // Dark Raw Sienna
        c(0xFF80511F), // Copper
        c(0xFFC17D11), // Gold Ochre
        c(0xFFAD7A4E), // Sandstone
    )),

    ColorGroup("Skin", "🧑", listOf(
        c(0xFFFFF8F0), // Porcelain
        c(0xFFF8DDD0), // Ivory
        c(0xFFF5C9A8), // Fair
        c(0xFFF0B98A), // Light Beige
        c(0xFFEBA872), // Warm Peach
        c(0xFFE59866), // Sand
        c(0xFFD4884E), // Honey
        c(0xFFC07838), // Caramel
        c(0xFFAD6428), // Tawny
        c(0xFF8D5524), // Walnut
        c(0xFF7B4512), // Chestnut
        c(0xFF6B3410), // Espresso
        c(0xFF5C2E0B), // Dark Brown
        c(0xFF4A2309), // Mahogany
        c(0xFF3D1F0D), // Sepia
        c(0xFF2C1503), // Ebony
    )),

    ColorGroup("Pastel", "🍬", listOf(
        c(0xFFFFB3BA), // Pastel Pink
        c(0xFFFFCCBA), // Pastel Peach
        c(0xFFFFDFBA), // Pastel Orange
        c(0xFFFFF8BA), // Pastel Yellow
        c(0xFFDFFFBA), // Pastel Lime
        c(0xFFBAFFD0), // Pastel Mint
        c(0xFFBAF0FF), // Pastel Sky
        c(0xFFBAD0FF), // Pastel Cornflower
        c(0xFFCFBAFF), // Pastel Lavender
        c(0xFFE8BAFF), // Pastel Violet
        c(0xFFFFBAF5), // Pastel Rose
        c(0xFFFFC5D0), // Baby Pink
        c(0xFFEEFFC5), // Honeydew
        c(0xFFC5FFEE), // Aqua Ice
        c(0xFFC5DEFF), // Ice Blue
        c(0xFFD9C5FF), // Wisteria
    )),

    ColorGroup("Neon", "⚡", listOf(
        c(0xFFFF073A), // Neon Red
        c(0xFFFF6B08), // Neon Orange
        c(0xFFFFDE00), // Neon Yellow
        c(0xFF39FF14), // Neon Green
        c(0xFF0AFFF0), // Neon Cyan
        c(0xFF00A3FF), // Neon Blue
        c(0xFF7F00FF), // Neon Violet
        c(0xFFFF00FF), // Neon Magenta
        c(0xFFFF1493), // Neon Deep Pink
        c(0xFFFF4444), // Hot Red
        c(0xFFADFF2F), // Green Yellow
        c(0xFF7FFF00), // Chartreuse
        c(0xFF00FF7F), // Spring Green
        c(0xFF00CFFF), // Electric Blue
        c(0xFFBC13FE), // Electric Purple
        c(0xFFFF00C8), // Electric Pink
    )),

    ColorGroup("Greys", "⬜", listOf(
        c(0xFFFFFFFF), // White
        c(0xFFF5F5F5), // Warm White
        c(0xFFEEEEEE), // Off White
        c(0xFFE0E0E0), // Light Grey
        c(0xFFBDBDBD), // Silver
        c(0xFF9E9E9E), // Medium Grey
        c(0xFF757575), // Dark Grey
        c(0xFF616161), // Charcoal Light
        c(0xFF424242), // Charcoal
        c(0xFF303030), // Near Black
        c(0xFF212121), // Almost Black
        c(0xFF000000), // Ivory Black (pigment)
        c(0xFFF2EFE9), // Warm Near-White
        c(0xFFD6D0C4), // Warm Silver
        c(0xFF938E82), // Warm Grey
        c(0xFF4A463F), // Warm Charcoal
    )),

    ColorGroup("Rainbow", "🌈", listOf(
        c(0xFFFF0000), // Red
        c(0xFFFF3300), // Red-Orange
        c(0xFFFF6600), // Orange
        c(0xFFFF9900), // Yellow-Orange
        c(0xFFFFCC00), // Yellow
        c(0xFFCCFF00), // Yellow-Green
        c(0xFF66FF00), // Green-Yellow
        c(0xFF00FF00), // Green
        c(0xFF00FF66), // Green-Cyan
        c(0xFF00FFCC), // Cyan-Green
        c(0xFF00CCFF), // Cyan
        c(0xFF0066FF), // Blue-Cyan
        c(0xFF0000FF), // Blue
        c(0xFF6600FF), // Blue-Violet
        c(0xFFCC00FF), // Violet
        c(0xFFFF00CC), // Magenta
    )),
)
