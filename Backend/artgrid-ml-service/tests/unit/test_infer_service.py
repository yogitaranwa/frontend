"""
test_infer_service.py
Responsibility : Unit tests for InferService business logic.
                 ModelStore is mocked — no real dlib/ONNX calls.
Dependencies   : pytest, pytest-mock, numpy
"""
import numpy as np
import pytest
from unittest.mock import MagicMock

from app.api.v1.infer.schemas import FaceDomain, NormBbox
from app.core.exceptions import InferenceException
from app.core.model_loader import ModelStore
from app.services.infer_service import InferService, _iou, _expand_head_bbox


def _blank_bgr(h: int = 480, w: int = 640) -> np.ndarray:
    """Returns a black BGR image for testing."""
    return np.zeros((h, w, 3), dtype=np.uint8)


# ── Legacy single-face detection ────────────────────────────────────────────────

class TestInferFace:
    def test_raises_when_model_not_loaded(self):
        svc = InferService(ModelStore())
        with pytest.raises(InferenceException):
            svc.infer_face(_blank_bgr())

    def test_returns_no_face_on_blank_image(self):
        """With a real HOG detector and a blank image, no face should be found."""
        try:
            import dlib
        except ImportError:
            pytest.skip("dlib not installed in test environment")

        store = ModelStore()
        store.face_detector = dlib.get_frontal_face_detector()
        svc = InferService(store)
        result = svc.infer_face(_blank_bgr())
        assert result.face_detected is False
        assert result.face_bbox is None
        assert result.landmarks == []


# ── Unified face detection ──────────────────────────────────────────────────────

class TestInferFaceUnified:
    def test_returns_empty_faces_when_no_models_loaded(self):
        """With no models loaded, unified face should return empty list (graceful degradation)."""
        svc = InferService(ModelStore())
        result = svc.infer_face_unified(_blank_bgr())
        assert result.faces == []
        assert result.request_id  # UUID should be populated
        assert isinstance(result.telemetry.domain_histogram, dict)

    def test_telemetry_counts_domains(self):
        """Telemetry histogram must include all three domain keys."""
        svc = InferService(ModelStore())
        result = svc.infer_face_unified(_blank_bgr())
        hist = result.telemetry.domain_histogram
        assert "human" in hist
        assert "animated" in hist
        assert "unknown" in hist

    def test_human_path_skipped_gracefully_without_dlib(self):
        """When only animeface is loaded, human path returns empty and doesn't crash."""
        store = ModelStore()
        store.yolo_animeface_session = MagicMock()
        # Empty output: [1, 5, 0] — no detections
        store.yolo_animeface_session.run.return_value = [np.zeros((1, 5, 0), dtype=np.float32)]
        store.yolo_animeface_session.get_inputs.return_value = [MagicMock(name="images")]
        svc = InferService(store)
        result = svc.infer_face_unified(_blank_bgr())
        assert result.faces == []

    def test_animated_face_detected_from_yolo_output(self):
        """
        Simulates a single high-confidence anime face detection.
        YOLO animeface output: [1, 5, N] — cx,cy,w,h,conf.
        Place a single detection at centre with conf=0.85.
        """
        store = ModelStore()
        store.yolo_animeface_session = MagicMock()

        # Single detection: cx=320, cy=240, w=100, h=120, conf=0.85 (at 640px scale)
        detection = np.array([[320.0, 240.0, 100.0, 120.0, 0.85]], dtype=np.float32).T
        output = detection[np.newaxis, ...]   # shape [1, 5, 1]
        store.yolo_animeface_session.run.return_value = [output]
        input_mock = MagicMock()
        input_mock.name = "images"
        store.yolo_animeface_session.get_inputs.return_value = [input_mock]

        svc = InferService(store)
        result = svc.infer_face_unified(_blank_bgr(), conf=0.3, iou=0.5)

        assert len(result.faces) == 1
        assert result.faces[0].domain == FaceDomain.ANIMATED
        assert result.faces[0].confidence == pytest.approx(0.85, abs=0.01)
        assert result.telemetry.domain_histogram["animated"] == 1
        assert result.telemetry.domain_histogram["human"] == 0

    def test_cross_head_nms_suppresses_overlapping_faces(self):
        """
        When human and animated detections overlap strongly (IoU > threshold), NMS must
        keep only the highest-confidence detection.
        dlib returns pixel coordinates for a 640×480 image.
        Animeface YOLO uses cx/cy/w/h in the 640-pixel grid space.
        We construct identical normalised bboxes for both heads so IoU == 1.0.
        """
        store = ModelStore()
        img_w, img_h = 640, 480

        # dlib mock: left=270, top=180, width=100, height=160 on 640×480
        # → x_norm=0.421875, y_norm=0.375, w_norm=0.15625, h_norm=0.333...
        mock_detector = MagicMock()
        mock_predictor = MagicMock()
        mock_det = MagicMock()
        mock_det.left.return_value = 270
        mock_det.top.return_value = 180
        mock_det.width.return_value = 100
        mock_det.height.return_value = 160
        mock_detector.return_value = [mock_det]
        mock_shape = MagicMock()
        mock_shape.part.return_value = MagicMock(x=320, y=260)
        mock_predictor.return_value = mock_shape
        store.face_detector = mock_detector
        store.landmark_predictor = mock_predictor

        # Animeface YOLO mock: produce the identical normalised bbox.
        # x_norm=0.421875 → cx = (0.421875 + 0.15625/2) * 640 = (0.421875 + 0.078125) * 640 = 320
        # y_norm=0.375    → cy = (0.375 + 0.333.../2) * 640 = (0.375 + 0.1666...) * 640 = 345.6
        # w_norm=0.15625  → bw = 0.15625 * 640 = 100
        # h_norm=0.3333   → bh = (160/480) * 640 = 213.33
        cx = (0.421875 + (100 / img_w) / 2) * 640       # 320.0
        cy = (0.375 + (160 / img_h) / 2) * 640          # 345.6
        bw = (100 / img_w) * 640                         # 100.0
        bh = (160 / img_h) * 640                         # 213.33

        store.yolo_animeface_session = MagicMock()
        detection = np.array([[cx, cy, bw, bh, 0.9]], dtype=np.float32).T
        store.yolo_animeface_session.run.return_value = [detection[np.newaxis, ...]]
        input_mock = MagicMock()
        input_mock.name = "images"
        store.yolo_animeface_session.get_inputs.return_value = [input_mock]

        svc = InferService(store)
        result = svc.infer_face_unified(_blank_bgr(img_h, img_w), conf=0.3, iou=0.5)

        # After cross-head NMS, only one detection should remain.
        assert len(result.faces) == 1

    def test_landmarks_28_empty_when_not_requested(self):
        """landmarks_28 list must be empty when return_landmarks=False."""
        store = ModelStore()
        store.yolo_animeface_session = MagicMock()
        detection = np.array([[320.0, 240.0, 100.0, 120.0, 0.85]], dtype=np.float32).T
        store.yolo_animeface_session.run.return_value = [detection[np.newaxis, ...]]
        input_mock = MagicMock()
        input_mock.name = "images"
        store.yolo_animeface_session.get_inputs.return_value = [input_mock]

        svc = InferService(store)
        result = svc.infer_face_unified(_blank_bgr(), conf=0.3, iou=0.5, return_landmarks=False)

        assert len(result.faces) == 1
        assert result.faces[0].landmarks_28 == []


