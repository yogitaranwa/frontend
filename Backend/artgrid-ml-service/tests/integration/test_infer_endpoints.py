"""
test_infer_endpoints.py
Integration tests for /infer/objects and /infer/segment.

Strategy:
  - Uses FastAPI TestClient (no real network).
  - ML models do NOT need to be present — InferService is mocked at the module level.
  - Synthetic in-memory JPEG images are generated with PIL (or raw bytes if PIL missing).
  - Auth tokens are generated with the same JWT_SECRET used by conftest.py.
  - Tests cover: successful inference, empty detection, payload-too-large, invalid image, no auth.
"""
import io
import os
import struct
import zlib
from unittest.mock import patch

import pytest

# Must match conftest.py JWT_SECRET (set via pytest_configure before app loads).
_JWT_SECRET = "test-secret-for-pytest-only-not-real"


# ── Minimal in-memory image factories ────────────────────────────────────────

def _make_jpeg_bytes(width: int = 64, height: int = 64) -> bytes:
    """Return a minimal valid JPEG byte string for a solid grey image."""
    try:
        from PIL import Image as PILImage
        img = PILImage.new("RGB", (width, height), color=(180, 180, 180))
        buf = io.BytesIO()
        img.save(buf, format="JPEG")
        return buf.getvalue()
    except ImportError:
        # Minimal 1×1 grey JFIF JPEG (universally parseable)
        return (
            b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00"
            b"\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t"
            b"\x08\n\x0c\x14\r\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a"
            b"\x1f\x1e\x1d\x1a\x1c\x1c $.' \",#\x1c\x1c(7),01444\x1f'9=82<.342\x1e"
            b"\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff\xc4\x00"
            b"\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00"
            b"\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\xff\xda\x00"
            b"\x08\x01\x01\x00\x00?\x00\xfb\xd3\xff\xd9"
        )


def _make_png_bytes(width: int = 64, height: int = 64) -> bytes:
    """Return a minimal valid PNG byte string."""
    try:
        from PIL import Image as PILImage
        img = PILImage.new("RGBA", (width, height), color=(0, 0, 0, 255))
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return buf.getvalue()
    except ImportError:
        def make_chunk(name: bytes, data: bytes) -> bytes:
            c = zlib.crc32(name + data) & 0xFFFFFFFF
            return struct.pack(">I", len(data)) + name + data + struct.pack(">I", c)

        ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
        raw = b"\x00\x00\x00\x00"
        idat = zlib.compress(raw)
        return (
            b"\x89PNG\r\n\x1a\n"
            + make_chunk(b"IHDR", ihdr)
            + make_chunk(b"IDAT", idat)
            + make_chunk(b"IEND", b"")
        )


def _make_valid_token() -> str:
    """Generate a JWT signed with the test secret that conftest.py sets."""
    import jwt
    token = jwt.encode({"sub": "test-device-001"}, _JWT_SECRET, algorithm="HS256")
    return token if isinstance(token, str) else token.decode()


# ── Fixtures ─────────────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def client():
    """
    TestClient. Models don't need to be present — InferService is mocked per-test.
    """
    os.environ["DLIB_FACE_DETECTOR_PATH"]       = "/nonexistent/face.dat"
    os.environ["DLIB_LANDMARK_PATH"]            = "/nonexistent/landmark.dat"
    os.environ["YOLO_MODEL_PATH"]               = "/nonexistent/yolov8n.onnx"
    os.environ["U2NETP_MODEL_PATH"]             = "/nonexistent/u2netp.onnx"
    os.environ["YOLO_ANIMEFACE_MODEL_PATH"]     = "/nonexistent/animeface.onnx"
    os.environ["ANIME_LANDMARKS_28_MODEL_PATH"] = "/nonexistent/landmarks28.onnx"

    from app.main import app
    from fastapi.testclient import TestClient
    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture(scope="module")
def auth_headers() -> dict:
    return {"Authorization": f"Bearer {_make_valid_token()}"}


def _jpeg_file(name: str = "test.jpg", size: tuple = (64, 64)):
    return (name, _make_jpeg_bytes(*size), "image/jpeg")


# ── /infer/objects tests ──────────────────────────────────────────────────────

