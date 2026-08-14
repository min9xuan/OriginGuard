from fastapi.testclient import TestClient

from originguard_model_api.main import (
    MODEL_CODE,
    MODEL_DIMENSIONS,
    app,
    get_embedding_service,
)


class FakeEmbeddingService:
    loaded = True
    device_name = "cpu"

    def encode(self, texts: list[str]) -> list[list[float]]:
        return [[1.0] + [0.0] * (MODEL_DIMENSIONS - 1) for _ in texts]


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
