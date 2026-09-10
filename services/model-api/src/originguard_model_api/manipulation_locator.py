from __future__ import annotations

import base64
import hashlib
import importlib
import io
import os
import sys
import time
from collections import deque
from pathlib import Path
from threading import Lock
from typing import Protocol

import numpy as np
import torch
from numpy.typing import NDArray
from PIL import Image
from pydantic import BaseModel, Field


class ManipulationRegion(BaseModel):
    x: int
    y: int
    width: int
    height: int
    areaRatio: float = Field(ge=0, le=1)
    meanProbability: float = Field(ge=0, le=1)
    maximumProbability: float = Field(ge=0, le=1)


class ManipulationLocalization(BaseModel):
    provider: str
    model: str
    modelVersion: str
    checkpointSha256: str
    device: str
    status: str
    classification: str
    tamperedProbability: float = Field(ge=0, le=1)
    threshold: float = Field(ge=0, le=1)
    calibrated: bool
    tamperedAreaRatio: float = Field(ge=0, le=1)
    width: int
    height: int
    processingMilliseconds: int
    maskMethod: str
    maskPngBase64: str
    heatmapPngBase64: str
    overlayPngBase64: str
    regions: list[ManipulationRegion]
    limitations: list[str]


class ManipulationLocator(Protocol):
    @property
    def configured(self) -> bool: ...

    @property
    def loaded(self) -> bool: ...

    @property
    def device_name(self) -> str: ...

    def locate(self, content: bytes) -> ManipulationLocalization: ...