class TestInferObjects:
    def test_no_auth_returns_401(self, client):
        """Objects endpoint must require a Bearer token."""
        r = client.post("/infer/objects", files={"image": _jpeg_file()})
        assert r.status_code == 401

    def test_invalid_image_returns_error(self, client, auth_headers):
        """Non-image bytes should return a 4xx error, not crash the server."""
        r = client.post(
            "/infer/objects",
            files={"image": ("bad.jpg", b"this is not an image", "image/jpeg")},
            headers=auth_headers,
        )
        assert r.status_code in (400, 422), f"Expected 4xx, got {r.status_code}: {r.text}"

    def test_payload_too_large_returns_413(self, client, auth_headers):
        """Payloads exceeding the per-endpoint limit must return 413."""
        huge = b"\x00" * (16 * 1024 * 1024)  # 16 MB > 15 MB limit
        r = client.post(
            "/infer/objects",
            files={"image": ("big.jpg", huge, "image/jpeg")},
            headers=auth_headers,
        )
        assert r.status_code == 413, f"Expected 413, got {r.status_code}"

    def test_valid_image_returns_200_with_detections_list(self, client, auth_headers):
        """
        A real JPEG should reach InferService and return a JSON body
        with a 'detections' list.
        """
        from app.api.v1.infer.schemas import NormBbox, ObjectDetection, ObjectInferResponse

        mock_detection = ObjectDetection(
            label="person",
            confidence=0.91,
            bbox=NormBbox(x_norm=0.1, y_norm=0.1, w_norm=0.5, h_norm=0.8),
        )
        mock_result = ObjectInferResponse(
            detections=[mock_detection],
            inference_ms=42,
            model="yolov8n",
        )

        with patch("app.api.v1.infer.router.InferService") as MockSvc:
            MockSvc.return_value.infer_objects.return_value = mock_result
            r = client.post(
                "/infer/objects",
                files={"image": _jpeg_file()},
                headers=auth_headers,
            )

        assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
        body = r.json()
        assert "detections" in body, f"Missing 'detections' key: {body}"
        assert isinstance(body["detections"], list)
        assert len(body["detections"]) == 1
        assert body["detections"][0]["label"] == "person"

    def test_empty_detection_returns_200_with_empty_list(self, client, auth_headers):
        """
        When the model finds nothing, the server MUST return 200 + detections: [].
        The client-side MlFeatureState.Empty logic then handles the empty case.
        This is NOT a server error.
        """
        from app.api.v1.infer.schemas import ObjectInferResponse

        mock_result = ObjectInferResponse(detections=[], inference_ms=15, model="yolov8n")

        with patch("app.api.v1.infer.router.InferService") as MockSvc:
            MockSvc.return_value.infer_objects.return_value = mock_result
            r = client.post(
                "/infer/objects",
                files={"image": _jpeg_file()},
                headers=auth_headers,
            )

        assert r.status_code == 200, (
            f"Expected 200 for empty result (not an error), got {r.status_code}: {r.text}"
        )
        body = r.json()
        assert body["detections"] == [], f"Expected empty list, got {body['detections']}"


# ── /infer/segment tests ──────────────────────────────────────────────────────

class TestInferSegment:
    def test_no_auth_returns_401(self, client):
        r = client.post("/infer/segment", files={"image": _jpeg_file()})
        assert r.status_code == 401

    def test_invalid_image_returns_error(self, client, auth_headers):
        r = client.post(
            "/infer/segment",
            files={"image": ("bad.jpg", b"garbage bytes", "image/jpeg")},
            headers=auth_headers,
        )
        assert r.status_code in (400, 422), f"Expected 4xx, got {r.status_code}: {r.text}"

    def test_payload_too_large_returns_413(self, client, auth_headers):
        huge = b"\x00" * (16 * 1024 * 1024)
        r = client.post(
            "/infer/segment",
            files={"image": ("big.jpg", huge, "image/jpeg")},
            headers=auth_headers,
        )
        assert r.status_code == 413, f"Expected 413, got {r.status_code}"

    def test_valid_image_returns_200_png_content_type(self, client, auth_headers):
        """
        A successful segment call must return:
          - HTTP 200
          - Content-Type: image/png
          - Non-empty body
        """
        png_mask = _make_png_bytes(64, 64)

        with patch("app.api.v1.infer.router.InferService") as MockSvc:
            MockSvc.return_value.infer_segment.return_value = png_mask
            r = client.post(
                "/infer/segment",
                files={"image": _jpeg_file()},
                headers=auth_headers,
            )

        assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
        content_type = r.headers.get("content-type", "")
        assert "image/png" in content_type, (
            f"Expected image/png content-type, got {content_type!r}"
        )
        assert len(r.content) > 0, "Expected non-empty PNG body"

    def test_segment_returns_valid_png_header(self, client, auth_headers):
        """Response body must start with the PNG magic bytes."""
        png_mask = _make_png_bytes(64, 64)

        with patch("app.api.v1.infer.router.InferService") as MockSvc:
            MockSvc.return_value.infer_segment.return_value = png_mask
            r = client.post(
                "/infer/segment",
                files={"image": _jpeg_file()},
                headers=auth_headers,
            )

        assert r.status_code == 200
        PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
        assert r.content[:8] == PNG_MAGIC, (
            f"Response does not start with PNG magic bytes: {r.content[:16]!r}"
        )


# ── Health additional checks ──────────────────────────────────────────────────

class TestHealthAdditional:
    def test_models_loaded_is_list(self, client):
        """models_loaded must always be a list (may be empty if files missing)."""
        r = client.get("/health")
        assert r.status_code == 200
        body = r.json()
        assert isinstance(body["models_loaded"], list), (
            f"models_loaded should be a list, got {type(body['models_loaded'])}"
        )

    def test_demo_mode_is_bool(self, client):
        r = client.get("/health")
        body = r.json()
        assert isinstance(body["demo_mode"], bool)

    def test_inference_ms_is_non_negative(self, client, auth_headers):
        """inference_ms in objects response should be >= 0."""
        from app.api.v1.infer.schemas import ObjectInferResponse

        mock_result = ObjectInferResponse(detections=[], inference_ms=0, model="yolov8n")
        with patch("app.api.v1.infer.router.InferService") as MockSvc:
            MockSvc.return_value.infer_objects.return_value = mock_result
            r = client.post(
                "/infer/objects",
                files={"image": _jpeg_file()},
                headers=auth_headers,
            )
        if r.status_code == 200:
            assert r.json()["inference_ms"] >= 0
