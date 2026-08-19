from __future__ import annotations

import io
import os
import time
from pathlib import Path
from threading import Lock
from typing import Protocol

import clip
import torch
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel

CLIP_PROVIDER = "OPENAI_CLIP"
CLIP_MODEL = "ViT-B/32"
CLIP_VERSION = "openai-2021"
CLIP_PROMPT_VERSION = "3.0.0"
CLIP_MAX_BYTES = 25 * 1024 * 1024
CLIP_MEDIA_TYPE_THRESHOLD = 0.45
CLIP_MEDIA_TYPE_MARGIN = 0.08

MEDIA_TYPE_LABELS = {
    "PHOTOGRAPH": "摄影图像",
    "ILLUSTRATION_CARTOON": "插画或卡通",
    "THREE_D_RENDER": "3D 渲染或游戏画面",
    "DOCUMENT_SCREENSHOT": "文档、网页或界面截图",
    "DIAGRAM_GRAPHIC": "图表、海报或平面设计",
    "UNKNOWN": "类型不明确",
}

MEDIA_TYPE_PROMPTS = {
    "PHOTOGRAPH": (
        "a photograph captured by a camera",
        "a realistic camera photo of a real scene",
        "a natural photographic image",
        "documentary photography from the real world",
    ),
    "ILLUSTRATION_CARTOON": (
        "a cartoon character, mascot, or illustrated portrait",
        "a two-dimensional digital illustration or vector cartoon",
        "an anime, comic, or hand-drawn character picture",
        "colorful stylized artwork depicting an illustrated subject",
    ),
    "THREE_D_RENDER": (
        "a three-dimensional computer graphics render",
        "a CGI scene rendered with 3D software",
        "a video game scene or 3D animated frame",
        "a digitally rendered three-dimensional image",
    ),
    "DOCUMENT_SCREENSHOT": (
        "a screenshot of a website or software interface",
        "a document page containing text and interface elements",
        "a mobile phone or computer screen capture",
        "a screenshot of a message, application, or web page",
    ),
    "DIAGRAM_GRAPHIC": (
        "an informational diagram with labels, arrows, and explanatory text",
        "a data infographic containing charts, numbers, and typography",
        "a presentation slide, statistical chart, or text-heavy poster",
        "a graphic layout communicating information through text and symbols",
    ),
}

class ClipDetection(BaseModel):
    provider: str
    role: str
    status: str
    model: str
    modelVersion: str
    promptVersion: str
    device: str
    mediaType: str
    mediaTypeLabel: str
    mediaTypeScore: float
    mediaTypeMargin: float
    mediaTypeScores: dict[str, float]
    promptLanguage: str
    processingMilliseconds: int
    limitations: list[str]


class ClipDetector(Protocol):
    @property
    def loaded(self) -> bool: ...

    @property
    def configured(self) -> bool: ...

    @property
    def device_name(self) -> str: ...

    def detect(self, content: bytes) -> ClipDetection: ...


