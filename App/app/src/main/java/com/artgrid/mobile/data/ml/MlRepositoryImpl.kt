/**
 * MlRepositoryImpl.kt
 * Responsibility : Implements MlRepository — builds multipart requests, calls MlApiService,
 *                  maps DTOs to domain models, and converts PNG ResponseBody to ByteArray.
 * API calls      : POST /infer/face, POST /infer/face_unified, POST /infer/objects,
 *                  POST /infer/segment, GET /health
 * Injects        : MlApiService
 */
package com.artgrid.mobile.data.ml

import com.artgrid.mobile.core.network.ApiResult
import com.artgrid.mobile.core.network.safeApiCall
import com.artgrid.mobile.domain.ml.MlRepository
import com.artgrid.mobile.domain.ml.mapper.MlMapper.toDomain
import com.artgrid.mobile.domain.ml.model.FaceResult
import com.artgrid.mobile.domain.ml.model.MlHealth
import com.artgrid.mobile.domain.ml.model.ObjectInferResult
import com.artgrid.mobile.domain.ml.model.SegmentResult
import com.artgrid.mobile.domain.ml.model.UnifiedFaceResult
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MlRepositoryImpl @Inject constructor(
    private val apiService: MlApiService,
) : MlRepository {

    override suspend fun inferFace(imageFile: File): ApiResult<FaceResult> = safeApiCall {
        apiService.inferFace(imageFile.toMultipartPart("image")).toDomain()
    }

    override suspend fun inferFaceUnified(imageFile: File): ApiResult<UnifiedFaceResult> = safeApiCall {
        apiService.inferFaceUnified(imageFile.toMultipartPart("image")).toDomain()
    }

    override suspend fun inferObjects(imageFile: File): ApiResult<ObjectInferResult> = safeApiCall {
        apiService.inferObjects(imageFile.toMultipartPart("image")).toDomain()
    }

    override suspend fun inferSegment(imageFile: File): ApiResult<SegmentResult> = safeApiCall {
        val responseBody = apiService.inferSegment(imageFile.toMultipartPart("image"))
        SegmentResult(maskPng = responseBody.bytes())
    }

    /**
     * Health probe — never throws. Returns an unavailable [MlHealth] on any failure
     * so the home screen can show a degraded mode without an error state.
     */
    override suspend fun checkHealth(): MlHealth = try {
        apiService.health().toDomain()
    } catch (e: Exception) {
        MlHealth(
            isAvailable  = false,
            modelsLoaded = emptyList(),
            isDemoMode   = false,
            gpuAvailable = false,
        )
    }

    private fun File.toMultipartPart(name: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(name, this.name, asRequestBody("image/jpeg".toMediaTypeOrNull()))
}
