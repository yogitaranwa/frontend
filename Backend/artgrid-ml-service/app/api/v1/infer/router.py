"""
router.py
Responsibility : HTTP route definitions for /infer/face, /infer/face_unified,
                 /infer/objects, /infer/segment, /health.
                 Request/response handling only — all business logic in InferService.
Pattern used   : FastAPI router with Depends() for auth + rate limiting.
Dependencies   : FastAPI, InferService, auth middleware, rate_limiter
"""
import time

import cv2
import numpy as np
from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from fastapi.responses import JSONResponse, Response

from app.api.v1.infer.schemas import (
    ErrorResponse,
    FaceInferResponse,
    FaceUnifiedResponse,
    HealthResponse,
    ObjectInferResponse,
)
from app.config.settings import Settings, get_settings
from app.core.exceptions import (
    AppException,
    PayloadTooLargeException,
    RateLimitException,
    ValidationException,
)
from app.core.logger import get_logger
from app.core.model_loader import ModelStore, get_model_store
from app.core.rate_limiter import rate_limiter
from app.middleware.auth import get_device_id
from app.services.infer_service import InferService

router = APIRouter()
log = get_logger(__name__)

# ── Size limits per endpoint (bytes) ──────────────────────────────────────────
_FACE_MAX_BYTES         = 15 * 1024 * 1024   # 15 MB
_OBJECTS_MAX_BYTES      = 15 * 1024 * 1024   # 15 MB
_SEGMENT_MAX_BYTES      = 15 * 1024 * 1024   # 15 MB
_FACE_UNIFIED_MAX_BYTES = 10 * 1024 * 1024   # 10 MB (spec: max 10MB)

# ── Max long-side pixels per endpoint ────────────────────────────────────────
_FACE_MAX_PX         = 1200
_OBJECTS_MAX_PX      = 640
_SEGMENT_MAX_PX      = 800
_FACE_UNIFIED_MAX_PX = 1280  # spec: max_long_edge default 1024, max 1280


@router.get("/health", response_model=HealthResponse, tags=["infra"])
async def health(
    models: ModelStore = Depends(get_model_store),
    settings: Settings = Depends(get_settings),
) -> HealthResponse:
    """Infrastructure health check — no auth required. Includes gpu_available flag (v2)."""
    return HealthResponse(
        status="ok",
        gpu_available=models.gpu_available,
        models_loaded=models.loaded_names,
        demo_mode=settings.demo_mode,
        build=settings.build,
    )


@router.post("/infer/face", response_model=FaceInferResponse, tags=["inference"])
async def infer_face(
    image: UploadFile = File(...),
    device_id: str = Depends(get_device_id),
    models: ModelStore = Depends(get_model_store),
    settings: Settings = Depends(get_settings),
) -> FaceInferResponse:
    """
    Human face detection — dlib HOG + 68-point landmark regressor (single face).
    Legacy endpoint; prefer `/infer/face_unified` for multi-face and animated-style support.
    """
    _check_rate(device_id, "face", settings.face_rate_limit)
    img = await _read_and_validate(image, _FACE_MAX_BYTES, _FACE_MAX_PX)
    svc = InferService(models)
    result = svc.infer_face(img)
    log.info("face.infer_complete", device_id=device_id, inference_ms=result.inference_ms)
    return result


