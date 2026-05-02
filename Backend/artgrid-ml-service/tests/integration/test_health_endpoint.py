"""
test_health_endpoint.py
Integration tests for GET /health and auth enforcement on all /infer/* endpoints.
Uses FastAPI TestClient (no real models needed).
"""
import os

import pytest
from fastapi.testclient import TestClient


@pytest.fixture(scope="module")
def client():
    """Create a TestClient without loading ML models (model files don't exist in CI)."""
    os.environ.setdefault("JWT_SECRET", "test-secret-at-least-32-characters-long")
    os.environ["DLIB_FACE_DETECTOR_PATH"]      = "/nonexistent/face.dat"
    os.environ["DLIB_LANDMARK_PATH"]           = "/nonexistent/landmark.dat"
    os.environ["YOLO_MODEL_PATH"]              = "/nonexistent/yolov8n.onnx"
    os.environ["U2NETP_MODEL_PATH"]            = "/nonexistent/u2netp.onnx"
    os.environ["YOLO_ANIMEFACE_MODEL_PATH"]    = "/nonexistent/yolov8_animeface.onnx"
    os.environ["ANIME_LANDMARKS_28_MODEL_PATH"] = "/nonexistent/anime_landmarks_28.onnx"

    from app.main import app
    return TestClient(app, raise_server_exceptions=False)


class TestHealthEndpoint:
    def test_health_returns_200(self, client: TestClient):
        response = client.get("/health")
        assert response.status_code == 200

    def test_health_response_shape_v2(self, client: TestClient):
        """v2 health response includes gpu_available and build fields."""
        body = client.get("/health").json()
        assert body["status"] == "ok"
        assert "gpu_available" in body
        assert "models_loaded" in body
        assert "demo_mode" in body
        assert "build" in body
        # Legacy field removed in v2.
        assert "server_ip" not in body

    def test_health_no_auth_required(self, client: TestClient):
        response = client.get("/health", headers={})
        assert response.status_code == 200

    def test_models_loaded_empty_when_files_missing(self, client: TestClient):
        body = client.get("/health").json()
        assert isinstance(body["models_loaded"], list)
        assert body["models_loaded"] == []

    def test_gpu_available_is_bool(self, client: TestClient):
        body = client.get("/health").json()
        assert isinstance(body["gpu_available"], bool)


class TestInferEndpointsRequireAuth:
    """All /infer/* endpoints must return 401 when no Bearer token is present."""

    def test_face_no_auth(self, client: TestClient):
        r = client.post("/infer/face", files={"image": ("t.jpg", b"fake", "image/jpeg")})
        assert r.status_code == 401

    def test_face_unified_no_auth(self, client: TestClient):
        r = client.post("/infer/face_unified", files={"image": ("t.jpg", b"fake", "image/jpeg")})
        assert r.status_code == 401

    def test_objects_no_auth(self, client: TestClient):
        r = client.post("/infer/objects", files={"image": ("t.jpg", b"fake", "image/jpeg")})
        assert r.status_code == 401

    def test_segment_no_auth(self, client: TestClient):
        r = client.post("/infer/segment", files={"image": ("t.jpg", b"fake", "image/jpeg")})
        assert r.status_code == 401
