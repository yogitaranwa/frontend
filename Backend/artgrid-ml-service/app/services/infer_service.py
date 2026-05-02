"""
infer_service.py
Responsibility : Business logic for all inference types — face, unified face, objects, segment.
                 No HTTP imports. Receives pre-validated numpy images from the controller.
Pattern used   : Service layer with injected ModelStore.
Dependencies   : dlib, onnxruntime, numpy, Pillow, opencv
"""
import io
import time
import uuid
from typing import Any

import cv2
import dlib
import numpy as np
from PIL import Image

from app.api.v1.infer.schemas import (
    FaceDomain,
    FaceInferResponse,
    FaceLandmark,
    FaceUnifiedResponse,
    FaceUnifiedTelemetry,
    HeadBboxExpanded,
    NormBbox,
    ObjectDetection,
    ObjectInferResponse,
    UnifiedFaceDetection,
)
from app.core.exceptions import InferenceException
from app.core.logger import get_logger
from app.core.model_loader import ModelStore

log = get_logger(__name__)

# YOLOv8n COCO class names (80 classes)
_COCO_LABELS: list[str] = [
    "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
    "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
    "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
    "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
    "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
    "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
    "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
    "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse",
    "remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator",
    "book","clock","vase","scissors","teddy bear","hair drier","toothbrush",
]

_YOLO_INPUT_SIZE = 640
_YOLO_CONF_THRESHOLD = 0.25
_U2NETP_INPUT_SIZE = 320
_HEAD_GAMMA = 1.6       # expansion factor for head_bbox_expanded
_FUSION_IOU_THRESHOLD = 0.5  # cross-head NMS IoU threshold


