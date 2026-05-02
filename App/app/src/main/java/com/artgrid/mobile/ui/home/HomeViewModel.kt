/**
 * HomeViewModel.kt
 * Responsibility : Coordinates the home screen — ML health probe, image picker
 *                  state, and per-feature ML result state for F-10/F-11/F-22.
 * API calls      : GET /health, POST /infer/face, POST /infer/objects, POST /infer/segment
 * Injects        : MlRepository
 */
package com.artgrid.mobile.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artgrid.mobile.core.network.ApiResult
import com.artgrid.mobile.domain.ml.MlRepository
import com.artgrid.mobile.domain.ml.model.FaceResult
import com.artgrid.mobile.domain.ml.model.MlHealth
import com.artgrid.mobile.domain.ml.model.ObjectInferResult
import com.artgrid.mobile.domain.ml.model.SegmentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// ── UI State ─────────────────────────────────────────────────────────────────

data class HomeUiState(
    val mlHealth:         MlHealth?         = null,
    val selectedImageUri: Uri?              = null,
    val faceState:        MlFeatureState<FaceResult>         = MlFeatureState.Idle,
    val objectsState:     MlFeatureState<ObjectInferResult>  = MlFeatureState.Idle,
    val segmentState:     MlFeatureState<SegmentResult>      = MlFeatureState.Idle,
)

sealed class MlFeatureState<out T> {
    data object Idle    : MlFeatureState<Nothing>()
    data object Loading : MlFeatureState<Nothing>()
    data class  Success<T>(val result: T) : MlFeatureState<T>()
    data class  Error(val message: String) : MlFeatureState<Nothing>()
    /**
     * ML call succeeded (HTTP 200) but the model found nothing to return.
     * Distinct from [Error] — the service is healthy, the image just had no detectable subject.
     */
    data class  Empty(val hint: String) : MlFeatureState<Nothing>()
}

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mlRepository: MlRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        probeHealth()
    }

    /**
     * Circuit-breaker health probe — called on init and after each user tap.
     * [MlHealth.isAvailable] gates whether the ML action buttons are shown.
     *
     * Clears [HomeUiState.mlHealth] to null first so the UI shows "Checking server…"
     * immediately on Retry (otherwise the card stayed on "Server offline" until the
     * request finished and looked like a no-op).
     */
    fun probeHealth() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(mlHealth = null)
            val health = mlRepository.checkHealth()   // never throws — see MlRepositoryImpl
            _uiState.value = _uiState.value.copy(mlHealth = health)
        }
    }

    /** Called by HomeScreen when the user picks or captures an image. */
    fun onImageSelected(uri: Uri) {
        _uiState.value = _uiState.value.copy(
            selectedImageUri = uri,
            faceState        = MlFeatureState.Idle,
            objectsState     = MlFeatureState.Idle,
            segmentState     = MlFeatureState.Idle,
        )
    }

    /**
     * F-10 · Facial Feature Detection.
     * [imageFile]: the JPEG already scaled to max 1200px by the caller.
     */
    fun runFaceDetection(imageFile: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(faceState = MlFeatureState.Loading)
            _uiState.value = _uiState.value.copy(
                faceState = when (val r = mlRepository.inferFace(imageFile)) {
                    is ApiResult.Success      -> MlFeatureState.Success(r.data)
                    is ApiResult.Error        -> MlFeatureState.Error("Server error ${r.code} — ${r.message.trimToOneLine()}")
                    is ApiResult.NetworkError -> MlFeatureState.Error(r.throwable.toUserMessage())
                }
            )
        }
    }

    /**
     * F-11 · Object Localisation.
     * [imageFile]: JPEG scaled to max 640px by the caller.
     */
    fun runObjectDetection(imageFile: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(objectsState = MlFeatureState.Loading)
            _uiState.value = _uiState.value.copy(
                objectsState = when (val r = mlRepository.inferObjects(imageFile)) {
                    is ApiResult.Success      -> {
                        val data = r.data
                        if (data.detections.isEmpty()) {
                            MlFeatureState.Empty("No objects detected — try a photo with clearer subjects")
                        } else {
                            MlFeatureState.Success(data)
                        }
                    }
                    is ApiResult.Error        -> MlFeatureState.Error("Server error ${r.code} — ${r.message.trimToOneLine()}")
                    is ApiResult.NetworkError -> MlFeatureState.Error(r.throwable.toUserMessage())
                }
            )
        }
    }

    /**
     * F-22 · Background Removal.
     * [imageFile]: JPEG scaled to max 800px by the caller.
     */
    fun runSegmentation(imageFile: File) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(segmentState = MlFeatureState.Loading)
            _uiState.value = _uiState.value.copy(
                segmentState = when (val r = mlRepository.inferSegment(imageFile)) {
                    is ApiResult.Success      -> {
                        val data = r.data
                        if (data.maskPng.isEmpty()) {
                            MlFeatureState.Empty("Background removal returned an empty mask — try a different image")
                        } else {
                            MlFeatureState.Success(data)
                        }
                    }
                    is ApiResult.Error        -> MlFeatureState.Error("Server error ${r.code} — ${r.message.trimToOneLine()}")
                    is ApiResult.NetworkError -> MlFeatureState.Error(r.throwable.toUserMessage())
                }
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Converts a transport-level throwable to an actionable one-liner for users. */
    private fun Throwable.toUserMessage(): String = when (this) {
        is java.net.SocketTimeoutException    -> "Request timed out — server may be busy or unreachable"
        is java.net.ConnectException          -> "Cannot connect to ML server — check server is running and ML_BASE_URL is correct"
        is javax.net.ssl.SSLException         -> "SSL/TLS error — certificate mismatch or wrong port"
        is java.net.UnknownHostException      -> "Cannot resolve ML server hostname — check network and ML_BASE_URL"
        is java.io.IOException                -> "Network I/O error: ${message ?: javaClass.simpleName}"
        else                                  -> "Unexpected error: ${message ?: javaClass.simpleName}"
    }

    /** Strips newlines from server error bodies so they display in a single line. */
    private fun String.trimToOneLine(): String =
        replace('\n', ' ').replace('\r', ' ').trim().take(200)
}

