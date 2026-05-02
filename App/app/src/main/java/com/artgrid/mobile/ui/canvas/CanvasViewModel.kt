/**
 * CanvasViewModel.kt
 * Responsibility : Shared canvas calibration state — canvas size selection and
 *                  cm_per_px computation. Based on F-02 + F-03 spec.
 *
 * New in this version:
 *  - Expanded preset library: ISO A/B, US Art sizes, Watercolour Imperial, Office
 *  - "Original Image DPI" mode: physical size computed from pixel dims at a chosen DPI
 *  - Presets grouped by category for the UI
 *  - printDpi field (default 300) configurable by user
 */
package com.artgrid.mobile.ui.canvas

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// ── Preset data ───────────────────────────────────────────────────────────────

data class CanvasPreset(
    val name: String,
    val widthCm: Float,
    val heightCm: Float,
)

/** Categories for the calibration sheet UI */
data class PresetCategory(
    val label: String,
    val presets: List<CanvasPreset>,
)

val PRESET_CATEGORIES: List<PresetCategory> = listOf(
    PresetCategory("ISO A Series", listOf(
        CanvasPreset("A6",  10.5f, 14.8f),
        CanvasPreset("A5",  14.8f, 21.0f),
        CanvasPreset("A4",  21.0f, 29.7f),
        CanvasPreset("A3",  29.7f, 42.0f),
        CanvasPreset("A2",  42.0f, 59.4f),
        CanvasPreset("A1",  59.4f, 84.1f),
        CanvasPreset("A0",  84.1f, 118.9f),
    )),
    PresetCategory("ISO B Series", listOf(
        CanvasPreset("B6",  12.5f, 17.6f),
        CanvasPreset("B5",  17.6f, 25.0f),
        CanvasPreset("B4",  25.0f, 35.3f),
        CanvasPreset("B3",  35.3f, 50.0f),
        CanvasPreset("B2",  50.0f, 70.7f),
    )),
    PresetCategory("US Art Paper", listOf(
        CanvasPreset("4\"×6\"",  10.2f, 15.2f),
        CanvasPreset("5\"×7\"",  12.7f, 17.8f),
        CanvasPreset("8\"×10\"", 20.3f, 25.4f),
        CanvasPreset("9\"×12\"", 22.9f, 30.5f),
        CanvasPreset("11\"×14\"",27.9f, 35.6f),
        CanvasPreset("12\"×16\"",30.5f, 40.6f),
        CanvasPreset("16\"×20\"",40.6f, 50.8f),
        CanvasPreset("18\"×24\"",45.7f, 61.0f),
        CanvasPreset("24\"×36\"",61.0f, 91.4f),
    )),
    PresetCategory("Watercolour (Imperial)", listOf(
        CanvasPreset("¼ Imperial",  27.9f, 38.1f),  // 11"×15"
        CanvasPreset("½ Imperial",  38.1f, 55.9f),  // 15"×22"
        CanvasPreset("Full Imperial",55.9f,76.2f),  // 22"×30"
        CanvasPreset("Elephant",    58.4f, 76.2f),  // 23"×30"
        CanvasPreset("Double Elephant",66.7f,101.6f),
    )),
    PresetCategory("Office / Other", listOf(
        CanvasPreset("Letter",  21.6f, 27.9f),
        CanvasPreset("Legal",   21.6f, 35.6f),
        CanvasPreset("Tabloid", 27.9f, 43.2f),
        CanvasPreset("Square 20cm", 20.0f, 20.0f),
        CanvasPreset("Square 30cm", 30.0f, 30.0f),
    )),
)

/** Flat list convenient for code that just needs the presets */
val BUILTIN_CANVAS_PRESETS: List<CanvasPreset> = PRESET_CATEGORIES.flatMap { it.presets }

// ── UI State ──────────────────────────────────────────────────────────────────

data class CanvasUiState(
    val selectedPreset: CanvasPreset  = BUILTIN_CANVAS_PRESETS.first { it.name == "A4" },
    val customWidthCm: Float          = 21.0f,
    val customHeightCm: Float         = 29.7f,
    val isCustom: Boolean             = false,
    /**
     * When true: physical canvas size is derived from [imagePxW] × [imagePxH] at [printDpi].
     * Useful when the user's canvas IS a photo print.
     */
    val isOriginalImageSize: Boolean  = false,
    /** DPI used only when [isOriginalImageSize] is true (default: 300 for photo prints). */
    val printDpi: Float               = 300f,
    val imagePxW: Int                 = 0,
    val imagePxH: Int                 = 0,
    val confirmed: Boolean            = false,
) {
    val canvasWidthCm: Float get() = when {
        isOriginalImageSize && imagePxW > 0 -> imagePxW / printDpi * 2.54f
        isCustom                            -> customWidthCm
        else                                -> selectedPreset.widthCm
    }
    val canvasHeightCm: Float get() = when {
        isOriginalImageSize && imagePxH > 0 -> imagePxH / printDpi * 2.54f
        isCustom                            -> customHeightCm
        else                                -> selectedPreset.heightCm
    }

    val cmPerPxX: Float? get() =
        if (confirmed && imagePxW > 0) canvasWidthCm / imagePxW.toFloat() else null
    val cmPerPxY: Float? get() =
        if (confirmed && imagePxH > 0) canvasHeightCm / imagePxH.toFloat() else null
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class CanvasViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(CanvasUiState())
    val state: StateFlow<CanvasUiState> = _state.asStateFlow()

    fun onImageLoaded(widthPx: Int, heightPx: Int) {
        _state.value = _state.value.copy(imagePxW = widthPx, imagePxH = heightPx, confirmed = false)
    }

    fun selectPreset(preset: CanvasPreset) {
        _state.value = _state.value.copy(
            selectedPreset       = preset,
            isCustom             = false,
            isOriginalImageSize  = false,
        )
    }

    fun setCustomSize(widthCm: Float, heightCm: Float) {
        _state.value = _state.value.copy(
            customWidthCm        = widthCm,
            customHeightCm       = heightCm,
            isCustom             = true,
            isOriginalImageSize  = false,
        )
    }

    /** Use the image's own pixel dimensions at [dpi] to derive physical canvas size. */
    fun selectOriginalImageSize(dpi: Float = 300f) {
        _state.value = _state.value.copy(
            isOriginalImageSize  = true,
            isCustom             = false,
            printDpi             = dpi,
        )
    }

    fun setPrintDpi(dpi: Float) {
        _state.value = _state.value.copy(printDpi = dpi)
    }

    fun confirmMapping() {
        _state.value = _state.value.copy(confirmed = true)
    }
}