@router.post("/infer/face_unified", response_model=FaceUnifiedResponse, tags=["inference"])
async def infer_face_unified(
    image: UploadFile = File(...),
    max_long_edge: str = Form(default="1024"),
    conf: str = Form(default="0.3"),
    iou: str = Form(default="0.5"),
    return_landmarks: str = Form(default="false"),
    device_id: str = Depends(get_device_id),
    models: ModelStore = Depends(get_model_store),
    settings: Settings = Depends(get_settings),
) -> FaceUnifiedResponse:
    """
    Unified human + animated face detection.
    Runs dlib HOG (human) + YOLOv8-animeface ONNX (animated) via ArtGridFaceDomainFusion.
    Optionally attaches 28-point anime landmarks when `return_landmarks=true`
    and the optional landmark model is loaded.
    Rate-limited: 60 requests/device/hour.
    """
    _check_rate(device_id, "face_unified", settings.face_unified_rate_limit)

    max_px = _parse_int_form(max_long_edge, "max_long_edge", default=1024, max_val=_FACE_UNIFIED_MAX_PX)
    conf_f = _parse_float_form(conf, "conf", default=0.3)
    iou_f  = _parse_float_form(iou, "iou", default=0.5)
    landmarks = return_landmarks.lower() == "true"

    img = await _read_and_validate(image, _FACE_UNIFIED_MAX_BYTES, max_px)
    svc = InferService(models)
    result = svc.infer_face_unified(img, conf=conf_f, iou=iou_f, return_landmarks=landmarks)

    log.info(
        "face_unified.infer_complete",
        device_id=device_id,
        face_count=len(result.faces),
        ms_human=result.ms_human,
        ms_animated=result.ms_animated,
        ms_fusion=result.ms_fusion,
    )
    return result


@router.post("/infer/objects", response_model=ObjectInferResponse, tags=["inference"])
async def infer_objects(
    image: UploadFile = File(...),
    device_id: str = Depends(get_device_id),
    models: ModelStore = Depends(get_model_store),
    settings: Settings = Depends(get_settings),
) -> ObjectInferResponse:
    """Object localisation — YOLOv8n ONNX inference."""
    _check_rate(device_id, "objects", settings.objects_rate_limit)
    img = await _read_and_validate(image, _OBJECTS_MAX_BYTES, _OBJECTS_MAX_PX)
    svc = InferService(models)
    result = svc.infer_objects(img)
    log.info("objects.infer_complete", device_id=device_id, inference_ms=result.inference_ms, count=len(result.detections))
    return result


@router.post("/infer/segment", tags=["inference"])
async def infer_segment(
    image: UploadFile = File(...),
    device_id: str = Depends(get_device_id),
    models: ModelStore = Depends(get_model_store),
    settings: Settings = Depends(get_settings),
) -> Response:
    """Background separation — U²-Netp ONNX. Returns Content-Type: image/png mask."""
    _check_rate(device_id, "segment", settings.segment_rate_limit)
    img = await _read_and_validate(image, _SEGMENT_MAX_BYTES, _SEGMENT_MAX_PX)
    svc = InferService(models)
    mask_bytes = svc.infer_segment(img)
    log.info("segment.infer_complete", device_id=device_id, mask_bytes=len(mask_bytes))
    return Response(content=mask_bytes, media_type="image/png")


# ── Private helpers ───────────────────────────────────────────────────────────

def _check_rate(device_id: str, endpoint: str, limit: int) -> None:
    if not rate_limiter.is_allowed(device_id, endpoint, limit):
        raise RateLimitException(f"{endpoint} inference limit reached — try again in 1 hour")


async def _read_and_validate(upload: UploadFile, max_bytes: int, max_px: int) -> np.ndarray:
    """Reads, size-checks, and decodes an uploaded JPEG/WebP. Returns BGR ndarray."""
    raw = await upload.read()

    if len(raw) > max_bytes:
        raise PayloadTooLargeException(
            f"Image payload exceeds {max_bytes // (1024 * 1024)}MB limit"
        )

    arr = np.frombuffer(raw, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)

    if img is None:
        raise ValidationException("Image must be a valid JPEG or WebP file")

    h, w = img.shape[:2]
    long_side = max(h, w)

    if long_side > max_px:
        scale = max_px / long_side
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)

    return img


def _parse_int_form(value: str, field: str, default: int, max_val: int) -> int:
    try:
        v = int(value)
        return min(max(1, v), max_val)
    except ValueError:
        raise ValidationException(f"Form field '{field}' must be an integer, got: {value!r}")


def _parse_float_form(value: str, field: str, default: float) -> float:
    try:
        v = float(value)
        return max(0.0, min(1.0, v))
    except ValueError:
        raise ValidationException(f"Form field '{field}' must be a float, got: {value!r}")
