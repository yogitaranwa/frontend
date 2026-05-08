"""
settings.py
Responsibility : Typed configuration via Pydantic BaseSettings — fails fast on missing vars.
Pattern used   : Singleton config loaded once at import time.
Dependencies   : pydantic-settings, os environment
"""
from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # ── Service ───────────────────────────────────────────────────────────────
    port: int = Field(default=8001, alias="PORT")
    demo_mode: bool = Field(default=True, alias="DEMO_MODE")
    log_level: str = Field(default="info", alias="LOG_LEVEL")
    build: str = Field(default="dev", alias="BUILD")

    # ── Auth (shared HMAC secret with artgrid-auth-service) ──────────────────
    jwt_secret: str = Field(alias="JWT_SECRET")

    # ── Model paths (mounted from ./models volume) ───────────────────────────
    dlib_face_detector_path: str = Field(
        default="/app/models/mmod_human_face_detector.dat",
        alias="DLIB_FACE_DETECTOR_PATH",
    )
    dlib_landmark_path: str = Field(
        default="/app/models/shape_predictor_68_face_landmarks.dat",
        alias="DLIB_LANDMARK_PATH",
    )
    yolo_model_path: str = Field(
        default="/app/models/yolov8n.onnx",
        alias="YOLO_MODEL_PATH",
    )
    u2netp_model_path: str = Field(
        default="/app/models/u2netp.onnx",
        alias="U2NETP_MODEL_PATH",
    )

    # ── YOLOv8 anime-face ONNX (Fuyucchi/yolov8_animeface on HuggingFace) ──
    yolo_animeface_model_path: str = Field(
        default="/app/models/yolov8_animeface.onnx",
        alias="YOLO_ANIMEFACE_MODEL_PATH",
    )

    # ── Optional 28-point anime landmark model ────────────────────────────────
    anime_landmarks_28_model_path: str = Field(
        default="/app/models/anime_landmarks_28.onnx",
        alias="ANIME_LANDMARKS_28_MODEL_PATH",
    )

    # ── Rate limits (per device per hour, in-memory) ──────────────────────────
    face_rate_limit: int = Field(default=20, alias="FACE_RATE_LIMIT")
    objects_rate_limit: int = Field(default=30, alias="OBJECTS_RATE_LIMIT")
    segment_rate_limit: int = Field(default=10, alias="SEGMENT_RATE_LIMIT")
    face_unified_rate_limit: int = Field(default=60, alias="FACE_UNIFIED_RATE_LIMIT")

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8", "populate_by_name": True}


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Return the cached Settings singleton. Use FastAPI Depends(get_settings)."""
    return Settings()
