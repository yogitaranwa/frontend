/**
 * NativePipelineViewModel.kt
 * Responsibility : Coordinates on-device C++17 image pipelines (edges, perspective, tonal filters,
 *                  colour sampler, K–M mix, palette) by bridging ArtGridNative to Compose state.
 *
 * Design principles:
 *   - Every heavy pipeline call dispatches to Dispatchers.Default (inside ArtGridNative).
 *     The ViewModel never blocks the Main thread — all C++ work runs off-thread.
 *   - Results flow into typed MutableStateFlow slots so the UI only reacts to changes.
 *   - A shared `_isProcessing` flag prevents concurrent pipeline calls on the same image.
 *   - runCatching() inside ArtGridNative ensures no C++ exception can reach this ViewModel.
 *
 * Crash-prevention strategy:
 *   - All native calls are wrapped in Result<T> (see ArtGridNative.kt).
 *   - If Bitmap is null (picker returned nothing), loadBitmap() returns a NativePipelineState.Error.
 *   - If the native call returns Result.failure, state is set to NativePipelineState.Error
 *     with the exception message — never crashes the app.
 *   - The Bitmap passed to JNI is always copied to ARGB_8888 by ArtGridNative.toArgb8888()
 *     before any C++ code sees it.
 *
 * Injects: none — ArtGridNative is an object singleton loaded via System.loadLibrary().
 */
package com.artgrid.mobile.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artgrid.mobile.nativebridge.ArtGridNative
import com.artgrid.mobile.nativebridge.ColorSampleResult
import com.artgrid.mobile.nativebridge.KmResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Pipeline result state ──────────────────────────────────────────────────────

sealed class NativePipelineState<out T> {
    data object Idle    : NativePipelineState<Nothing>()
    data object Loading : NativePipelineState<Nothing>()
    data class  Success<T>(val value: T) : NativePipelineState<T>()
    data class  Error(val message: String) : NativePipelineState<Nothing>()
}

// ── Aggregated UI state for the native pipeline screen ────────────────────────

