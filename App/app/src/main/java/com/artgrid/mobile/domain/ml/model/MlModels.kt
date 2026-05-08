/**
 * MlModels.kt
 * Responsibility : Domain models for all ML inference results — zero Retrofit/Moshi annotations.
 *                  Includes unified human + anime face domain types.
 * API calls      : none (pure domain types)
 * Injects        : none
 */
package com.artgrid.mobile.domain.ml.model

// ── Shared ───────────────────────────────────────────────────────────────────

/** Normalised bounding box with all coordinates in [0.0, 1.0]. */
data class NormBbox(
    val xNorm: Float,
    val yNorm: Float,
    val wNorm: Float,
    val hNorm: Float,
)

// ── Legacy face detection (`/infer/face`) ─────────────────────────────────────

/** Single dlib 68-point facial landmark in normalised coordinates. */
data class FaceLandmark(
    val id: Int,
    val xNorm: Float,
    val yNorm: Float,
)

/**
 * Result of POST /infer/face.
 * When [faceDetected] is false, [faceBbox] and [landmarks] are empty.
 */
data class FaceResult(
    val faceDetected: Boolean,
    val faceBbox: NormBbox?,
    val landmarks: List<FaceLandmark>,
    val inferenceMs: Int,
)

// ── Object detection (`/infer/objects`) ───────────────────────────────────────

/** Single YOLOv8n detection with label, confidence, and normalised bounding box. */
data class ObjectDetection(
    val label: String,
    val confidence: Float,
    val bbox: NormBbox,
)

/**
 * Result of POST /infer/objects.
 * [detections] is empty when nothing is detected (200 response, not an error).
 */
data class ObjectInferResult(
    val detections: List<ObjectDetection>,
    val inferenceMs: Int,
    val model: String,
)

// ── Segmentation mask (`/infer/segment`) ───────────────────────────────────────

/**
 * Result of POST /infer/segment.
 * [maskPng] holds the raw PNG bytes of the single-channel alpha mask.
 * The UI layer (Skia / Canvas) composites this over the original image.
 */
data class SegmentResult(
    val maskPng: ByteArray,
) {
    // ByteArray equality must be structural, not referential.
    override fun equals(other: Any?): Boolean =
        other is SegmentResult && maskPng.contentEquals(other.maskPng)

    override fun hashCode(): Int = maskPng.contentHashCode()
}

// ── Unified face (`/infer/face_unified`) ────────────────────────────────────

/** Which pipeline detected this face: human (dlib), animated (YOLOv8-animeface), or fused. */
enum class FaceDomain { HUMAN, ANIMATED, AMBIGUOUS }

/** Single dlib 28-point anime landmark in normalised coordinates. */
data class FaceLandmark28(
    val id: Int,
    val xNorm: Float,
    val yNorm: Float,
)

/** Padded head bounding box used as drawing reference guide. */
data class HeadBboxExpanded(
    val xNorm: Float,
    val yNorm: Float,
    val wNorm: Float,
    val hNorm: Float,
)

/** Single face entry returned by ArtGridFaceDomainFusion. */
data class FaceDetection(
    val faceIndex: Int,
    val domain: FaceDomain,
    val confidence: Float,
    val bbox: NormBbox,
    val headBboxExpanded: HeadBboxExpanded?,
    val landmarks68: List<FaceLandmark>,       // non-empty for HUMAN
    val landmarks28: List<FaceLandmark28>,     // non-empty when optional anime landmark model is loaded
    val eyeDistanceNorm: Float?,
    val gammaEstimate: Float?,                 // ANIMATED only
)

/** Unified inference timing telemetry. */
data class FaceUnifiedTelemetry(
    val humanHeadMs: Int,
    val animatedHeadMs: Int,
    val fusionMs: Int,
    val totalMs: Int,
)

/** Result of POST /infer/face_unified — multi-face, multi-domain. */
data class UnifiedFaceResult(
    val faces: List<FaceDetection>,
    val faceCount: Int,
    val telemetry: FaceUnifiedTelemetry,
    val modelVersions: Map<String, String>,
)

// ── Infrastructure health ─────────────────────────────────────────────────────

/** Result of GET /health (v2) — used to gate ML feature visibility on home screen. */
data class MlHealth(
    val isAvailable: Boolean,   // true = status == "ok"
    val modelsLoaded: List<String>,
    val isDemoMode: Boolean,
    val gpuAvailable: Boolean,
)
