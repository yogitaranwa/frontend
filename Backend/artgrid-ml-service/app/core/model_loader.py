"""
model_loader.py
Responsibility : Loads and holds all ML models in memory on application startup.
                 Detects GPU availability (CUDA via onnxruntime-gpu) and selects execution provider.
Pattern used   : Singleton (module-level instances, loaded once via lifespan event).
Dependencies   : dlib, onnxruntime (or onnxruntime-gpu), settings
"""
import time
from dataclasses import dataclass, field
from pathlib import Path

import dlib
import onnxruntime as ort

from app.config.settings import Settings
from app.core.logger import get_logger

log = get_logger(__name__)


def _detect_gpu() -> tuple[bool, list[str]]:
    """
    Checks whether an NVIDIA CUDA execution provider is available in onnxruntime.
    Returns (gpu_available, preferred_providers_list).
    """
    available = ort.get_available_providers()
    if "CUDAExecutionProvider" in available:
        return True, ["CUDAExecutionProvider", "CPUExecutionProvider"]
    return False, ["CPUExecutionProvider"]


@dataclass
class ModelStore:
    """Holds all resident ML model objects and GPU availability state."""

    face_detector: dlib.fhog_object_detector | None = field(default=None)
    landmark_predictor: dlib.shape_predictor | None = field(default=None)
    yolo_session: ort.InferenceSession | None = field(default=None)
    u2netp_session: ort.InferenceSession | None = field(default=None)
    # Unified human + anime face detection (YOLOv8 anime-face ONNX, single-class: face)
    yolo_animeface_session: ort.InferenceSession | None = field(default=None)
    # Optional 28-point anime landmark model
    anime_landmarks_28_session: ort.InferenceSession | None = field(default=None)
    gpu_available: bool = field(default=False)

    @property
    def loaded_names(self) -> list[str]:
        names: list[str] = []
        if self.face_detector and self.landmark_predictor:
            names.append("face_dlib")
        if self.yolo_session:
            names.append("yolov8n_objects")
        if self.u2netp_session:
            names.append("u2netp")
        if self.yolo_animeface_session:
            names.append("yolov8_animeface_onnx")
        if self.anime_landmarks_28_session:
            names.append("anime_landmarks_28_optional")
        return names


# Module-level singleton — populated by load_all_models().
_store: ModelStore = ModelStore()


def get_model_store() -> ModelStore:
    """FastAPI dependency: returns the populated ModelStore singleton."""
    return _store


def load_all_models(settings: Settings) -> None:
    """
    Loads all ML models into the module-level ModelStore.
    Call from the FastAPI lifespan startup event.
    Logs the load time for each model — useful for diagnosing slow cold starts.
    """
    gpu_available, providers = _detect_gpu()
    _store.gpu_available = gpu_available
    log.info(
        "gpu.detection_complete",
        gpu_available=gpu_available,
        providers=providers,
    )

    _load_dlib(settings)
    _load_yolo(settings, providers)
    _load_u2netp(settings, providers)
    _load_yolo_animeface(settings, providers)
    _load_anime_landmarks_28(settings, providers)

    log.info("models.all_loaded", models=_store.loaded_names, gpu=gpu_available)


def _load_dlib(settings: Settings) -> None:
    """dlib built-in HOG frontal face detector + 68-point landmark regressor (.dat on disk)."""
    lm_path = Path(settings.dlib_landmark_path)

    if not lm_path.exists():
        log.warning(
            "dlib.models_not_found",
            path=str(lm_path),
            hint="run backend/download_models.sh (or download_models.ps1) before docker compose",
        )
        return

    t0 = time.monotonic()
    _store.face_detector = dlib.get_frontal_face_detector()
    _store.landmark_predictor = dlib.shape_predictor(str(lm_path))
    log.info("dlib.loaded", elapsed_ms=round((time.monotonic() - t0) * 1000))


def _load_yolo(settings: Settings, providers: list[str]) -> None:
    """YOLOv8n ONNX session for object detection."""
    model_path = Path(settings.yolo_model_path)
    if not model_path.exists():
        log.warning("yolo.model_not_found", path=str(model_path))
        return

    t0 = time.monotonic()
    try:
        _store.yolo_session = ort.InferenceSession(str(model_path), providers=providers)
        log.info("yolov8n.loaded", elapsed_ms=round((time.monotonic() - t0) * 1000))
    except Exception as exc:
        log.warning("yolo.load_failed", path=str(model_path), error=str(exc),
                    elapsed_ms=round((time.monotonic() - t0) * 1000))


def _load_u2netp(settings: Settings, providers: list[str]) -> None:
    """U²-Netp ONNX session for subject/background mask."""
    model_path = Path(settings.u2netp_model_path)
    if not model_path.exists():
        log.warning("u2netp.model_not_found", path=str(model_path))
        return

    t0 = time.monotonic()
    try:
        _store.u2netp_session = ort.InferenceSession(str(model_path), providers=providers)
        log.info("u2netp.loaded", elapsed_ms=round((time.monotonic() - t0) * 1000))
    except Exception as exc:
        log.warning("u2netp.load_failed", path=str(model_path), error=str(exc),
                    elapsed_ms=round((time.monotonic() - t0) * 1000))


def _load_yolo_animeface(settings: Settings, providers: list[str]) -> None:
    """
    YOLOv8 anime-face ONNX session.
    Model: Fuyucchi/yolov8_animeface (HuggingFace) exported to ONNX.
    Single-class detector; output shape [1, 5, N] (cx,cy,w,h,conf).
    Optional — service runs in human-only mode if file is missing.
    """
    model_path = Path(settings.yolo_animeface_model_path)
    if not model_path.exists():
        log.warning(
            "yolo_animeface.model_not_found",
            path=str(model_path),
            hint="run download_models.sh to fetch yolov8_animeface.onnx",
        )
        return

    t0 = time.monotonic()
    try:
        _store.yolo_animeface_session = ort.InferenceSession(str(model_path), providers=providers)
        log.info("yolov8_animeface.loaded", elapsed_ms=round((time.monotonic() - t0) * 1000))
    except Exception as exc:
        log.warning("yolo_animeface.load_failed", path=str(model_path), error=str(exc),
                    elapsed_ms=round((time.monotonic() - t0) * 1000))


def _load_anime_landmarks_28(settings: Settings, providers: list[str]) -> None:
    """
    Optional 28-point anime landmark ONNX model.
    Loaded only when the model file exists. When absent, clients fall back to
    bbox + grid — graceful degradation per API contract.
    """
    model_path = Path(settings.anime_landmarks_28_model_path)
    if not model_path.exists():
        log.info(
            "anime_landmarks_28.not_found",
            hint="28-pt landmark model is optional — bbox grid fallback active",
        )
        return

    t0 = time.monotonic()
    try:
        _store.anime_landmarks_28_session = ort.InferenceSession(str(model_path), providers=providers)
        log.info("anime_landmarks_28.loaded", elapsed_ms=round((time.monotonic() - t0) * 1000))
    except Exception as exc:
        log.warning("anime_landmarks_28.load_failed", path=str(model_path), error=str(exc),
                    elapsed_ms=round((time.monotonic() - t0) * 1000))
