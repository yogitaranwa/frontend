/**
 * MlRepository.kt
 * Responsibility : Interface contract for all ML inference operations.
 * API calls      : POST /infer/face, POST /infer/face_unified, POST /infer/objects,
 *                  POST /infer/segment, GET /health
 * Injects        : none (interface)
 */
package com.artgrid.mobile.domain.ml

import com.artgrid.mobile.core.network.ApiResult
import com.artgrid.mobile.domain.ml.model.FaceResult
import com.artgrid.mobile.domain.ml.model.MlHealth
import com.artgrid.mobile.domain.ml.model.ObjectInferResult
import com.artgrid.mobile.domain.ml.model.SegmentResult
import com.artgrid.mobile.domain.ml.model.UnifiedFaceResult
import java.io.File

interface MlRepository {

    /**
     * Facial feature detection (legacy single human face).
     * [imageFile]: JPEG on device disk, resized to max 1200px before upload.
     */
    suspend fun inferFace(imageFile: File): ApiResult<FaceResult>

    /**
     * Unified human + animated face detection.
     * [imageFile]: JPEG on device disk, resized to max 1200px before upload.
     * Returns multi-face, multi-domain result via ArtGridFaceDomainFusion.
     */
    suspend fun inferFaceUnified(imageFile: File): ApiResult<UnifiedFaceResult>

    /**
     * Object localisation.
     * [imageFile]: JPEG on device disk, resized to max 640px before upload.
     */
    suspend fun inferObjects(imageFile: File): ApiResult<ObjectInferResult>

    /**
     * Subject/background segmentation (mask PNG).
     * [imageFile]: JPEG on device disk, resized to max 800px before upload.
     * Returns raw PNG mask bytes — UI layer composites with Skia/Canvas.
     */
    suspend fun inferSegment(imageFile: File): ApiResult<SegmentResult>

    /**
     * Infrastructure health probe — called on app launch.
     * Returns [MlHealth] with [MlHealth.isAvailable] = false on any failure
     * so the UI can degrade gracefully instead of showing an error screen.
     */
    suspend fun checkHealth(): MlHealth
}