class LocalClipDetector:
    """Offline OpenAI CLIP adapter used only for pre-planning media typing."""

    def __init__(self, repository_root: Path, runtime_root: Path) -> None:
        configured = os.getenv(
            "CLIP_MODEL_PATH", str(runtime_root / "models" / "clip" / "ViT-B-32.pt")
        )
        path = Path(configured)
        self._model_path = (path if path.is_absolute() else repository_root / path).resolve()
        self._requested_device = os.getenv("CLIP_DEVICE", "auto").lower()
        self._device = torch.device("cpu")
        self._model: torch.nn.Module | None = None
        self._preprocess = None
        self._media_type_features: torch.Tensor | None = None
        self._load_lock = Lock()
        self._inference_lock = Lock()

    @property
    def loaded(self) -> bool:
        return self._model is not None

    @property
    def configured(self) -> bool:
        return self._model_path.is_file()

    @property
    def device_name(self) -> str:
        return str(self._device)

    def detect(self, content: bytes) -> ClipDetection:
        if not content:
            raise ValueError("CLIP image content cannot be empty")
        if len(content) > CLIP_MAX_BYTES:
            raise ValueError(f"Image exceeds the {CLIP_MAX_BYTES} byte CLIP limit")
        image = self._open_image(content)
        self._load()
        assert self._model is not None
        assert self._preprocess is not None
        assert self._media_type_features is not None
        started = time.perf_counter()
        with self._inference_lock, torch.inference_mode():
            image_input = self._preprocess(image).unsqueeze(0).to(self._device)
            image_features = self._model.encode_image(image_input).float()
            image_features = image_features / image_features.norm(dim=-1, keepdim=True)
            media_type, media_score, media_margin, type_scores = self._classify_media_type(
                image_features
            )
        limitations = [
            "媒体类型分数是英文提示词之间的相对语义匹配度，不是经过校准的概率",
            "CLIP 只识别内容类型，不判断图像是否由 AI 生成",
            "结果只用于 Agent 规划、模型路由和适用性解释",
            "提示词选择、画面风格和训练数据偏差都可能影响类型识别",
        ]
        return ClipDetection(
            provider=CLIP_PROVIDER,
            role="MEDIA_TYPE_CONTEXT",
            status="AVAILABLE",
            model=CLIP_MODEL,
            modelVersion=CLIP_VERSION,
            promptVersion=CLIP_PROMPT_VERSION,
            device=self.device_name,
            mediaType=media_type,
            mediaTypeLabel=MEDIA_TYPE_LABELS[media_type],
            mediaTypeScore=round(media_score, 6),
            mediaTypeMargin=round(media_margin, 6),
            mediaTypeScores={key: round(value, 6) for key, value in type_scores.items()},
            promptLanguage="en",
            processingMilliseconds=round((time.perf_counter() - started) * 1000),
            limitations=limitations,
        )

    def _load(self) -> None:
        if self.loaded:
            return
        with self._load_lock:
            if self.loaded:
                return
            if not self.configured:
                raise FileNotFoundError(
                    f"CLIP model is missing at {self._model_path}. Run scripts/setup-clip.ps1 first."
                )
            self._device = self._resolve_device()
            model, preprocess = clip.load(str(self._model_path), device=self._device, jit=False)
            model.eval()
            with torch.inference_mode():
                self._media_type_features = self._encode_prompt_groups(
                    model, list(MEDIA_TYPE_PROMPTS.values())
                )
            self._model = model
            self._preprocess = preprocess

    def _encode_prompt_groups(
        self, model: torch.nn.Module, groups: list[tuple[str, ...]]
    ) -> torch.Tensor:
        prototypes: list[torch.Tensor] = []
        for prompts in groups:
            features = model.encode_text(clip.tokenize(list(prompts)).to(self._device)).float()
            features = features / features.norm(dim=-1, keepdim=True)
            prototype = features.mean(dim=0)
            prototypes.append(prototype / prototype.norm())
        return torch.stack(prototypes)

    def _scores(self, image_features: torch.Tensor, class_features: torch.Tensor) -> torch.Tensor:
        assert self._model is not None
        logits = self._model.logit_scale.exp().float() * image_features @ class_features.T
        return logits.softmax(dim=-1)[0].cpu()

    def _classify_media_type(
        self, image_features: torch.Tensor
    ) -> tuple[str, float, float, dict[str, float]]:
        assert self._media_type_features is not None
        scores = self._scores(image_features, self._media_type_features)
        ordered_types = list(MEDIA_TYPE_PROMPTS)
        ranked = torch.argsort(scores, descending=True)
        top_index = int(ranked[0].item())
        second_index = int(ranked[1].item())
        top_score = float(scores[top_index].item())
        margin = top_score - float(scores[second_index].item())
        media_type = (
            ordered_types[top_index]
            if top_score >= CLIP_MEDIA_TYPE_THRESHOLD and margin >= CLIP_MEDIA_TYPE_MARGIN
            else "UNKNOWN"
        )
        type_scores = {
            media_type_code: float(scores[index].item())
            for index, media_type_code in enumerate(ordered_types)
        }
        return media_type, top_score, margin, type_scores

    def _resolve_device(self) -> torch.device:
        if self._requested_device == "auto":
            return torch.device("cuda" if torch.cuda.is_available() else "cpu")
        if self._requested_device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("CLIP_DEVICE=cuda but CUDA is unavailable")
        if self._requested_device not in {"cpu", "cuda"}:
            raise ValueError("CLIP_DEVICE must be auto, cpu, or cuda")
        return torch.device(self._requested_device)

    def _open_image(self, content: bytes) -> Image.Image:
        try:
            with Image.open(io.BytesIO(content)) as source:
                source.verify()
            with Image.open(io.BytesIO(content)) as source:
                return source.convert("RGB")
        except (UnidentifiedImageError, OSError) as exception:
            raise ValueError("CLIP input is not a supported image") from exception