# ── Object detection ────────────────────────────────────────────────────────────

class TestInferObjects:
    def test_raises_when_model_not_loaded(self):
        svc = InferService(ModelStore())
        with pytest.raises(InferenceException):
            svc.infer_objects(_blank_bgr())

    def test_postprocess_yolo_no_detections_on_zeros(self):
        """ONNX output of all zeros should produce no detections above threshold."""
        store = ModelStore()
        store.yolo_session = MagicMock()
        store.yolo_session.run.return_value = [np.zeros((1, 84, 8400), dtype=np.float32)]
        svc = InferService(store)
        result = svc.infer_objects(_blank_bgr())
        assert result.detections == []
        assert result.model == "yolov8n"


# ── Segmentation ──────────────────────────────────────────────────────────────

class TestInferSegment:
    def test_raises_when_model_not_loaded(self):
        svc = InferService(ModelStore())
        with pytest.raises(InferenceException):
            svc.infer_segment(_blank_bgr())

    def test_returns_png_bytes(self):
        """Postprocess with a uniform salience map should return valid PNG bytes."""
        store = ModelStore()
        store.u2netp_session = MagicMock()
        store.u2netp_session.run.return_value = [
            np.full((1, 1, 320, 320), 0.5, dtype=np.float32)
        ]
        svc = InferService(store)
        result = svc.infer_segment(_blank_bgr(480, 640))
        assert isinstance(result, bytes)
        assert result[:4] == b"\x89PNG"


# ── Utility functions ─────────────────────────────────────────────────────────

class TestIoU:
    def test_identical_boxes(self):
        b = NormBbox(x_norm=0.1, y_norm=0.1, w_norm=0.5, h_norm=0.5)
        assert _iou(b, b) == pytest.approx(1.0)

    def test_non_overlapping_boxes(self):
        a = NormBbox(x_norm=0.0, y_norm=0.0, w_norm=0.4, h_norm=0.4)
        b = NormBbox(x_norm=0.6, y_norm=0.6, w_norm=0.4, h_norm=0.4)
        assert _iou(a, b) == pytest.approx(0.0)

    def test_partial_overlap(self):
        a = NormBbox(x_norm=0.0, y_norm=0.0, w_norm=0.5, h_norm=0.5)
        b = NormBbox(x_norm=0.25, y_norm=0.25, w_norm=0.5, h_norm=0.5)
        iou = _iou(a, b)
        assert 0.0 < iou < 1.0


class TestExpandHeadBbox:
    def test_gamma_expansion_does_not_exceed_bounds(self):
        bbox = NormBbox(x_norm=0.4, y_norm=0.4, w_norm=0.2, h_norm=0.2)
        expanded = _expand_head_bbox(bbox, gamma=1.6)
        assert expanded.x_norm >= 0.0
        assert expanded.y_norm >= 0.0
        assert expanded.w_norm <= 1.0
        assert expanded.h_norm <= 1.0
        assert expanded.gamma == pytest.approx(1.6)

    def test_expansion_increases_area(self):
        bbox = NormBbox(x_norm=0.3, y_norm=0.3, w_norm=0.4, h_norm=0.4)
        expanded = _expand_head_bbox(bbox, gamma=1.6)
        assert expanded.w_norm >= bbox.w_norm
        assert expanded.h_norm >= bbox.h_norm
