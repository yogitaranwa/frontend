/**
 * UnifiedFaceViewModel.kt
 * Responsibility : Unified human + animated face detection ViewModel.
 *                  Calls POST /infer/face_unified, holds multi-face result, tracks
 *                  selected face index for tap-to-select UX.
 * Pattern used   : HiltViewModel + StateFlow
 * Dependencies   : MlRepository, file system helpers
 */
package com.artgrid.mobile.ui.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artgrid.mobile.core.network.ApiResult
import com.artgrid.mobile.domain.ml.MlRepository
import com.artgrid.mobile.domain.ml.model.FaceDetection
import com.artgrid.mobile.domain.ml.model.FaceDomain
import com.artgrid.mobile.domain.ml.model.UnifiedFaceResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

sealed class UnifiedFaceUiState {
    data object Idle : UnifiedFaceUiState()
    data object Loading : UnifiedFaceUiState()
    data class Success(
        val imageUri: Uri,
        val result: UnifiedFaceResult,
        val selectedIndex: Int,
    ) : UnifiedFaceUiState()
    data class Error(val message: String) : UnifiedFaceUiState()
}

@HiltViewModel
class UnifiedFaceViewModel @Inject constructor(
    private val mlRepository: MlRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UnifiedFaceUiState>(UnifiedFaceUiState.Idle)
    val uiState: StateFlow<UnifiedFaceUiState> = _uiState.asStateFlow()

    fun runUnifiedDetection(imageUri: Uri) {
        viewModelScope.launch {
            _uiState.update { UnifiedFaceUiState.Loading }
            val file = withContext(Dispatchers.IO) { imageUri.toScaledJpegFile(context) }
            if (file == null) {
                _uiState.update { UnifiedFaceUiState.Error("Failed to read image from URI") }
                return@launch
            }
            when (val result = mlRepository.inferFaceUnified(file)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        UnifiedFaceUiState.Success(
                            imageUri      = imageUri,
                            result        = result.data,
                            selectedIndex = 0,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        UnifiedFaceUiState.Error("Server error ${result.code}: ${result.message}")
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        UnifiedFaceUiState.Error("Network unavailable — check ML GPU service connection.")
                    }
                }
            }
        }
    }

    fun selectFace(index: Int) {
        val current = _uiState.value as? UnifiedFaceUiState.Success ?: return
        _uiState.update { current.copy(selectedIndex = index) }
    }

    private fun Uri.toScaledJpegFile(ctx: Context): File? = try {
        val inputStream = ctx.contentResolver.openInputStream(this) ?: return null
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        val maxSide = 1200
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val file = File(ctx.cacheDir, "unified_face_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        file
    } catch (e: Exception) {
        null
    }
}
