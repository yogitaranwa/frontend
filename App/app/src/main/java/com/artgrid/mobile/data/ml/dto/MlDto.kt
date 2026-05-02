/**
 * MlDto.kt
 * Responsibility : Moshi-annotated DTOs for artgrid-ml-service request/response bodies.
 *                  All coordinates are normalised [0,1] per API contract.
 * API calls      : POST /infer/face, POST /infer/face_unified, POST /infer/objects,
 *                  POST /infer/segment, GET /health
 * Injects        : none (plain data containers)
 */
package com.artgrid.mobile.data.ml.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── /infer/face ──────────────────────────────────────────────────────────────

/** Normalised bounding box, all values in [0,1]. */
@JsonClass(generateAdapter = true)
data class NormBboxDto(
    @Json(name = "x_norm") val xNorm: Float,
    @Json(name = "y_norm") val yNorm: Float,
    @Json(name = "w_norm") val wNorm: Float,
    @Json(name = "h_norm") val hNorm: Float,
)

/** Single dlib 68-point landmark. */
@JsonClass(generateAdapter = true)
data class FaceLandmarkDto(
    @Json(name = "id")     val id: Int,
    @Json(name = "x_norm") val xNorm: Float,
    @Json(name = "y_norm") val yNorm: Float,
)

/** F-10 response body from POST /infer/face. */
@JsonClass(generateAdapter = true)
data class FaceInferResponseDto(
    @Json(name = "face_detected") val faceDetected: Boolean,
    @Json(name = "face_bbox")     val faceBbox: NormBboxDto?,
    @Json(name = "landmarks")     val landmarks: List<FaceLandmarkDto>,
    @Json(name = "inference_ms")  val inferenceMs: Int,
)

// ── /infer/objects ────────────────────────────────────────────────────────────

/** Single YOLOv8n detection result. */
@JsonClass(generateAdapter = true)
data class ObjectDetectionDto(
    @Json(name = "label")      val label: String,
    @Json(name = "confidence") val confidence: Float,
    @Json(name = "bbox")       val bbox: NormBboxDto,
)

/** F-11 response body from POST /infer/objects. */
@JsonClass(generateAdapter = true)
data class ObjectInferResponseDto(
    @Json(name = "detections")   val detections: List<ObjectDetectionDto>,
    @Json(name = "inference_ms") val inferenceMs: Int,
    @Json(name = "model")        val model: String,
)

// NOTE: /infer/segment returns Content-Type: image/png binary.
// That response is handled as ResponseBody in MlApiService and converted
// to ByteArray in MlRepositoryImpl — no DTO needed.

// ── /infer/face_unified (F-34) ───────────────────────────────────────────────

/** Single 28-point anime landmark (F-34-LM, conditional). */
@JsonClass(generateAdapter = true)
data class FaceLandmark28Dto(
    @Json(name = "id")     val id: Int,
    @Json(name = "x_norm") val xNorm: Float,
    @Json(name = "y_norm") val yNorm: Float,
)

/** Expanded head bounding box with padding for drawing reference. */
@JsonClass(generateAdapter = true)
data class HeadBboxExpandedDto(
    @Json(name = "x_norm") val xNorm: Float,
    @Json(name = "y_norm") val yNorm: Float,
    @Json(name = "w_norm") val wNorm: Float,
    @Json(name = "h_norm") val hNorm: Float,
)

/** Single detected face from the unified head — human or animated. */
@JsonClass(generateAdapter = true)
data class FaceDetectionDto(
    @Json(name = "face_index")          val faceIndex: Int,
    @Json(name = "domain")              val domain: String,           // "human" | "animated" | "ambiguous"
    @Json(name = "confidence")          val confidence: Float,
    @Json(name = "bbox")                val bbox: NormBboxDto,
    @Json(name = "head_bbox_expanded")  val headBboxExpanded: HeadBboxExpandedDto?,
    @Json(name = "landmarks_68")        val landmarks68: List<FaceLandmarkDto>,   // non-empty for human only
    @Json(name = "landmarks_28")        val landmarks28: List<FaceLandmark28Dto>, // non-empty if F-34-LM enabled
    @Json(name = "eye_distance_norm")   val eyeDistanceNorm: Float?,
    @Json(name = "gamma_estimate")      val gammaEstimate: Float?,                // animated only
)

/** Telemetry block returned inside the unified response. */
@JsonClass(generateAdapter = true)
data class FaceUnifiedTelemetryDto(
    @Json(name = "human_head_ms")    val humanHeadMs: Int,
    @Json(name = "animated_head_ms") val animatedHeadMs: Int,
    @Json(name = "fusion_ms")        val fusionMs: Int,
    @Json(name = "total_ms")         val totalMs: Int,
)

/** F-34 · Unified face detection response from POST /infer/face_unified. */
@JsonClass(generateAdapter = true)
data class FaceUnifiedResponseDto(
    @Json(name = "faces")           val faces: List<FaceDetectionDto>,
    @Json(name = "face_count")      val faceCount: Int,
    @Json(name = "telemetry")       val telemetry: FaceUnifiedTelemetryDto,
    @Json(name = "model_versions")  val modelVersions: Map<String, String>,
)

// ── /health (v2) ─────────────────────────────────────────────────────────────

/** Infrastructure health-check response from GET /health (v2 — adds gpu_available). */
@JsonClass(generateAdapter = true)
data class MlHealthDto(
    @Json(name = "status")        val status: String,
    @Json(name = "models_loaded") val modelsLoaded: List<String>,
    @Json(name = "demo_mode")     val demoMode: Boolean,
    @Json(name = "gpu_available") val gpuAvailable: Boolean,
)
