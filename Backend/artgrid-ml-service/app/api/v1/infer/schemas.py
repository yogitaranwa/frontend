"""
schemas.py
Responsibility : Pydantic request/response models for all ML inference endpoints.
Pattern used   : Separate input schemas from response schemas.
Dependencies   : pydantic
"""
from enum import Enum

from pydantic import BaseModel, Field


# ── Shared ───────────────────────────────────────────────────────────────────

class NormBbox(BaseModel):
    x_norm: float = Field(ge=0.0, le=1.0)
    y_norm: float = Field(ge=0.0, le=1.0)
    w_norm: float = Field(ge=0.0, le=1.0)
    h_norm: float = Field(ge=0.0, le=1.0)


# ── F-10 Face Detection (legacy single-face endpoint) ────────────────────────

class FaceLandmark(BaseModel):
    id: int
    x_norm: float
    y_norm: float


class FaceInferResponse(BaseModel):
    face_detected: bool
    face_bbox: NormBbox | None
    landmarks: list[FaceLandmark]
    inference_ms: int


# ── F-11 Object Detection ─────────────────────────────────────────────────────

class ObjectDetection(BaseModel):
    label: str
    confidence: float = Field(ge=0.0, le=1.0)
    bbox: NormBbox


class ObjectInferResponse(BaseModel):
    detections: list[ObjectDetection]
    inference_ms: int
    model: str


# ── F-34 + F-34-LM: Unified Human + Animated Face Detection ──────────────────

class FaceDomain(str, Enum):
    HUMAN    = "human"
    ANIMATED = "animated"
    UNKNOWN  = "unknown"


class HeadBboxExpanded(BaseModel):
    """Gamma-expanded head bounding box for display proportions (optional)."""
    x_norm: float
    y_norm: float
    w_norm: float
    h_norm: float
    gamma: float


class UnifiedFaceDetection(BaseModel):
    """Single face detected by ArtGridFaceDomainFusion (human or animated)."""
    face_id: str
    domain: FaceDomain
    confidence: float = Field(ge=0.0, le=1.0)
    bbox: NormBbox
    landmarks_68: list[FaceLandmark]
    # F-34-LM: populated for animated faces when return_landmarks=true and model loaded.
    landmarks_28: list[FaceLandmark]
    head_bbox_expanded: HeadBboxExpanded | None = None


class FaceUnifiedTelemetry(BaseModel):
    domain_histogram: dict[str, int]


class FaceUnifiedResponse(BaseModel):
    request_id: str
    faces: list[UnifiedFaceDetection]
    ms_preprocess: int
    ms_human: int
    ms_animated: int
    ms_fusion: int
    telemetry: FaceUnifiedTelemetry


# ── Health ────────────────────────────────────────────────────────────────────

class HealthResponse(BaseModel):
    status: str
    gpu_available: bool
    models_loaded: list[str]
    demo_mode: bool
    build: str


# ── Error ─────────────────────────────────────────────────────────────────────

class ErrorResponse(BaseModel):
    error: str
    message: str
