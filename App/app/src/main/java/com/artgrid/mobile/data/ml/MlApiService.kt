/**
 * MlApiService.kt
 * Responsibility : Retrofit interface for artgrid-ml-service endpoints.
 *                  Image uploads use multipart/form-data as specified in the API contract.
 * API calls      : POST /infer/face, POST /infer/face_unified, POST /infer/objects,
 *                  POST /infer/segment, GET /health
 * Injects        : none (Retrofit creates the implementation)
 */
package com.artgrid.mobile.data.ml

import com.artgrid.mobile.data.ml.dto.FaceInferResponseDto
import com.artgrid.mobile.data.ml.dto.FaceUnifiedResponseDto
import com.artgrid.mobile.data.ml.dto.MlHealthDto
import com.artgrid.mobile.data.ml.dto.ObjectInferResponseDto
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface MlApiService {

    /**
     * Facial feature detection (legacy single-face human endpoint).
     * Kept for backward compatibility — prefer [inferFaceUnified] for new screens.
     * Image: JPEG, max 1200px on longest side, max 5MB.
     */
    @Multipart
    @POST("infer/face")
    suspend fun inferFace(
        @Part image: MultipartBody.Part,
    ): FaceInferResponseDto

    /**
     * Unified human + animated face detection.
     * Runs dlib (human) + YOLOv8-animeface ONNX (animated) in parallel on the GPU VM.
     * ArtGridFaceDomainFusion applies cross-head NMS and classifies each face by domain.
     * Image: JPEG, max 1200px on longest side, max 5MB.
     * Returns up to N faces with domain, bbox, landmarks_68 (human) or landmarks_28 (anime, optional).
     */
    @Multipart
    @POST("infer/face_unified")
    suspend fun inferFaceUnified(
        @Part image: MultipartBody.Part,
    ): FaceUnifiedResponseDto

    /**
     * Object localisation via YOLOv8n (onnxruntime-gpu on ML host when available).
     * Image: JPEG, max 640px, max 2MB.
     */
    @Multipart
    @POST("infer/objects")
    suspend fun inferObjects(
        @Part image: MultipartBody.Part,
    ): ObjectInferResponseDto

    /**
     * Subject/background segmentation via U²-Netp (GPU on ML host when available).
     * Image: JPEG, max 800px, max 3MB.
     * Response: image/png binary (alpha mask). Returned as raw ResponseBody.
     */
    @Multipart
    @POST("infer/segment")
    suspend fun inferSegment(
        @Part image: MultipartBody.Part,
    ): ResponseBody

    /**
     * Infrastructure health-check. No auth required.
     * v2: includes gpu_available flag for feature gating on home screen.
     */
    @GET("health")
    suspend fun health(): MlHealthDto
}
