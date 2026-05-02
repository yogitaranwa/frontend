/**
 * TraceModeViewModel.kt
 * Responsibility : F-27-MVP camera-underlay (trace) mode state.
 *                  Manages reference image overlay transform (translate, scale, rotate, opacity).
 *                  Camera stream is managed by CameraX in the composable — VM owns the overlay only.
 * Pattern used   : HiltViewModel + StateFlow
 * Dependencies   : none
 */
package com.artgrid.mobile.ui.trace

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class TraceOverlayState(
    val referenceUri: Uri?    = null,
    val translateX: Float     = 0f,
    val translateY: Float     = 0f,
    val scale: Float          = 1f,
    val rotationDeg: Float    = 0f,
    val overlayAlpha: Float   = 0.5f,
    val gridEnabled: Boolean  = false,
    val gridOpacity: Float    = 0.4f,
    val gridSpacingCm: Float  = 1f,
    val isMirroredH: Boolean  = false,
)

@HiltViewModel
class TraceModeViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(TraceOverlayState())
    val state: StateFlow<TraceOverlayState> = _state.asStateFlow()

    fun setReferenceImage(uri: Uri) { _state.update { it.copy(referenceUri = uri) } }

    fun onPan(dx: Float, dy: Float) {
        _state.update { it.copy(translateX = it.translateX + dx, translateY = it.translateY + dy) }
    }

    fun onScale(factor: Float) {
        _state.update { it.copy(scale = (it.scale * factor).coerceIn(0.1f, 10f)) }
    }

    fun onRotate(degrees: Float) {
        _state.update { it.copy(rotationDeg = (it.rotationDeg + degrees) % 360f) }
    }

    fun setOverlayAlpha(alpha: Float) {
        _state.update { it.copy(overlayAlpha = alpha.coerceIn(0f, 1f)) }
    }

    fun setGridOpacity(alpha: Float) {
        _state.update { it.copy(gridOpacity = alpha.coerceIn(0f, 1f)) }
    }

    fun setGridSpacing(cm: Float) {
        _state.update { it.copy(gridSpacingCm = cm.coerceIn(0.5f, 10f)) }
    }

    fun toggleGrid() { _state.update { it.copy(gridEnabled = !it.gridEnabled) } }

    fun toggleMirror() { _state.update { it.copy(isMirroredH = !it.isMirroredH) } }

    fun resetTransform() {
        _state.update { it.copy(translateX = 0f, translateY = 0f, scale = 1f, rotationDeg = 0f) }
    }
}
