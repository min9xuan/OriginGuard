from __future__ import annotations

import base64
import hashlib
import io
import os
import sys
import time
from pathlib import Path
from threading import Lock
from typing import Any, Protocol

import numpy as np
import torch
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel
from torch.nn import functional
from torchvision import transforms

ANIME_PROVIDER = "ILLUSTRATION_AIGC_DETECTOR"
ANIME_MODEL = "Illustration and cartoon generative-content detector"
ANIME_VERSION = "anixplore-official"
ANIME_INPUT_SIZE = 512
ANIME_MAX_BYTES = 25 * 1024 * 1024


class AnimeDetection(BaseModel):
    provider: str
    model: str
    modelVersion: str
    checkpointSha256: str
    device: str
    syntheticProbability: float
    authenticProbability: float
    classification: str
    syntheticThreshold: float
    authenticThreshold: float
    width: int
    height: int
    processingMilliseconds: int
    localizationMethod: str
    localizationOverlayPngBase64: str
    limitations: list[str]


class AnimeDetector(Protocol):
    @property
    def loaded(self) -> bool: ...

    @property
    def configured(self) -> bool: ...

    @property
    def device_name(self) -> str: ...

    def detect(self, content: bytes) -> AnimeDetection: ...


class LocalAnimeDetector:
    """Lazy adapter around the authors' official anime-domain detector."""

    def __init__(self, repository_root: Path, runtime_root: Path) -> None:
        self._checkpoint_path = self._resolve_path(
            os.getenv("ANIXPLORE_CHECKPOINT_PATH", str(runtime_root / "models" / "anixplore" / "checkpoint.pth")),
            repository_root,
        )
        self._source_path = self._resolve_path(
            os.getenv(
                "ANIXPLORE_SOURCE_PATH",
                str(runtime_root / "vendor-src" / "AnimeDL2M" / "AniXplore" / "IMDLBenCo"),
            ),
            repository_root,
        )
        self._threshold = float(os.getenv("ANIXPLORE_SYNTHETIC_THRESHOLD", "0.5"))
        self._requested_device = os.getenv("ANIXPLORE_DEVICE", "auto").lower()
        self._device = torch.device("cpu")
        self._model: Any | None = None
        self._checkpoint_sha256 = ""
        self._load_lock = Lock()
        self._inference_lock = Lock()

    @property
    def loaded(self) -> bool:
        return self._model is not None

    @property
    def configured(self) -> bool:
        model_file = self._source_path / "IMDLBenCo" / "model_zoo" / "AniXplore" / "AniXplore.py"
        return self._checkpoint_path.is_file() and model_file.is_file()

    @property
    def device_name(self) -> str:
        return str(self._device)

    def detect(self, content: bytes) -> AnimeDetection:
        if not content:
            raise ValueError("Image content cannot be empty")
        if len(content) > ANIME_MAX_BYTES:
            raise ValueError(f"Image exceeds the {ANIME_MAX_BYTES} byte detector limit")
        try:
            image = Image.open(io.BytesIO(content)).convert("RGB")
        except (UnidentifiedImageError, OSError) as exception:
            raise ValueError("Image payload cannot be decoded") from exception
        width, height = image.size
        self._load()
        assert self._model is not None
        started = time.perf_counter()
        tensor = transforms.Compose(
            [
                transforms.Resize((ANIME_INPUT_SIZE, ANIME_INPUT_SIZE), antialias=True),
                transforms.ToTensor(),
                transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
            ]
        )(image).unsqueeze(0).to(self._device)
        dummy_mask = torch.zeros((1, 1, ANIME_INPUT_SIZE, ANIME_INPUT_SIZE), device=self._device)
        dummy_label = torch.zeros((1,), device=self._device)
        captured_logits: list[torch.Tensor] = []
        hook = self._model.cls_head.register_forward_hook(
            lambda _module, _inputs, output: captured_logits.append(output.detach())
        )
        with self._inference_lock, torch.inference_mode():
            try:
                output = self._model(tensor, dummy_mask, dummy_label)
            finally:
                hook.remove()
            if not captured_logits:
                raise RuntimeError("Illustration detector did not expose its classification logit")
            probability = float(torch.sigmoid(captured_logits[0]).reshape(-1)[0].item())
            mask = output["pred_mask"][0, 0].detach().float().cpu()
        probability = min(1.0, max(0.0, probability))
        overlay = self._overlay(image, mask)
        elapsed = int((time.perf_counter() - started) * 1000)
        return AnimeDetection(
            provider=ANIME_PROVIDER,
            model=ANIME_MODEL,
            modelVersion=ANIME_VERSION,
            checkpointSha256=self._checkpoint_sha256,
            device=self.device_name,
            syntheticProbability=probability,
            authenticProbability=1.0 - probability,
            classification="LIKELY_SYNTHETIC" if probability >= self._threshold else "LIKELY_AUTHENTIC",
            syntheticThreshold=self._threshold,
            authenticThreshold=self._threshold,
            width=width,
            height=height,
            processingMilliseconds=elapsed,
            localizationMethod="PIXEL_LEVEL_GENERATION_MASK",
            localizationOverlayPngBase64=base64.b64encode(overlay).decode("ascii"),
            limitations=[
                "该模型面向动漫、插画与卡通内容，不能直接外推到自然照片。",
                "当前连续分数由像素级生成区域响应汇总，阈值仍需使用业务验证集校准。",
            ],
        )

    def _load(self) -> None:
        if self.loaded:
            return
        with self._load_lock:
            if self.loaded:
                return
            if not self.configured:
                raise FileNotFoundError(
                    "Illustration detector is not configured. Run scripts/setup-anixplore.ps1 and place the official checkpoint first."
                )
            if str(self._source_path) not in sys.path:
                sys.path.insert(0, str(self._source_path))
            try:
                from IMDLBenCo.model_zoo.AniXplore.AniXplore import AniXplore
            except ImportError as exception:
                raise RuntimeError(
                    "Illustration detector dependencies are missing; install its optional environment first"
                ) from exception
            self._device = self._resolve_device()
            model = AniXplore(seg_pretrain_path=None, conv_pretrain=False, image_size=ANIME_INPUT_SIZE)
            checkpoint = torch.load(self._checkpoint_path, map_location=self._device, weights_only=False)
            state_dict = checkpoint.get("model", checkpoint) if isinstance(checkpoint, dict) else checkpoint
            model.load_state_dict(state_dict, strict=True)
            self._model = model.to(self._device).eval()
            self._checkpoint_sha256 = hashlib.sha256(self._checkpoint_path.read_bytes()).hexdigest()

    def _resolve_device(self) -> torch.device:
        if self._requested_device == "auto":
            return torch.device("cuda" if torch.cuda.is_available() else "cpu")
        if self._requested_device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("ANIXPLORE_DEVICE=cuda but CUDA is unavailable")
        if self._requested_device not in {"cpu", "cuda"}:
            raise ValueError("ANIXPLORE_DEVICE must be auto, cpu, or cuda")
        return torch.device(self._requested_device)

    def _overlay(self, image: Image.Image, mask: torch.Tensor) -> bytes:
        resized = functional.interpolate(
            mask.unsqueeze(0).unsqueeze(0), size=(image.height, image.width), mode="bilinear", align_corners=False
        )[0, 0].numpy()
        heat = np.zeros((image.height, image.width, 4), dtype=np.uint8)
        heat[..., 0] = 238
        heat[..., 1] = 82
        heat[..., 2] = 83
        heat[..., 3] = np.clip(resized * 150, 0, 150).astype(np.uint8)
        composed = Image.alpha_composite(image.convert("RGBA"), Image.fromarray(heat, "RGBA"))
        buffer = io.BytesIO()
        composed.save(buffer, format="PNG")
        return buffer.getvalue()

    def _resolve_path(self, configured: str, repository_root: Path) -> Path:
        path = Path(configured)
        return (path if path.is_absolute() else repository_root / path).resolve()