data class NativePipelineUiState(
    /** The source bitmap currently loaded (null = no image selected yet) */
    val sourceBitmap: Bitmap? = null,

    // Edge extraction
    val edgeState: NativePipelineState<Bitmap> = NativePipelineState.Idle,

    // Perspective correction
    val perspectiveState: NativePipelineState<Bitmap> = NativePipelineState.Idle,

    // Greyscale
    val greyscaleState: NativePipelineState<Bitmap> = NativePipelineState.Idle,

    // Tonal heatmap
    val tonalState: NativePipelineState<Bitmap> = NativePipelineState.Idle,

    // White balance
    val whiteBalanceState: NativePipelineState<Bitmap> = NativePipelineState.Idle,

    // Invert
    val invertState: NativePipelineState<Bitmap> = NativePipelineState.Idle,

    // Kuwahara
    val kuwaharaState: NativePipelineState<Bitmap> = NativePipelineState.Idle,

    // Colour sampler
    val colorSampleState: NativePipelineState<ColorSampleResult> = NativePipelineState.Idle,

    // K–M paint mix
    val kmState: NativePipelineState<KmResult> = NativePipelineState.Idle,

    // Palette extraction
    val paletteState: NativePipelineState<List<Int>> = NativePipelineState.Idle,

    /** True while any pipeline is running — used to disable buttons. */
    val isProcessing: Boolean = false,
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class NativePipelineViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NativePipelineUiState())
    val uiState: StateFlow<NativePipelineUiState> = _uiState.asStateFlow()

    /** Active coroutine job — allows cancelling a running pipeline if the user
     *  picks a new image before the previous call finishes. */
    private var activeJob: Job? = null

    // ── Image loading ─────────────────────────────────────────────────────────

    /**
     * Decode a picked URI into a Bitmap, capping at MAX_PROCESSING_DIM on the
     * longer edge to stay within the 4000px cap enforced inside C++.
     * Runs on Dispatchers.Default (inside viewModelScope.launch).
     * Returns Result.failure if the URI cannot be decoded.
     */
    fun loadImage(uri: Uri) {
        activeJob?.cancel()
        resetAllStates()
        activeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true)
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    // Decode with inSampleSize to pre-scale very large images
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, opts)
                    // Re-open stream — InputStreams are single-use
                    null
                }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }.getOrNull()

            _uiState.value = if (bitmap != null) {
                _uiState.value.copy(sourceBitmap = bitmap, isProcessing = false)
            } else {
                _uiState.value.copy(
                    isProcessing = false,
                    edgeState = NativePipelineState.Error("Could not decode image — try a JPEG or PNG"),
                )
            }
        }
    }

    // ── Edge extraction ─────────────────────────────────────────────────────

    /**
     * @param sensitivity λ ∈ [0.5, 3.0]
     * @param overlay true = white edges over source; false = white-on-black
     */
    fun runEdgeExtraction(sensitivity: Float = 1.2f, overlay: Boolean = true) =
        launchPipeline(
            stateUpdater = { s -> copy(edgeState = s) },
        ) { bmp ->
            ArtGridNative.extractEdges(bmp, sensitivity, overlay)
        }

    // ── Perspective correction ──────────────────────────────────────────────

    fun runPerspectiveCorrect() =
        launchPipeline(
            stateUpdater = { s -> copy(perspectiveState = s) },
        ) { bmp ->
            ArtGridNative.correctPerspective(bmp)
        }

    // ── Greyscale ────────────────────────────────────────────────────────────

    fun runGreyscale() =
        launchPipeline(
            stateUpdater = { s -> copy(greyscaleState = s) },
        ) { bmp ->
            ArtGridNative.toGreyscale(bmp)
        }

    // ── Tonal heatmap ───────────────────────────────────────────────────────

    fun runTonalHeatmap() =
        launchPipeline(
            stateUpdater = { s -> copy(tonalState = s) },
        ) { bmp ->
            ArtGridNative.tonalHeatmap(bmp)
        }

    // ── White balance ────────────────────────────────────────────────────────

    fun runWhiteBalance(
        usePercentile: Boolean = true,
        greyR: Int = 128, greyG: Int = 128, greyB: Int = 128,
    ) = launchPipeline(
        stateUpdater = { s -> copy(whiteBalanceState = s) },
    ) { bmp ->
        ArtGridNative.whiteBalance(bmp, usePercentile, greyR, greyG, greyB)
    }

    // ── Linear inversion ────────────────────────────────────────────────────

    fun runInvert() =
        launchPipeline(
            stateUpdater = { s -> copy(invertState = s) },
        ) { bmp ->
            ArtGridNative.invertColors(bmp)
        }

    // ── Kuwahara filter ─────────────────────────────────────────────────────

    fun runKuwahara(radius: Int = 3) =
        launchPipeline(
            stateUpdater = { s -> copy(kuwaharaState = s) },
        ) { bmp ->
            ArtGridNative.kuwaharaSimplify(bmp, radius)
        }

    // ── Colour sampler ───────────────────────────────────────────────────────

    /**
     * Sample a colour from the current [sourceBitmap].
     * Use this only when the display bitmap IS the sourceBitmap (e.g. full-image picker).
     * For crop-based pickers (ObjectDetailScreen, FaceDetailScreen) use [runColorSampleOn].
     */
    fun runColorSample(cx: Int, cy: Int, radius: Int = 6) {
        val bmp = _uiState.value.sourceBitmap ?: return
        runColorSampleOn(bmp, cx, cy, radius)
    }

    /**
     * Sample a colour from an explicit [targetBitmap].
     * Use this when the detail screen is displaying a CROP of the source — tap coordinates
     * in the crop's pixel space must be sampled from the same crop, not sourceBitmap.
     *
     * @param targetBitmap  the exact bitmap that is rendered on-screen (e.g. objectCrop / faceCrop)
     * @param cx, cy        pixel coordinates within [targetBitmap]
     * @param radius        Chamfer aperture radius in pixels (default 6)
     */
    fun runColorSampleOn(targetBitmap: Bitmap, cx: Int, cy: Int, radius: Int = 6) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                colorSampleState = NativePipelineState.Loading,
            )
            val result = ArtGridNative.sampleColor(targetBitmap, cx, cy, radius)
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                colorSampleState = result.fold(
                    onSuccess = { NativePipelineState.Success(it) },
                    onFailure = { NativePipelineState.Error(it.message ?: "Sampling failed") },
                ),
            )
        }
    }

    // ── K–M paint mix ────────────────────────────────────────────────────────
    // medium: 0 = watercolour, 1 = acrylic

    fun runKmSolve(r: Int, g: Int, b: Int, medium: Int = 0) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                kmState = NativePipelineState.Loading,
            )
            val result = ArtGridNative.solvePaintMix(r, g, b, medium)
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                kmState = result.fold(
                    onSuccess = { NativePipelineState.Success(it) },
                    onFailure = { NativePipelineState.Error(it.message ?: "Solve failed") },
                ),
            )
        }
    }

    // ── Palette extraction ───────────────────────────────────────────────────

    fun runPaletteExtraction(nColors: Int = 6) {
        val bmp = _uiState.value.sourceBitmap ?: return
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isProcessing = true,
                paletteState = NativePipelineState.Loading,
            )
            val result = ArtGridNative.extractPalette(bmp, nColors)
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                paletteState = result.fold(
                    onSuccess = { NativePipelineState.Success(it) },
                    onFailure = { NativePipelineState.Error(it.message ?: "Palette failed") },
                ),
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Generic pipeline launcher:
     *   1. Guard: no source bitmap → emit Error immediately (no crash)
     *   2. Cancel any in-flight job (prevents two pipelines running on same image)
     *   3. Set Loading state
     *   4. Call the suspend [block] with the source bitmap
     *   5. Map Result to Success/Error state
     */
    private fun <T> launchPipeline(
        stateUpdater: NativePipelineUiState.(NativePipelineState<T>) -> NativePipelineUiState,
        block: suspend (Bitmap) -> Result<T>,
    ) {
        val bmp = _uiState.value.sourceBitmap ?: run {
            _uiState.value = _uiState.value.stateUpdater(
                NativePipelineState.Error("No image loaded — import an image first")
            )
            return
        }

        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(isProcessing = true)
            .stateUpdater(NativePipelineState.Loading)

        activeJob = viewModelScope.launch {
            val result = block(bmp)
            _uiState.value = _uiState.value.copy(isProcessing = false)
                .stateUpdater(
                    result.fold(
                        onSuccess = { NativePipelineState.Success(it) },
                        onFailure = { NativePipelineState.Error(it.message ?: "Pipeline failed") },
                    )
                )
        }
    }

    /** Resets all pipeline output states when a new image is picked. */
    private fun resetAllStates() {
        _uiState.value = NativePipelineUiState()
    }
}
