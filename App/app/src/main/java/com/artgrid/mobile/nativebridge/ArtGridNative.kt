/**
 * ArtGridNative.kt
 * Responsibility : Kotlin JNI wrapper exposing all C++17 math pipelines as
 *                  suspend functions running on Dispatchers.Default.
 *
 * THREAD SAFETY:
 *   Every public function immediately suspends to Dispatchers.Default so that
 *   no heavy C++ computation ever runs on the Main thread. Results are returned
 *   to the caller's context (ViewModel) which then posts to StateFlow/UI.
 *
 * CRASH PREVENTION:
 *   - All native calls are wrapped in runCatching{} — a null result from JNI
 *     (C++ exception, OOM, bad input) becomes a Result.failure.
 *   - Image size is capped to 4000px on the longest side inside C++ before any
 *     heavy computation (see artgrid_math.h: MAX_PROCESSING_DIM).
 *   - ARGB_8888 format is enforced via copy() + reconfigure before passing to JNI.
 */
package com.artgrid.mobile.nativebridge

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Parsed result from the K–M solver.
 */
data class KmRecipeEntry(val name: String, val weight: Float)
data class KmResult(
    val similarityScore: Float,       // 0–100 %
    val recipe: List<KmRecipeEntry>,
)

/**
 * Parsed result from the pixel colour sampler.
 */
data class ColorSampleResult(
    val r: Float, val g: Float, val b: Float,         // sRGB normalised [0,1]
    val oklabL: Float, val oklabA: Float, val oklabB: Float,
    val hslH: Float,                                   // [0,1] mapped from [0,360)
    val hslS: Float, val hslL: Float,
    val kelvin: Float,
    val lightnessPct: Float,
)

object ArtGridNative {

    init {
        System.loadLibrary("artgrid-native")
    }

    // ── JNI declarations — called via JNI bridge (jni_bridge.cpp) ─────────────

    private external fun edgePipeline(
        src: Bitmap, sensitivity: Float, overlay: Boolean
    ): Bitmap?

    private external fun perspectiveCorrect(src: Bitmap): Bitmap?

    private external fun greyscaleOklab(src: Bitmap): Bitmap?

    private external fun nativeTonalHeatmap(src: Bitmap): Bitmap?

    private external fun nativeWhiteBalance(
        src: Bitmap, usePercentile: Boolean,
        greyR: Int, greyG: Int, greyB: Int
    ): Bitmap?

    private external fun invertLinear(src: Bitmap): Bitmap?

    private external fun kuwaharaFilter(src: Bitmap, radius: Int): Bitmap?

    /** Returns FloatArray(11): r,g,b(0-1), L,a,b(oklab), h,s,l(hsl), kelvin, lightnessP */
    private external fun nativeSampleColor(src: Bitmap, cx: Int, cy: Int, radius: Int): FloatArray?

    /** Returns JSON string with score and recipe */
    private external fun kmSolve(r: Int, g: Int, b: Int, medium: Int): String?

    /** Returns IntArray of ARGB-packed sRGB palette colours */
    private external fun paletteQuantise(src: Bitmap, nColors: Int): IntArray?

    // ── Public suspend API ────────────────────────────────────────────────────

    /**
     * Structural edge extraction
     * @param sensitivity λ ∈ [0.5, 3.0] — higher = more edges
     * @param overlay     true: white edges overlaid on source; false: white-on-black
     */
    suspend fun extractEdges(
        src: Bitmap,
        sensitivity: Float = 1.0f,
        overlay: Boolean = true,
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        runCatching {
            val argb = src.toArgb8888()
            edgePipeline(argb, sensitivity, overlay)
                ?: error("Edge pipeline returned null — check model inputs")
        }
    }

    /**
     * Perspective correction
     * Returns source bitmap unchanged if corner detection fails.
     */
    suspend fun correctPerspective(src: Bitmap): Result<Bitmap> =
        withContext(Dispatchers.Default) {
            runCatching {
                val argb = src.toArgb8888()
                perspectiveCorrect(argb) ?: argb
            }
        }

    /**
     * Greyscale via Oklab L-channel
     */
    suspend fun toGreyscale(src: Bitmap): Result<Bitmap> =
        withContext(Dispatchers.Default) {
            runCatching {
                greyscaleOklab(src.toArgb8888())
                    ?: error("Greyscale returned null")
            }
        }

