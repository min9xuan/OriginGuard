import io
from pathlib import Path

from fastapi.testclient import TestClient
from PIL import Image

from originguard_model_api.aide import (
    AideDetection,
    ImageQualityAssessment,
    LocalAideDetector,
)
from originguard_model_api.clip_detector import ClipDetection
from originguard_model_api.main import (
    MODEL_CODE,
    MODEL_DIMENSIONS,
    app,
    get_aide_detector,
    get_clip_detector,
    get_embedding_service,
)


class FakeEmbeddingService:
    loaded = True
    device_name = "cpu"

    def encode(self, texts: list[str]) -> list[list[float]]:
        return [[1.0] + [0.0] * (MODEL_DIMENSIONS - 1) for _ in texts]


class FakeAideDetector:
    loaded = True
    configured = True
    device_name = "cpu"

    def detect(self, content: bytes) -> AideDetection:
        return AideDetection(
            provider="AIDE_ICLR_2025_OFFICIAL",
            model="AIDE GenImage train",
            modelVersion="test",
            checkpointSha256="a" * 64,
            device="cpu",
            precision="float32",
            syntheticProbability=0.91,
            authenticProbability=0.09,
            classification="LIKELY_SYNTHETIC",
            syntheticThreshold=0.5,
            authenticThreshold=0.5,
            width=32,
            height=32,
            processingMilliseconds=12,
            qualityAssessment=ImageQualityAssessment(
                status="PASS",
                modelEligible=True,
                qualityScore=100,
                width=32,
                height=32,
                minDimension=32,
                aspectRatio=1.0,
                sharpnessVariance=100.0,
                grayscaleEntropy=7.0,
                issues=[],
            ),
            attentionMethod="Grad-CAM test",
            attentionTarget="LIKELY_SYNTHETIC",
            attentionOverlayPngBase64="aGVhdG1hcA==",
            limitations=["测试限制"],
        )


class FakeClipDetector:
    loaded = True
    configured = True
    device_name = "cpu"

    def detect(self, content: bytes) -> ClipDetection:
        return ClipDetection(
            provider="OPENAI_CLIP",
            role="MEDIA_TYPE_CONTEXT",
            status="AVAILABLE",
            model="ViT-B/32",
            modelVersion="test",
            promptVersion="3.0.0",
            device="cpu",
            mediaType="ILLUSTRATION_CARTOON",
            mediaTypeLabel="插画或卡通",
            mediaTypeScore=0.82,
            mediaTypeMargin=0.57,
            mediaTypeScores={
                "PHOTOGRAPH": 0.05,
                "ILLUSTRATION_CARTOON": 0.82,
                "THREE_D_RENDER": 0.08,
                "DOCUMENT_SCREENSHOT": 0.03,
                "DIAGRAM_GRAPHIC": 0.02,
            },
            promptLanguage="en",
            processingMilliseconds=8,
            limitations=["测试限制"],
        )


def test_health() -> None:
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_embedding_contract_without_loading_model() -> None:
    app.dependency_overrides[get_embedding_service] = lambda: FakeEmbeddingService()
    try:
        response = TestClient(app).post("/v1/embeddings", json={"inputs": ["AIGC 取证"]})
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 200
    assert response.json()["provider"] == MODEL_CODE
    assert response.json()["dimensions"] == MODEL_DIMENSIONS
    assert len(response.json()["embeddings"][0]) == MODEL_DIMENSIONS


def test_aide_detection_contract_without_loading_model() -> None:
    app.dependency_overrides[get_aide_detector] = lambda: FakeAideDetector()
    try:
        response = TestClient(app).post(
            "/v1/aigc/detect", content=b"fake-image", headers={"Content-Type": "image/png"}
        )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 200
    assert response.json()["classification"] == "LIKELY_SYNTHETIC"
    assert response.json()["syntheticProbability"] == 0.91
    assert response.json()["attentionOverlayPngBase64"] == "aGVhdG1hcA=="


def test_quality_gate_rejects_tiny_image_without_loading_aide() -> None:
    output = io.BytesIO()
    Image.new("RGB", (64, 64), "white").save(output, format="PNG")
    detector = LocalAideDetector(Path.cwd(), Path.cwd() / ".runtime-does-not-exist")

    result = detector.detect(output.getvalue())

    assert result.classification == "UNSUPPORTED_INPUT"
    assert result.syntheticProbability is None
    assert result.qualityAssessment.status == "REJECT"
    assert detector.loaded is False


def test_aide_uses_official_half_score_as_preliminary_boundary() -> None:
    detector = LocalAideDetector(Path.cwd(), Path.cwd() / ".runtime-does-not-exist")

    assert detector._classify(0.499999) == "LIKELY_AUTHENTIC"
    assert detector._classify(0.5) == "LIKELY_SYNTHETIC"


def test_clip_media_type_contract_without_loading_model() -> None:
    app.dependency_overrides[get_clip_detector] = lambda: FakeClipDetector()
    try:
        response = TestClient(app).post(
            "/v1/media/classify", content=b"fake-image", headers={"Content-Type": "image/png"}
        )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 200
    assert response.json()["role"] == "MEDIA_TYPE_CONTEXT"
    assert "classification" not in response.json()
    assert "semanticSyntheticScore" not in response.json()
    assert response.json()["mediaType"] == "ILLUSTRATION_CARTOON"
    assert response.json()["mediaTypeLabel"] == "插画或卡通"