class InferService:
    """Stateless inference service — ModelStore injected via constructor."""

    def __init__(self, models: ModelStore) -> None:
        self._models = models

    # ── F-10 Face Detection (legacy single-face endpoint) ─────────────────────

    def infer_face(self, img_bgr: np.ndarray) -> FaceInferResponse:
        """Runs dlib HOG detector + 68-point landmark prediction on [img_bgr]."""
        if self._models.face_detector is None:
            raise InferenceException("dlib models not loaded — check server startup logs")

        h, w = img_bgr.shape[:2]
        img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)

        t0 = time.monotonic()
        detections = self._models.face_detector(img_rgb, 1)
        elapsed_ms = round((time.monotonic() - t0) * 1000)

        if len(detections) == 0:
            return FaceInferResponse(
                face_detected=False,
                face_bbox=None,
                landmarks=[],
                inference_ms=elapsed_ms,
            )

        if self._models.landmark_predictor is None:
            raise InferenceException("dlib landmark predictor not loaded — check server startup logs")

        det = max(detections, key=lambda d: d.width() * d.height())
        shape = self._models.landmark_predictor(img_rgb, det)

        bbox = NormBbox(
            x_norm=max(0.0, det.left() / w),
            y_norm=max(0.0, det.top() / h),
            w_norm=min(1.0, det.width() / w),
            h_norm=min(1.0, det.height() / h),
        )
        landmarks = [
            FaceLandmark(id=i, x_norm=shape.part(i).x / w, y_norm=shape.part(i).y / h)
            for i in range(68)
        ]
        return FaceInferResponse(
            face_detected=True,
            face_bbox=bbox,
            landmarks=landmarks,
            inference_ms=elapsed_ms,
        )

    # ── F-34 + F-34-LM: Unified Human + Animated Face Detection ──────────────

    def infer_face_unified(
        self,
        img_bgr: np.ndarray,
        conf: float = 0.3,
        iou: float = 0.5,
        return_landmarks: bool = False,
    ) -> FaceUnifiedResponse:
        """
        ArtGridFaceDomainFusion: runs dlib HOG (human) and YOLOv8-animeface (animated)
        on the same image in sequence, then resolves overlapping detections with cross-head NMS.
        Each face is classified as human, animated, or unknown.
        Optionally attaches 28-pt anime landmarks for animated faces (F-34-LM).
        """
        request_id = str(uuid.uuid4())
        h, w = img_bgr.shape[:2]
        img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)

        # ── Preprocess timer ──────────────────────────────────────────────────
        t_pre = time.monotonic()
        ms_preprocess = round((time.monotonic() - t_pre) * 1000)

        # ── Human path: dlib HOG + 68-pt landmarks ────────────────────────────
        human_faces = self._run_dlib_human(img_rgb, w, h)
        ms_human = human_faces["elapsed_ms"]

        # ── Animated path: YOLOv8-animeface ───────────────────────────────────
        animated_faces = self._run_yolo_animeface(img_rgb, w, h, conf)
        ms_animated = animated_faces["elapsed_ms"]

        # ── ArtGridFaceDomainFusion: cross-head NMS ───────────────────────────
        t_fusion = time.monotonic()
        fused = self._fuse_detections(
            human_faces["faces"],
            animated_faces["faces"],
            iou,
            img_rgb,
            w,
            h,
            return_landmarks,
        )
        ms_fusion = round((time.monotonic() - t_fusion) * 1000)

        histogram: dict[str, int] = {"human": 0, "animated": 0, "unknown": 0}
        for face in fused:
            histogram[face.domain.value] += 1

        return FaceUnifiedResponse(
            request_id=request_id,
            faces=fused,
            ms_preprocess=ms_preprocess,
            ms_human=ms_human,
            ms_animated=ms_animated,
            ms_fusion=ms_fusion,
            telemetry=FaceUnifiedTelemetry(domain_histogram=histogram),
        )

    # ── F-11 Object Detection ─────────────────────────────────────────────────

    def infer_objects(self, img_bgr: np.ndarray) -> ObjectInferResponse:
        """Runs YOLOv8n ONNX inference; returns normalised detections above threshold."""
        if self._models.yolo_session is None:
            raise InferenceException("yolov8n model not loaded — check server startup logs")

        orig_h, orig_w = img_bgr.shape[:2]
        input_tensor = self._preprocess_yolo(img_bgr)

        t0 = time.monotonic()
        outputs = self._models.yolo_session.run(None, {"images": input_tensor})
        elapsed_ms = round((time.monotonic() - t0) * 1000)

        detections = self._postprocess_yolo(outputs[0], orig_w, orig_h)
        return ObjectInferResponse(
            detections=detections,
            inference_ms=elapsed_ms,
            model="yolov8n",
        )

    # ── F-22 Background Removal ───────────────────────────────────────────────

    def infer_segment(self, img_bgr: np.ndarray) -> bytes:
        """Runs u2netp ONNX salient-object segmentation; returns alpha mask PNG bytes."""
        if self._models.u2netp_session is None:
            raise InferenceException("u2netp model not loaded — check server startup logs")

        orig_h, orig_w = img_bgr.shape[:2]
        input_tensor = self._preprocess_u2netp(img_bgr)

        t0 = time.monotonic()
        outputs = self._models.u2netp_session.run(None, {"input.1": input_tensor})
        elapsed_ms = round((time.monotonic() - t0) * 1000)

        log.info("segment.inference_done", elapsed_ms=elapsed_ms)
        return self._postprocess_u2netp(outputs[0], orig_w, orig_h)

    # ── Private: ArtGridFaceDomainFusion helpers ──────────────────────────────

    def _run_dlib_human(
        self, img_rgb: np.ndarray, w: int, h: int
    ) -> dict[str, Any]:
        """Runs dlib HOG + 68-pt predictor. Returns raw detections and elapsed ms."""
        if self._models.face_detector is None or self._models.landmark_predictor is None:
            return {"faces": [], "elapsed_ms": 0}

        t0 = time.monotonic()
        detections = self._models.face_detector(img_rgb, 1)
        elapsed_ms = round((time.monotonic() - t0) * 1000)

        faces: list[dict[str, Any]] = []
        for det in detections:
            shape = self._models.landmark_predictor(img_rgb, det)
            bbox = NormBbox(
                x_norm=max(0.0, det.left() / w),
                y_norm=max(0.0, det.top() / h),
                w_norm=min(1.0, det.width() / w),
                h_norm=min(1.0, det.height() / h),
            )
            landmarks_68 = [
                FaceLandmark(id=i, x_norm=shape.part(i).x / w, y_norm=shape.part(i).y / h)
                for i in range(68)
            ]
            faces.append({
                "bbox": bbox,
                "confidence": 0.9,   # dlib HOG does not emit confidence; use fixed high score
                "landmarks_68": landmarks_68,
                "domain": FaceDomain.HUMAN,
            })

        return {"faces": faces, "elapsed_ms": elapsed_ms}

    def _run_yolo_animeface(
        self, img_rgb: np.ndarray, w: int, h: int, conf_threshold: float
    ) -> dict[str, Any]:
        """Runs YOLOv8-animeface ONNX. Returns raw detections and elapsed ms."""
        if self._models.yolo_animeface_session is None:
            return {"faces": [], "elapsed_ms": 0}

        input_tensor = self._preprocess_yolo_rgb(img_rgb)
        input_name = self._models.yolo_animeface_session.get_inputs()[0].name

        t0 = time.monotonic()
        outputs = self._models.yolo_animeface_session.run(None, {input_name: input_tensor})
        elapsed_ms = round((time.monotonic() - t0) * 1000)

        faces = self._postprocess_yolo_animeface(outputs[0], conf_threshold)
        return {"faces": faces, "elapsed_ms": elapsed_ms}

    def _postprocess_yolo_animeface(
        self, output: np.ndarray, conf_threshold: float
    ) -> list[dict[str, Any]]:
        """
        YOLOv8 single-class output: [1, 5, N] where 5 = cx,cy,w,h,conf.
        Normalised to [0,1] relative to the 640px input grid.
        """
        preds = output[0].T  # [N, 5]
        faces: list[dict[str, Any]] = []

        for pred in preds:
            cx, cy, bw, bh = pred[:4]
            conf = float(pred[4])
            if conf < conf_threshold:
                continue

            x_norm = max(0.0, float((cx - bw / 2) / _YOLO_INPUT_SIZE))
            y_norm = max(0.0, float((cy - bh / 2) / _YOLO_INPUT_SIZE))
            w_norm = min(1.0, float(bw / _YOLO_INPUT_SIZE))
            h_norm = min(1.0, float(bh / _YOLO_INPUT_SIZE))

            faces.append({
                "bbox": NormBbox(x_norm=x_norm, y_norm=y_norm, w_norm=w_norm, h_norm=h_norm),
                "confidence": round(conf, 4),
                "landmarks_68": [],
                "domain": FaceDomain.ANIMATED,
            })

        return faces

    def _fuse_detections(
        self,
        human_faces: list[dict[str, Any]],
        animated_faces: list[dict[str, Any]],
        iou_threshold: float,
        img_rgb: np.ndarray,
        w: int,
        h: int,
        return_landmarks: bool,
    ) -> list[UnifiedFaceDetection]:
        """
        Cross-head NMS: if a human bbox and an animated bbox overlap above iou_threshold,
        keep the one with higher confidence and classify by that head's domain.
        Non-overlapping detections are all kept.
        """
        all_faces = human_faces + animated_faces
        if not all_faces:
            return []

        # Sort by confidence descending so we keep the strongest detections.
        all_faces = sorted(all_faces, key=lambda f: f["confidence"], reverse=True)
        kept: list[dict[str, Any]] = []

        for candidate in all_faces:
            suppressed = False
            for accepted in kept:
                if _iou(candidate["bbox"], accepted["bbox"]) > iou_threshold:
                    suppressed = True
                    break
            if not suppressed:
                kept.append(candidate)

        result: list[UnifiedFaceDetection] = []
        for face in kept:
            bbox = face["bbox"]
            head_bbox = _expand_head_bbox(bbox, _HEAD_GAMMA)

            landmarks_28: list[FaceLandmark] = []
            if (
                face["domain"] == FaceDomain.ANIMATED
                and return_landmarks
                and self._models.anime_landmarks_28_session is not None
            ):
                landmarks_28 = self._run_anime_landmarks_28(img_rgb, bbox, w, h)

            result.append(UnifiedFaceDetection(
                face_id=str(uuid.uuid4()),
                domain=face["domain"],
                confidence=face["confidence"],
                bbox=bbox,
                landmarks_68=face["landmarks_68"],
                landmarks_28=landmarks_28,
                head_bbox_expanded=head_bbox,
            ))

        return result

    def _run_anime_landmarks_28(
        self, img_rgb: np.ndarray, bbox: NormBbox, w: int, h: int
    ) -> list[FaceLandmark]:
        """
        F-34-LM: Runs the optional 28-point anime landmark model on the face crop.
        Returns 28 landmarks normalised to the full-image coordinate space.
        Falls back to empty list on any inference error.
        """
        if self._models.anime_landmarks_28_session is None:
            return []

        # Crop face region with a small padding.
        x1 = max(0, int(bbox.x_norm * w))
        y1 = max(0, int(bbox.y_norm * h))
        x2 = min(w, int((bbox.x_norm + bbox.w_norm) * w))
        y2 = min(h, int((bbox.y_norm + bbox.h_norm) * h))
        face_crop = img_rgb[y1:y2, x1:x2]

        if face_crop.size == 0:
            return []

        try:
            crop_h, crop_w = face_crop.shape[:2]
            resized = cv2.resize(face_crop, (128, 128))
            tensor = (resized.astype(np.float32) / 255.0).transpose(2, 0, 1)
            tensor = np.expand_dims(tensor, axis=0)

            input_name = self._models.anime_landmarks_28_session.get_inputs()[0].name
            output = self._models.anime_landmarks_28_session.run(None, {input_name: tensor})[0]
            # Expected output: [1, 56] = 28 pairs of (x,y) in [0,1] relative to crop.
            pts = output[0].reshape(28, 2)
            return [
                FaceLandmark(
                    id=i,
                    x_norm=(x1 + float(pts[i][0]) * crop_w) / w,
                    y_norm=(y1 + float(pts[i][1]) * crop_h) / h,
                )
                for i in range(28)
            ]
        except Exception as exc:
            log.warning("anime_landmarks_28.inference_failed", error=str(exc))
            return []

    # ── Private YOLO helpers ──────────────────────────────────────────────────

    @staticmethod
    def _preprocess_yolo(img_bgr: np.ndarray) -> np.ndarray:
        img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
        return InferService._preprocess_yolo_rgb(img_rgb)

    @staticmethod
    def _preprocess_yolo_rgb(img_rgb: np.ndarray) -> np.ndarray:
        resized = cv2.resize(img_rgb, (_YOLO_INPUT_SIZE, _YOLO_INPUT_SIZE))
        tensor = resized.astype(np.float32) / 255.0
        return np.expand_dims(tensor.transpose(2, 0, 1), axis=0)  # NCHW

    @staticmethod
    def _postprocess_yolo(
        output: np.ndarray, orig_w: int, orig_h: int
    ) -> list[ObjectDetection]:
        """
        YOLOv8n output shape: [1, 84, 8400]
        84 = 4 bbox + 80 class scores. Each column is one anchor.
        """
        preds = output[0].T  # [8400, 84]
        detections: list[ObjectDetection] = []

        for pred in preds:
            scores = pred[4:]
            class_id = int(np.argmax(scores))
            confidence = float(scores[class_id])
            if confidence < _YOLO_CONF_THRESHOLD:
                continue

            cx, cy, bw, bh = pred[:4]
            x_norm = max(0.0, float((cx - bw / 2) / _YOLO_INPUT_SIZE))
            y_norm = max(0.0, float((cy - bh / 2) / _YOLO_INPUT_SIZE))
            w_norm = min(1.0, float(bw / _YOLO_INPUT_SIZE))
            h_norm = min(1.0, float(bh / _YOLO_INPUT_SIZE))

            label = _COCO_LABELS[class_id] if class_id < len(_COCO_LABELS) else str(class_id)
            detections.append(ObjectDetection(
                label=label,
                confidence=round(confidence, 4),
                bbox=NormBbox(x_norm=x_norm, y_norm=y_norm, w_norm=w_norm, h_norm=h_norm),
            ))
        return detections

    # ── Private u2netp helpers ────────────────────────────────────────────────

    @staticmethod
    def _preprocess_u2netp(img_bgr: np.ndarray) -> np.ndarray:
        img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB)
        resized = cv2.resize(img_rgb, (_U2NETP_INPUT_SIZE, _U2NETP_INPUT_SIZE))
        tensor = resized.astype(np.float32) / 255.0
        mean = np.array([0.485, 0.456, 0.406])
        std  = np.array([0.229, 0.224, 0.225])
        tensor = (tensor - mean) / std
        return np.expand_dims(tensor.transpose(2, 0, 1), axis=0).astype(np.float32)

    @staticmethod
    def _postprocess_u2netp(output: np.ndarray, orig_w: int, orig_h: int) -> bytes:
        """u2netp output: [1, 1, 320, 320] salience map in [0,1]."""
        mask = output[0, 0]
        mask = (mask - mask.min()) / (mask.max() - mask.min() + 1e-8)
        mask_uint8 = (mask * 255).astype(np.uint8)
        mask_resized = cv2.resize(mask_uint8, (orig_w, orig_h))
        pil_mask = Image.fromarray(mask_resized, mode="L")
        buf = io.BytesIO()
        pil_mask.save(buf, format="PNG")
        return buf.getvalue()