class LocalMesorchLocator:
    """Inference-only adapter around the official Mesorch source and checkpoint."""

    def __init__(self, repository_root: Path, runtime_root: Path) -> None:
        source = Path(
            os.getenv("MESORCH_SOURCE_PATH", str(runtime_root / "vendor-src" / "Mesorch"))
        )
        checkpoint = Path(
            os.getenv(
                "MESORCH_CHECKPOINT_PATH",
                str(runtime_root / "models" / "mesorch" / "mesorch-98.pth"),
            )
        )
        self._source = source if source.is_absolute() else repository_root / source
        self._checkpoint = checkpoint if checkpoint.is_absolute() else repository_root / checkpoint
        self._requested_device = os.getenv("MESORCH_DEVICE", "auto").lower()
        self._threshold = float(os.getenv("MESORCH_MASK_THRESHOLD", "0.5"))
        self._minimum_area_ratio = float(os.getenv("MESORCH_MIN_AREA_RATIO", "0.002"))
        if not 0 <= self._threshold <= 1:
            raise ValueError("MESORCH_MASK_THRESHOLD must be between 0 and 1")
        if not 0 <= self._minimum_area_ratio <= 1:
            raise ValueError("MESORCH_MIN_AREA_RATIO must be between 0 and 1")
        self._model: torch.nn.Module | None = None
        self._checkpoint_sha256: str | None = None
        self._device = torch.device("cpu")
        self._load_lock = Lock()
        self._inference_lock = Lock()

    @property
    def configured(self) -> bool:
        return (self._source / "mesorch.py").is_file() and self._checkpoint.is_file()

    @property
    def loaded(self) -> bool:
        return self._model is not None

    @property
    def device_name(self) -> str:
        return str(self._device)

    def locate(self, content: bytes) -> ManipulationLocalization:
        started = time.perf_counter()
        source = self._decode(content)
        original_width, original_height = source.size
        self._load()
        assert self._model is not None
        tensor = self._prepare(source).to(self._device)
        empty_mask = torch.zeros((1, 1, 512, 512), device=self._device)
        with self._inference_lock, torch.inference_mode():
            result = self._model(tensor, empty_mask)
            predicted = result["pred_mask"]
        probability_map = predicted[0, 0].detach().float().cpu().clamp(0, 1).numpy()
        probability_image = Image.fromarray(
            np.round(probability_map * 255).astype(np.uint8), mode="L"
        ).resize((original_width, original_height), Image.Resampling.BILINEAR)
        probabilities = np.asarray(probability_image, dtype=np.float32) / 255.0
        binary = probabilities >= self._threshold
        area_ratio = float(binary.mean())
        top_count = max(1, int(probabilities.size * 0.01))
        top_probability = float(np.partition(probabilities.ravel(), -top_count)[-top_count:].mean())
        suspicious = top_probability >= self._threshold and area_ratio >= self._minimum_area_ratio
        mask, heatmap, overlay = self._visualizations(source, probabilities, binary)
        return ManipulationLocalization(
            provider="MESORCH",
            model="Mesorch image manipulation localization",
            modelVersion="AAAI-2025-official",
            checkpointSha256=self._checkpoint_sha256 or self._sha256(self._checkpoint),
            device=self.device_name,
            status="SUCCEEDED",
            classification="SUSPICIOUS_MANIPULATION" if suspicious else "NO_MANIPULATION_DETECTED",
            tamperedProbability=round(top_probability, 6),
            threshold=self._threshold,
            calibrated=False,
            tamperedAreaRatio=round(area_ratio, 6),
            width=original_width,
            height=original_height,
            processingMilliseconds=round((time.perf_counter() - started) * 1000),
            maskMethod="MESORCH_PIXEL_PROBABILITY_THRESHOLD",
            maskPngBase64=self._encode_png(mask),
            heatmapPngBase64=self._encode_png(heatmap),
            overlayPngBase64=self._encode_png(overlay),
            regions=self._regions(probabilities, binary),
            limitations=[
                "当前阈值尚未使用 OriginGuard 业务验证集校准，概率不能解释为司法意义上的确定性结论。",
                "模型定位局部内容变化，不能替代全图 AIGC 鉴别，也不能说明具体编辑工具或责任主体。",
                "压缩、缩放、截图和自然纹理可能造成误报，必须结合原图、C2PA 与人工核验。",
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
                    "Mesorch source or checkpoint is missing. Run scripts/setup-mesorch.ps1 first."
                )
            self._device = self._resolve_device()
            source_text = str(self._source.resolve())
            if source_text not in sys.path:
                sys.path.insert(0, source_text)
            module = importlib.import_module("mesorch")
            model_class = getattr(module, "MesorchFull", None)
            if model_class is None:
                raise RuntimeError("The pinned Mesorch source does not expose MesorchFull")
            model = model_class(seg_pretrain_path=None, conv_pretrain=False, image_size=512)
            checkpoint = torch.load(self._checkpoint, map_location="cpu", weights_only=False)
            state = (
                checkpoint.get("model", checkpoint) if isinstance(checkpoint, dict) else checkpoint
            )
            if not isinstance(state, dict):
                raise TypeError("Mesorch checkpoint does not contain a model state dictionary")
            normalized = {str(key).removeprefix("module."): value for key, value in state.items()}
            incompatible = model.load_state_dict(normalized, strict=False)
            if len(incompatible.missing_keys) > 16:
                raise RuntimeError("Mesorch checkpoint is incompatible with the pinned source")
            self._checkpoint_sha256 = self._sha256(self._checkpoint)
            self._model = model.to(self._device).eval()

    def _resolve_device(self) -> torch.device:
        if self._requested_device == "auto":
            return torch.device("cuda" if torch.cuda.is_available() else "cpu")
        if self._requested_device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("MESORCH_DEVICE=cuda but CUDA is unavailable")
        if self._requested_device not in {"cpu", "cuda"}:
            raise ValueError("MESORCH_DEVICE must be auto, cpu, or cuda")
        return torch.device(self._requested_device)

    def _decode(self, content: bytes) -> Image.Image:
        try:
            image = Image.open(io.BytesIO(content))
            image.load()
            return image.convert("RGB")
        except Exception as exception:
            raise ValueError("Unable to decode image for manipulation localization") from exception

    def _prepare(self, image: Image.Image) -> torch.Tensor:
        resized = image.resize((512, 512), Image.Resampling.BILINEAR)
        values = np.asarray(resized, dtype=np.float32) / 255.0
        values = (values - np.array([0.485, 0.456, 0.406], dtype=np.float32)) / np.array(
            [0.229, 0.224, 0.225], dtype=np.float32
        )
        return torch.from_numpy(values.transpose(2, 0, 1)).unsqueeze(0)

    def _visualizations(
        self,
        source: Image.Image,
        probabilities: NDArray[np.float32],
        binary: NDArray[np.bool_],
    ) -> tuple[Image.Image, Image.Image, Image.Image]:
        strength = np.clip(probabilities, 0, 1)
        red = np.clip(1.7 * strength, 0, 1)
        green = np.clip(1.7 * (1 - np.abs(strength - 0.5) * 2), 0, 1)
        blue = np.clip(1.5 * (1 - strength), 0, 1)
        heatmap = Image.fromarray(
            np.round(np.stack([red, green, blue], axis=-1) * 255).astype(np.uint8), mode="RGB"
        )
        mask = Image.fromarray((binary.astype(np.uint8) * 255), mode="L")
        alpha = Image.fromarray(np.round(strength * 150).astype(np.uint8), mode="L")
        overlay = Image.composite(heatmap, source, alpha)
        return mask, heatmap, overlay

    def _regions(
        self, probabilities: NDArray[np.float32], binary: NDArray[np.bool_]
    ) -> list[ManipulationRegion]:
        height, width = binary.shape
        visited = np.zeros_like(binary, dtype=bool)
        minimum_pixels = max(16, round(binary.size * self._minimum_area_ratio))
        regions: list[ManipulationRegion] = []
        for start_y, start_x in zip(*np.where(binary & ~visited), strict=False):
            if visited[start_y, start_x]:
                continue
            queue: deque[tuple[int, int]] = deque([(int(start_y), int(start_x))])
            visited[start_y, start_x] = True
            points: list[tuple[int, int]] = []
            while queue:
                y, x = queue.popleft()
                points.append((y, x))
                for next_y, next_x in ((y - 1, x), (y + 1, x), (y, x - 1), (y, x + 1)):
                    if (
                        0 <= next_y < height
                        and 0 <= next_x < width
                        and binary[next_y, next_x]
                        and not visited[next_y, next_x]
                    ):
                        visited[next_y, next_x] = True
                        queue.append((next_y, next_x))
            if len(points) < minimum_pixels:
                continue
            ys = np.array([point[0] for point in points])
            xs = np.array([point[1] for point in points])
            scores = probabilities[ys, xs]
            regions.append(
                ManipulationRegion(
                    x=int(xs.min()),
                    y=int(ys.min()),
                    width=int(xs.max() - xs.min() + 1),
                    height=int(ys.max() - ys.min() + 1),
                    areaRatio=round(len(points) / binary.size, 6),
                    meanProbability=round(float(scores.mean()), 6),
                    maximumProbability=round(float(scores.max()), 6),
                )
            )
        return sorted(regions, key=lambda region: region.areaRatio, reverse=True)[:10]

    def _encode_png(self, image: Image.Image) -> str:
        output = io.BytesIO()
        image.save(output, format="PNG")
        return base64.b64encode(output.getvalue()).decode("ascii")

    def _sha256(self, path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
