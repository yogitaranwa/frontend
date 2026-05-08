/**
 * PaperMappingViewModel.kt
 * Responsibility : Paper-to-paper rescaling — manages source/destination paper size
 *                  selection and scaling ratio computation. Pure arithmetic, no ML calls.
 * Pattern used   : HiltViewModel + StateFlow
 * Dependencies   : none
 */
package com.artgrid.mobile.ui.papermapping

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Paper size in millimetres. ISO 216 series + common art papers. */
enum class PaperSize(val widthMm: Float, val heightMm: Float, val displayName: String) {
    A3(297f,  420f, "A3  (297 × 420 mm)"),
    A4(210f,  297f, "A4  (210 × 297 mm)"),
    A5(148f,  210f, "A5  (148 × 210 mm)"),
    A6(105f,  148f, "A6  (105 × 148 mm)"),
    B4(250f,  353f, "B4  (250 × 353 mm)"),
    B5(176f,  250f, "B5  (176 × 250 mm)"),
    LETTER(216f, 279f, "Letter (216 × 279 mm)"),
    CUSTOM(0f, 0f, "Custom"),
}

data class ScaledMeasurement(val labelMm: Float, val scaledMm: Float) {
    val scaledCm: Float get() = scaledMm / 10f
    val labelCm: Float  get() = labelMm / 10f
}

data class PaperMappingUiState(
    val imageUri: Uri?                  = null,
    val sourcePaper: PaperSize          = PaperSize.A5,
    val destPaper: PaperSize            = PaperSize.A4,
    val customSourceWidthMm: Float      = 148f,
    val customSourceHeightMm: Float     = 210f,
    val customDestWidthMm: Float        = 210f,
    val customDestHeightMm: Float       = 297f,
    val gridOpacity: Float              = 0.5f,
    val labelOpacity: Float             = 0.8f,
    val gridSpacingCm: Float            = 1f,
    val isPortrait: Boolean             = true,
    val customMeasurements: List<Float> = emptyList(),   // user-input measurements in mm on source
)

/** Derived scaling info computed from current state. */
data class ScalingInfo(
    val scaleX: Float,
    val scaleY: Float,
    val sourceWidthMm: Float,
    val sourceHeightMm: Float,
    val destWidthMm: Float,
    val destHeightMm: Float,
    val scaledMeasurements: List<ScaledMeasurement>,
)

@HiltViewModel
class PaperMappingViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(PaperMappingUiState())
    val uiState: StateFlow<PaperMappingUiState> = _uiState.asStateFlow()

    fun setImageUri(uri: Uri) { _uiState.update { it.copy(imageUri = uri) } }

    fun setSourcePaper(paper: PaperSize) { _uiState.update { it.copy(sourcePaper = paper) } }

    fun setDestPaper(paper: PaperSize) { _uiState.update { it.copy(destPaper = paper) } }

    fun setCustomSourceDimensions(widthMm: Float, heightMm: Float) {
        _uiState.update { it.copy(customSourceWidthMm = widthMm, customSourceHeightMm = heightMm) }
    }

    fun setCustomDestDimensions(widthMm: Float, heightMm: Float) {
        _uiState.update { it.copy(customDestWidthMm = widthMm, customDestHeightMm = heightMm) }
    }

    fun setGridOpacity(alpha: Float) { _uiState.update { it.copy(gridOpacity = alpha) } }

    fun setLabelOpacity(alpha: Float) { _uiState.update { it.copy(labelOpacity = alpha) } }

    fun setGridSpacing(cm: Float) { _uiState.update { it.copy(gridSpacingCm = cm.coerceIn(0.5f, 10f)) } }

    fun toggleOrientation() { _uiState.update { it.copy(isPortrait = !it.isPortrait) } }

    fun addMeasurement(mm: Float) {
        _uiState.update { it.copy(customMeasurements = it.customMeasurements + mm) }
    }

    fun removeMeasurement(index: Int) {
        _uiState.update { state ->
            state.copy(customMeasurements = state.customMeasurements.toMutableList().also { it.removeAt(index) })
        }
    }

    fun computeScaling(): ScalingInfo {
        val state = _uiState.value
        val (srcW, srcH) = effectiveDimensions(state.sourcePaper, state.customSourceWidthMm, state.customSourceHeightMm, state.isPortrait)
        val (dstW, dstH) = effectiveDimensions(state.destPaper, state.customDestWidthMm, state.customDestHeightMm, state.isPortrait)
        val scaleX = dstW / srcW
        val scaleY = dstH / srcH
        val scaled = state.customMeasurements.map { mm ->
            ScaledMeasurement(labelMm = mm, scaledMm = mm * scaleX)
        }
        return ScalingInfo(
            scaleX           = scaleX,
            scaleY           = scaleY,
            sourceWidthMm    = srcW,
            sourceHeightMm   = srcH,
            destWidthMm      = dstW,
            destHeightMm     = dstH,
            scaledMeasurements = scaled,
        )
    }

    private fun effectiveDimensions(paper: PaperSize, customW: Float, customH: Float, portrait: Boolean): Pair<Float, Float> {
        val w = if (paper == PaperSize.CUSTOM) customW else paper.widthMm
        val h = if (paper == PaperSize.CUSTOM) customH else paper.heightMm
        return if (portrait) Pair(w, h) else Pair(h, w)
    }
}