# ── Module-level pure utilities ───────────────────────────────────────────────

def _iou(a: NormBbox, b: NormBbox) -> float:
    """Intersection-over-Union for two normalised bboxes."""
    ax1, ay1 = a.x_norm, a.y_norm
    ax2, ay2 = a.x_norm + a.w_norm, a.y_norm + a.h_norm
    bx1, by1 = b.x_norm, b.y_norm
    bx2, by2 = b.x_norm + b.w_norm, b.y_norm + b.h_norm

    inter_x = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    inter_y = max(0.0, min(ay2, by2) - max(ay1, by1))
    inter = inter_x * inter_y

    area_a = a.w_norm * a.h_norm
    area_b = b.w_norm * b.h_norm
    union = area_a + area_b - inter

    return inter / union if union > 0 else 0.0


def _expand_head_bbox(bbox: NormBbox, gamma: float) -> HeadBboxExpanded:
    """Expands the face bbox by gamma to approximate the full head region."""
    cx = bbox.x_norm + bbox.w_norm / 2
    cy = bbox.y_norm + bbox.h_norm / 2
    new_w = min(1.0, bbox.w_norm * gamma)
    new_h = min(1.0, bbox.h_norm * gamma)
    return HeadBboxExpanded(
        x_norm=max(0.0, cx - new_w / 2),
        y_norm=max(0.0, cy - new_h / 2),
        w_norm=new_w,
        h_norm=new_h,
        gamma=gamma,
    )
