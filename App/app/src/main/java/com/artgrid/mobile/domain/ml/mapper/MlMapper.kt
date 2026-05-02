/**
 * MlMapper.kt
 * Responsibility : Maps ML DTOs to domain models. No business logic — pure structural mapping.
 *                  Includes F-34 unified face mapping.
 * API calls      : none (mapping only)
 * Injects        : none (object with extension functions)
 */
package com.artgrid.mobile.domain.ml.mapper

import com.artgrid.mobile.data.ml.dto.FaceDetectionDto
import com.artgrid.mobile.data.ml.dto.FaceInferResponseDto
import com.artgrid.mobile.data.ml.dto.FaceLandmark28Dto
import com.artgrid.mobile.data.ml.dto.FaceLandmarkDto
import com.artgrid.mobile.data.ml.dto.FaceUnifiedResponseDto
import com.artgrid.mobile.data.ml.dto.FaceUnifiedTelemetryDto
import com.artgrid.mobile.data.ml.dto.HeadBboxExpandedDto
import com.artgrid.mobile.data.ml.dto.MlHealthDto
import com.artgrid.mobile.data.ml.dto.NormBboxDto
import com.artgrid.mobile.data.ml.dto.ObjectDetectionDto
import com.artgrid.mobile.data.ml.dto.ObjectInferResponseDto
import com.artgrid.mobile.domain.ml.model.FaceDetection
import com.artgrid.mobile.domain.ml.model.FaceDomain
import com.artgrid.mobile.domain.ml.model.FaceLandmark
import com.artgrid.mobile.domain.ml.model.FaceLandmark28
import com.artgrid.mobile.domain.ml.model.FaceResult
import com.artgrid.mobile.domain.ml.model.FaceUnifiedTelemetry
import com.artgrid.mobile.domain.ml.model.HeadBboxExpanded
import com.artgrid.mobile.domain.ml.model.MlHealth
import com.artgrid.mobile.domain.ml.model.NormBbox
import com.artgrid.mobile.domain.ml.model.ObjectDetection
import com.artgrid.mobile.domain.ml.model.ObjectInferResult
import com.artgrid.mobile.domain.ml.model.UnifiedFaceResult

object MlMapper {

    fun NormBboxDto.toDomain(): NormBbox = NormBbox(
        xNorm = xNorm,
        yNorm = yNorm,
        wNorm = wNorm,
        hNorm = hNorm,
    )

    fun FaceLandmarkDto.toDomain(): FaceLandmark = FaceLandmark(
        id    = id,
        xNorm = xNorm,
        yNorm = yNorm,
    )

    fun FaceInferResponseDto.toDomain(): FaceResult = FaceResult(
        faceDetected = faceDetected,
        faceBbox     = faceBbox?.toDomain(),
        landmarks    = landmarks.map { it.toDomain() },
        inferenceMs  = inferenceMs,
    )

    fun ObjectDetectionDto.toDomain(): ObjectDetection = ObjectDetection(
        label      = label,
        confidence = confidence,
        bbox       = bbox.toDomain(),
    )

    fun ObjectInferResponseDto.toDomain(): ObjectInferResult = ObjectInferResult(
        detections  = detections.map { it.toDomain() },
        inferenceMs = inferenceMs,
        model       = model,
    )

    fun MlHealthDto.toDomain(): MlHealth = MlHealth(
        isAvailable  = status == "ok",
        modelsLoaded = modelsLoaded,
        isDemoMode   = demoMode,
        gpuAvailable = gpuAvailable,
    )

    // ── F-34 Unified Face ─────────────────────────────────────────────────────

    private fun String.toFaceDomain(): FaceDomain = when (this.lowercase()) {
        "human"    -> FaceDomain.HUMAN
        "animated" -> FaceDomain.ANIMATED
        else       -> FaceDomain.AMBIGUOUS
    }

    private fun FaceLandmark28Dto.toDomain(): FaceLandmark28 = FaceLandmark28(
        id    = id,
        xNorm = xNorm,
        yNorm = yNorm,
    )

    private fun HeadBboxExpandedDto.toDomain(): HeadBboxExpanded = HeadBboxExpanded(
        xNorm = xNorm,
        yNorm = yNorm,
        wNorm = wNorm,
        hNorm = hNorm,
    )

    private fun FaceDetectionDto.toDomain(): FaceDetection = FaceDetection(
        faceIndex        = faceIndex,
        domain           = domain.toFaceDomain(),
        confidence       = confidence,
        bbox             = bbox.toDomain(),
        headBboxExpanded = headBboxExpanded?.toDomain(),
        landmarks68      = landmarks68.map { it.toDomain() },
        landmarks28      = landmarks28.map { it.toDomain() },
        eyeDistanceNorm  = eyeDistanceNorm,
        gammaEstimate    = gammaEstimate,
    )

    private fun FaceUnifiedTelemetryDto.toDomain(): FaceUnifiedTelemetry = FaceUnifiedTelemetry(
        humanHeadMs    = humanHeadMs,
        animatedHeadMs = animatedHeadMs,
        fusionMs       = fusionMs,
        totalMs        = totalMs,
    )

    fun FaceUnifiedResponseDto.toDomain(): UnifiedFaceResult = UnifiedFaceResult(
        faces         = faces.map { it.toDomain() },
        faceCount     = faceCount,
        telemetry     = telemetry.toDomain(),
        modelVersions = modelVersions,
    )
}
