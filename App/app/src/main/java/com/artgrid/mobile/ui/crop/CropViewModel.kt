/**
 * CropViewModel.kt
 * Responsibility : Manages crop rectangle state for in-app non-destructive crop.
 *                  Exposes normalised crop rect [0,1] that the UI layer renders as handles.
 * Pattern used   : HiltViewModel + StateFlow
 * Dependencies   : none (pure UI state — actual pixel crop is a Canvas draw operation)
 */
package com.artgrid.mobile.ui.crop

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class CropRect(
    val left: Float   = 0f,
    val top: Float    = 0f,
    val right: Float  = 1f,
    val bottom: Float = 1f,
) {
    val width: Float  get() = right - left
    val height: Float get() = bottom - top
    val aspectRatio: Float get() = if (height == 0f) 1f else width / height
}

data class CropUiState(
    val imageUri: Uri?        = null,
    val cropRect: CropRect    = CropRect(),
    val aspectLocked: Boolean = false,
    val lockedAspect: Float   = 1f,
    val isSaving: Boolean     = false,
    val savedPath: String?    = null,
)

enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

@HiltViewModel
class CropViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(CropUiState())
    val uiState: StateFlow<CropUiState> = _uiState.asStateFlow()

    private val MIN_CROP = 0.05f

    fun setImageUri(uri: Uri) {
        _uiState.update { it.copy(imageUri = uri, cropRect = CropRect(), savedPath = null) }
    }

    fun onHandleDrag(handle: CropHandle, dx: Float, dy: Float) {
        _uiState.update { state ->
            val r = state.cropRect
            val newRect = when (handle) {
                CropHandle.TOP_LEFT     -> r.copy(
                    left = (r.left + dx).coerceIn(0f, r.right - MIN_CROP),
                    top  = (r.top  + dy).coerceIn(0f, r.bottom - MIN_CROP),
                )
                CropHandle.TOP_RIGHT    -> r.copy(
                    right = (r.right + dx).coerceIn(r.left + MIN_CROP, 1f),
                    top   = (r.top   + dy).coerceIn(0f, r.bottom - MIN_CROP),
                )
                CropHandle.BOTTOM_LEFT  -> r.copy(
                    left   = (r.left   + dx).coerceIn(0f, r.right - MIN_CROP),
                    bottom = (r.bottom + dy).coerceIn(r.top + MIN_CROP, 1f),
                )
                CropHandle.BOTTOM_RIGHT -> r.copy(
                    right  = (r.right  + dx).coerceIn(r.left + MIN_CROP, 1f),
                    bottom = (r.bottom + dy).coerceIn(r.top + MIN_CROP, 1f),
                )
                CropHandle.MOVE         -> r.copy(
                    left   = (r.left   + dx).coerceIn(0f, 1f - r.width),
                    top    = (r.top    + dy).coerceIn(0f, 1f - r.height),
                    right  = (r.right  + dx).coerceIn(r.width, 1f),
                    bottom = (r.bottom + dy).coerceIn(r.height, 1f),
                )
            }
            val finalRect = if (state.aspectLocked) enforceAspect(newRect, state.lockedAspect, handle) else newRect
            state.copy(cropRect = finalRect)
        }
    }

    fun resetCrop() {
        _uiState.update { it.copy(cropRect = CropRect(), savedPath = null) }
    }

    fun toggleAspectLock(lock: Boolean) {
        _uiState.update { state ->
            state.copy(
                aspectLocked = lock,
                lockedAspect = if (lock) state.cropRect.aspectRatio else 1f,
            )
        }
    }

    fun setPresetAspect(widthRatio: Float, heightRatio: Float) {
        _uiState.update { state ->
            val aspect = widthRatio / heightRatio
            val r = state.cropRect
            val newHeight = r.width / aspect
            val clampedBottom = (r.top + newHeight).coerceAtMost(1f)
            state.copy(
                cropRect     = r.copy(bottom = clampedBottom),
                aspectLocked = true,
                lockedAspect = aspect,
            )
        }
    }

    private fun enforceAspect(rect: CropRect, aspect: Float, handle: CropHandle): CropRect {
        val newHeight = rect.width / aspect
        return when (handle) {
            CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT ->
                rect.copy(top = (rect.bottom - newHeight).coerceAtLeast(0f))
            else ->
                rect.copy(bottom = (rect.top + newHeight).coerceAtMost(1f))
        }
    }
}
