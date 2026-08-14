from __future__ import annotations

import os
from datetime import UTC, datetime
from pathlib import Path
from threading import Lock
from typing import Annotated, Protocol

_REPOSITORY_ROOT = Path(__file__).resolve().parents[4]
_RUNTIME_ROOT = _REPOSITORY_ROOT / ".runtime"
os.environ.setdefault("HF_HOME", str(_RUNTIME_ROOT / "cache" / "huggingface"))
os.environ.setdefault("TORCH_HOME", str(_RUNTIME_ROOT / "cache" / "torch"))

import torch
from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field
from torch.nn import functional
from transformers import AutoModel, AutoTokenizer, PreTrainedModel, PreTrainedTokenizerBase

MODEL_CODE = "LOCAL_BGE_SMALL_ZH_V1_5"
MODEL_NAME = "BAAI/bge-small-zh-v1.5"
MODEL_DIMENSIONS = 512
MODEL_MAX_LENGTH = 512


class EmbeddingRequest(BaseModel):
    inputs: list[str] = Field(min_length=1, max_length=32)


class EmbeddingResponse(BaseModel):
    provider: str
    model: str
    dimensions: int
    embeddings: list[list[float]]


class EmbeddingService(Protocol):
    @property
    def loaded(self) -> bool: ...

    @property
    def device_name(self) -> str: ...

    def encode(self, texts: list[str]) -> list[list[float]]: ...


class LocalBgeEmbeddingService:
    def __init__(self) -> None:
        configured_path = os.getenv(
            "EMBEDDING_MODEL_PATH", str(_RUNTIME_ROOT / "models" / "bge-small-zh-v1.5")
        )
        model_path = Path(configured_path)
        if not model_path.is_absolute():
            model_path = _REPOSITORY_ROOT / model_path
        self._model_path = model_path.resolve()
        self._requested_device = os.getenv("EMBEDDING_DEVICE", "auto").lower()
        self._device = torch.device("cpu")
        self._tokenizer: PreTrainedTokenizerBase | None = None
        self._model: PreTrainedModel | None = None
        self._load_lock = Lock()
        self._inference_lock = Lock()

    @property
    def loaded(self) -> bool:
        return self._model is not None

    @property
    def device_name(self) -> str:
        return str(self._device)

    def encode(self, texts: list[str]) -> list[list[float]]:
        if any(not text.strip() for text in texts):
            raise ValueError("Embedding inputs cannot be blank")
        self._load()
        assert self._tokenizer is not None
        assert self._model is not None
        with self._inference_lock, torch.inference_mode():
            encoded = self._tokenizer(
                texts,
                padding=True,
                truncation=True,
                max_length=MODEL_MAX_LENGTH,
                return_tensors="pt",
            )
            encoded = {name: value.to(self._device) for name, value in encoded.items()}
            output = self._model(**encoded)
            embeddings = functional.normalize(output.last_hidden_state[:, 0], p=2, dim=1)
            return embeddings.cpu().tolist()

    def _load(self) -> None:
        if self.loaded:
            return
        with self._load_lock:
            if self.loaded:
                return
            if not self._model_path.is_dir():
                raise FileNotFoundError(
                    f"Embedding model is missing at {self._model_path}. "
                    "Run the project model download command first."
                )
            self._device = self._resolve_device()
            self._tokenizer = AutoTokenizer.from_pretrained(
                self._model_path, local_files_only=True
            )
            self._model = AutoModel.from_pretrained(
                self._model_path, local_files_only=True, use_safetensors=True
            ).to(self._device)
            self._model.eval()

    def _resolve_device(self) -> torch.device:
        if self._requested_device == "auto":
            return torch.device("cuda" if torch.cuda.is_available() else "cpu")
        if self._requested_device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("EMBEDDING_DEVICE=cuda but CUDA is unavailable")
        if self._requested_device not in {"cpu", "cuda"}:
            raise ValueError("EMBEDDING_DEVICE must be auto, cpu, or cuda")
        return torch.device(self._requested_device)


_embedding_service: EmbeddingService = LocalBgeEmbeddingService()


def get_embedding_service() -> EmbeddingService:
    return _embedding_service


EmbeddingServiceDependency = Annotated[EmbeddingService, Depends(get_embedding_service)]


app = FastAPI(title="OriginGuard Model API", version="0.2.0")


@app.get("/health")
def health(service: EmbeddingServiceDependency) -> dict[str, object]:
    return {
        "status": "UP",
        "service": "originguard-model-api",
        "timestamp": datetime.now(UTC).isoformat(),
        "embeddingModelLoaded": service.loaded,
    }


@app.get("/v1/models")
def list_models(service: EmbeddingServiceDependency) -> dict[str, list[object]]:
    return {
        "items": [
            {
                "code": MODEL_CODE,
                "name": MODEL_NAME,
                "type": "TEXT_EMBEDDING",
                "dimensions": MODEL_DIMENSIONS,
                "loaded": service.loaded,
                "device": service.device_name,
            }
        ]
    }


@app.post("/v1/embeddings", response_model=EmbeddingResponse)
def create_embeddings(
    request: EmbeddingRequest,
    service: EmbeddingServiceDependency,
) -> EmbeddingResponse:
    try:
        embeddings = service.encode(request.inputs)
    except (FileNotFoundError, RuntimeError) as exception:
        raise HTTPException(status_code=503, detail=str(exception)) from exception
    except ValueError as exception:
        raise HTTPException(status_code=422, detail=str(exception)) from exception
    if any(len(embedding) != MODEL_DIMENSIONS for embedding in embeddings):
        raise HTTPException(status_code=500, detail="Embedding model returned an unexpected dimension")
    return EmbeddingResponse(
        provider=MODEL_CODE,
        model=MODEL_NAME,
        dimensions=MODEL_DIMENSIONS,
        embeddings=embeddings,
    )
