import io
from pathlib import Path

from fastapi.testclient import TestClient
from PIL import Image

from originguard_model_api.aide import (
    AideDetection,
    ImageQualityAssessment,
    LocalAideDetector,
)
from originguard_model_api.anime_detector import AnimeDetection, LocalAnimeDetector
from originguard_model_api.clip_detector import ClipDetection
from originguard_model_api.diffusion_verifier import DiffusionVerification
from originguard_model_api.main import (
    MODEL_CODE,
    MODEL_DIMENSIONS,
    app,
    get_aide_detector,
    get_anime_detector,
    get_clip_detector,
    get_diffusion_verifier,
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
            provider="GENERIC_AIGC_DETECTOR",
            model="Multi-feature generative content detector",
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
            mediaType="ANIME_MANGA",
            mediaTypeLabel="动漫或漫画",
            mediaTypeScore=0.82,
            mediaTypeMargin=0.57,
            mediaTypeScores={
                "PHOTOGRAPH": 0.05,
                "ANIME_MANGA": 0.82,
                "DIGITAL_ILLUSTRATION": 0.01,
                "VECTOR_CARTOON": 0.01,
                "THREE_D_RENDER": 0.08,
                "DOCUMENT_SCREENSHOT": 0.03,
                "DIAGRAM_GRAPHIC": 0.02,
            },
            promptLanguage="en",
            processingMilliseconds=8,
            limitations=["测试限制"],
        )


class FakeAnimeDetector:
    loaded = True
    configured = True
    device_name = "cuda"
    synthetic_threshold = 0.65
    authentic_threshold = 0.35
    checkpoint_integrity_verified = True

    def detect(self, content: bytes) -> AnimeDetection:
        return AnimeDetection(
            provider="ANIME_AIGC_DETECTOR",
            model="AniXplore anime generation and manipulation detector",
            modelVersion="test",
            checkpointSha256="b" * 64,
            checkpointIntegrityVerified=True,
            device="cuda",
            syntheticProbability=0.87,
            authenticProbability=0.13,
            classification="LIKELY_SYNTHETIC",
            syntheticThreshold=0.65,
            authenticThreshold=0.35,
            width=512,
            height=512,
            processingMilliseconds=20,
            localizationMethod="PIXEL_LEVEL_GENERATION_MASK",
            localizationOverlayPngBase64="bWFzaw==",
            limitations=["测试限制"],
        )


class FakeDiffusionVerifier:
    loaded = True
    configured = True

    def verify(self, content: bytes, suffix: str) -> DiffusionVerification:
        return DiffusionVerification(
            provider="DIFFUSION_RECONSTRUCTION_VERIFIER",
            model="Training-free diffusion reconstruction verifier",
            modelVersion="test",
            status="SUCCEEDED",
            reconstructionDistance=-0.42,
            aerobladeScore=0.42,
            distanceMetric="lpips_vgg_2",
            autoencoder="test/autoencoder",
            calibrated=False,
            classification="INCONCLUSIVE",
            device="cpu",
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
    assert response.json()["mediaType"] == "ANIME_MANGA"
    assert response.json()["mediaTypeLabel"] == "动漫或漫画"


def test_anime_detection_contract_without_loading_model() -> None:
    app.dependency_overrides[get_anime_detector] = lambda: FakeAnimeDetector()
    try:
        response = TestClient(app).post(
            "/v1/aigc/anime/detect", content=b"fake-image", headers={"Content-Type": "image/webp"}
        )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 200
    assert response.json()["provider"] == "ANIME_AIGC_DETECTOR"
    assert response.json()["localizationMethod"] == "PIXEL_LEVEL_GENERATION_MASK"
    assert response.json()["checkpointIntegrityVerified"] is True


def test_anime_detector_uses_conservative_uncertainty_band() -> None:
    detector = LocalAnimeDetector(Path.cwd(), Path.cwd() / ".runtime-does-not-exist")

    assert detector._classify(0.66) == "LIKELY_SYNTHETIC"
    assert detector._classify(0.34) == "LIKELY_AUTHENTIC"
    assert detector._classify(0.50) == "INCONCLUSIVE"


def test_diffusion_verification_does_not_mislabel_distance_as_probability() -> None:
    app.dependency_overrides[get_diffusion_verifier] = lambda: FakeDiffusionVerifier()
    try:
        response = TestClient(app).post(
            "/v1/aigc/diffusion/reconstruct",
            content=b"fake-image",
            headers={"Content-Type": "image/png"},
        )
    finally:
        app.dependency_overrides.clear()
    assert response.status_code == 200
    assert response.json()["reconstructionDistance"] == -0.42
    assert response.json()["calibrated"] is False
    assert "syntheticProbability" not in response.json()