    /**
     * Tonal value heatmap
     */
    suspend fun tonalHeatmap(src: Bitmap): Result<Bitmap> =
        withContext(Dispatchers.Default) {
            runCatching {
                nativeTonalHeatmap(src.toArgb8888())
                    ?: error("Tonal heatmap returned null")
            }
        }

    /**
     * White balance
     * @param usePercentile true: 98th-percentile auto mode; false: use greyPatch
     * @param greyPatch     sampled grey-patch pixel (sRGB 0-255), ignored if usePercentile=true
     */
    suspend fun whiteBalance(
        src: Bitmap,
        usePercentile: Boolean = true,
        greyR: Int = 128, greyG: Int = 128, greyB: Int = 128,
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        runCatching {
            nativeWhiteBalance(src.toArgb8888(), usePercentile, greyR, greyG, greyB)
                ?: error("White balance returned null")
        }
    }

    /**
     * Gamma-corrected linear inversion
     */
    suspend fun invertColors(src: Bitmap): Result<Bitmap> =
        withContext(Dispatchers.Default) {
            runCatching {
                invertLinear(src.toArgb8888())
                    ?: error("Invert returned null")
            }
        }

    /**
     * Kuwahara edge-preserving simplification
     * @param radius neighbourhood radius, 1–8 (default 3)
     */
    suspend fun kuwaharaSimplify(src: Bitmap, radius: Int = 3): Result<Bitmap> =
        withContext(Dispatchers.Default) {
            runCatching {
                kuwaharaFilter(src.toArgb8888(), radius.coerceIn(1, 8))
                    ?: error("Kuwahara returned null")
            }
        }

    /**
     * Pixel colour sampler (Chamfer-distance aperture)
     * @param cx, cy  aperture centre in bitmap pixels
     * @param radius  aperture radius in pixels (default 6)
     */
    suspend fun sampleColor(
        src: Bitmap,
        cx: Int, cy: Int,
        radius: Int = 6,
    ): Result<ColorSampleResult> = withContext(Dispatchers.Default) {
        runCatching {
            val arr = nativeSampleColor(src.toArgb8888(), cx, cy, radius)
                ?: error("sampleColor returned null")
            ColorSampleResult(
                r = arr[0], g = arr[1], b = arr[2],
                oklabL = arr[3], oklabA = arr[4], oklabB = arr[5],
                hslH = arr[6], hslS = arr[7], hslL = arr[8],
                kelvin = arr[9],
                lightnessPct = arr[10],
            )
        }
    }

    /**
     * Kubelka–Munk paint mix suggestion
     * @param r, g, b   target sRGB colour (0–255)
     * @param medium    0 = watercolour, 1 = acrylic
     */
    suspend fun solvePaintMix(
        r: Int, g: Int, b: Int,
        medium: Int = 0,
    ): Result<KmResult> = withContext(Dispatchers.Default) {
        runCatching {
            val json = kmSolve(r, g, b, medium)
                ?: error("kmSolve returned null")
            parseKmJson(json)
        }
    }

    /**
     * Dominant palette extraction
     * @param nColors   number of palette entries, 2–12 (default 6)
     */
    suspend fun extractPalette(src: Bitmap, nColors: Int = 6): Result<List<Int>> =
        withContext(Dispatchers.Default) {
            runCatching {
                val arr = paletteQuantise(src.toArgb8888(), nColors.coerceIn(2, 12))
                    ?: error("paletteQuantise returned null")
                arr.toList()
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Ensures the bitmap is ARGB_8888 (required by the native code).
     * If it already is, returns [this] unchanged (no copy).
     */
    private fun Bitmap.toArgb8888(): Bitmap {
        if (config == Bitmap.Config.ARGB_8888) return this
        return copy(Bitmap.Config.ARGB_8888, /*isMutable=*/false)
    }

    /** Parses the compact JSON returned by kmSolve JNI function. */
    private fun parseKmJson(json: String): KmResult {
        val obj = JSONObject(json)
        val score = obj.optDouble("score", 0.0).toFloat()
        val arr = obj.optJSONArray("recipe")
        val recipe = buildList {
            if (arr != null) {
                repeat(arr.length()) { i ->
                    val entry = arr.getJSONObject(i)
                    add(KmRecipeEntry(
                        name = entry.getString("name"),
                        weight = entry.getDouble("weight").toFloat(),
                    ))
                }
            }
        }
        return KmResult(similarityScore = score, recipe = recipe)
    }
}
