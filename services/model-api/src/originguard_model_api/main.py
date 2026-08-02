from datetime import UTC, datetime

from fastapi import FastAPI

app = FastAPI(title="OriginGuard Model API", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    return {
        "status": "UP",
        "service": "originguard-model-api",
        "timestamp": datetime.now(UTC).isoformat(),
    }


@app.get("/v1/models")
def list_models() -> dict[str, list[object]]:
    """No real model is registered during M0."""
    return {"items": []}

