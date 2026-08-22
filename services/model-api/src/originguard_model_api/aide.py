from __future__ import annotations

import base64
import hashlib
import io
import os
import sys
import time
from pathlib import Path
from threading import Lock
from typing import Any, Protocol, cast

import numpy as np
import torch
from PIL import Image, ImageFilter, UnidentifiedImageError
from pydantic import BaseModel
from torch.nn import functional
from torchvision import transforms

AIDE_PROVIDER = "AIDE_ICLR_2025_OFFICIAL"
AIDE_MODEL = "AIDE GenImage train"
AIDE_VERSION = "official-6725b710"
AIDE_INPUT_SIZE = 256
AIDE_MAX_BYTES = 25 * 1024 * 1024
# AIDE's official evaluation uses a 0.5 binary decision boundary. These two
# response fields remain for API compatibility and intentionally share it.
AIDE_SYNTHETIC_THRESHOLD = 0.50
AIDE_AUTHENTIC_THRESHOLD = 0.50


class QualityIssue(BaseModel):
    code: str
    severity: str
    message: str


class ImageQualityAssessment(BaseModel):
    status: str
    modelEligible: bool
    qualityScore: int
    width: int
    height: int
    minDimension: int
    aspectRatio: float
    sharpnessVariance: float
    grayscaleEntropy: float
    issues: list[QualityIssue]


class AideDetection(BaseModel):
    provider: str
    model: str
    modelVersion: str
    checkpointSha256: str
    device: str
    precision: str
    syntheticProbability: float | None = None
    authenticProbability: float | None = None
    classification: str
    syntheticThreshold: float
    authenticThreshold: float
    width: int
    height: int
    processingMilliseconds: int
    qualityAssessment: ImageQualityAssessment
    attentionMethod: str | None = None
    attentionTarget: str | None = None
    attentionOverlayPngBase64: str | None = None
    limitations: list[str]


class AideDetector(Protocol):
    @property
    def loaded(self) -> bool: ...

    @property
    def configured(self) -> bool: ...

    @property
    def device_name(self) -> str: ...

    def detect(self, content: bytes) -> AideDetection: ...


class LocalAideDetector:
    """Lazy adapter around the authors' official AIDE model and checkpoint."""

    def __init__(self, repository_root: Path, runtime_root: Path) -> None:
        self._checkpoint_path = self._resolve_path(
            os.getenv("AIDE_CHECKPOINT_PATH", str(runtime_root / "models" / "aide" / "GenImage_train.pth")),
            repository_root,
        )
        self._source_path = self._resolve_path(
            os.getenv("AIDE_SOURCE_PATH", str(runtime_root / "vendor" / "AIDE")), repository_root
        )
        self._requested_device = os.getenv("AIDE_DEVICE", "cpu").lower()
        self._requested_precision = os.getenv("AIDE_PRECISION", "auto").lower()
        self._device = torch.device("cpu")
        self._precision = "float32"
        # The official AIDE repository is loaded dynamically and has no PEP
        # 561 type metadata. Confine Any to this third-party adapter boundary.
        self._model: Any | None = None
        self._preprocessor: Any | None = None
        self._checkpoint_sha256 = ""
        self._load_lock = Lock()
        self._inference_lock = Lock()

    @property
    def loaded(self) -> bool:
        return self._model is not None

    @property
    def configured(self) -> bool:
        return self._checkpoint_path.is_file() and (self._source_path / "models" / "AIDE.py").is_file()

    @property
    def device_name(self) -> str:
        return str(self._device)

    def detect(self, content: bytes) -> AideDetection:
        if not content:
            raise ValueError("Image content cannot be empty")
        if len(content) > AIDE_MAX_BYTES:
            raise ValueError(f"Image exceeds the {AIDE_MAX_BYTES} byte AIDE limit")
        image = self._open_image(content)
        width, height = image.size
        quality = self._assess_quality(image)
        if not quality.modelEligible:
            return AideDetection(
                provider=AIDE_PROVIDER,
                model=AIDE_MODEL,
                modelVersion=AIDE_VERSION,
                checkpointSha256=self._checkpoint_sha256,
                device=self.device_name,
                precision=self._precision,
                classification="UNSUPPORTED_INPUT",
                syntheticThreshold=AIDE_SYNTHETIC_THRESHOLD,
                authenticThreshold=AIDE_AUTHENTIC_THRESHOLD,
                width=width,
                height=height,
                processingMilliseconds=0,
                qualityAssessment=quality,
                limitations=[
                    "输入未通过图像质量门控，AIDE 未执行，不能据此判断媒体真伪",
                ],
            )
        self._load()
        assert self._model is not None
        started = time.perf_counter()
        with self._inference_lock:
            batch: torch.Tensor = self._preprocess(image).unsqueeze(0).to(self._device)
            if self._precision == "float16":
                batch = batch.half()
            logits, attention = self._forward_with_attention(batch)
            probabilities = functional.softmax(logits.float(), dim=1)[0].detach().cpu()
        authentic_probability = float(probabilities[0].item())
        synthetic_probability = float(probabilities[1].item())
        classification = self._classify(synthetic_probability)
        return AideDetection(
            provider=AIDE_PROVIDER,
            model=AIDE_MODEL,
            modelVersion=AIDE_VERSION,
            checkpointSha256=self._checkpoint_sha256,
            device=self.device_name,
            precision=self._precision,
            syntheticProbability=round(synthetic_probability, 6),
            authenticProbability=round(authentic_probability, 6),
            classification=classification,
            syntheticThreshold=AIDE_SYNTHETIC_THRESHOLD,
            authenticThreshold=AIDE_AUTHENTIC_THRESHOLD,
            width=width,
            height=height,
            processingMilliseconds=round((time.perf_counter() - started) * 1000),
            qualityAssessment=quality,
            attentionMethod="Grad-CAM on AIDE semantic ConvNeXt feature map",
            attentionTarget=classification,
            attentionOverlayPngBase64=self._attention_overlay(image, attention),
            limitations=[
                "AIDE 分数未经 OriginGuard 业务数据校准，不能单独作为最终真伪结论",
                "压缩、缩放、截图和未见过的生成器可能影响模型泛化能力",
                "热力图表示语义分支对当前分类的注意力贡献，不等同于精确的生成或篡改区域",
                "AIDE 的频域分支参与最终概率计算，但当前热力图不对频域分支进行空间定位",
            ],
        )

    def _assess_quality(self, image: Image.Image) -> ImageQualityAssessment:
        width, height = image.size
        min_dimension = min(width, height)
        aspect_ratio = max(width, height) / max(1, min_dimension)
        sample = image.convert("L")
        sample.thumbnail((256, 256), Image.Resampling.LANCZOS)
        grayscale = np.asarray(sample, dtype=np.float32)
        edges = np.asarray(sample.filter(ImageFilter.FIND_EDGES), dtype=np.float32)
        if edges.shape[0] > 2 and edges.shape[1] > 2:
            edges = edges[1:-1, 1:-1]
        sharpness = float(np.var(edges))
        histogram = np.bincount(grayscale.astype(np.uint8).ravel(), minlength=256).astype(np.float64)
        probabilities = histogram[histogram > 0] / max(1.0, histogram.sum())
        entropy = float(-(probabilities * np.log2(probabilities)).sum())

        issues: list[QualityIssue] = []
        if min_dimension < 128:
            issues.append(QualityIssue(
                code="RESOLUTION_TOO_LOW", severity="REJECT",
                message="图片最短边小于 128 像素，无法支持可靠的 AIGC 模型判断。",
            ))
        elif min_dimension < 256:
            issues.append(QualityIssue(
                code="RESOLUTION_LOW", severity="WARNING",
                message="图片最短边小于 256 像素，缩放可能削弱检测可靠性。",
            ))
        if aspect_ratio > 10.0:
            issues.append(QualityIssue(
                code="ASPECT_RATIO_EXTREME", severity="REJECT",
                message="图片长宽比过于极端，超出当前检测流程的可靠输入范围。",
            ))
        if sharpness < 20.0:
            issues.append(QualityIssue(
                code="IMAGE_BLURRED", severity="WARNING",
                message="图片边缘信息较弱，模糊或强压缩可能影响模型特征。",
            ))
        if entropy < 3.0:
            issues.append(QualityIssue(
                code="LOW_VISUAL_INFORMATION", severity="WARNING",
                message="图片视觉信息量较低，模型分数需要谨慎解释。",
            ))

        rejected = any(issue.severity == "REJECT" for issue in issues)
        warned = any(issue.severity == "WARNING" for issue in issues)
        score = 100
        score -= sum(45 if issue.severity == "REJECT" else 15 for issue in issues)
        return ImageQualityAssessment(
            status="REJECT" if rejected else "WARN" if warned else "PASS",
            modelEligible=not rejected,
            qualityScore=max(0, score),
            width=width,
            height=height,
            minDimension=min_dimension,
            aspectRatio=round(aspect_ratio, 4),
            sharpnessVariance=round(sharpness, 4),
            grayscaleEntropy=round(entropy, 4),
            issues=issues,
        )

    def _forward_with_attention(self, batch: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """Run the official classifier while retaining gradients only at its semantic feature map."""
        assert self._model is not None
        model = self._model
        x_min, x_max, x_min_second, x_max_second, tokens = [batch[:, index] for index in range(5)]
        with torch.no_grad():
            frequency: torch.Tensor = (
                model.model_min(model.hpf(x_min))
                + model.model_max(model.hpf(x_max))
                + model.model_min(model.hpf(x_min_second))
                + model.model_max(model.hpf(x_max_second))
            ) / 4
            clip_mean = torch.tensor([0.48145466, 0.4578275, 0.40821073], device=tokens.device, dtype=tokens.dtype).view(3, 1, 1)
            clip_std = torch.tensor([0.26862954, 0.26130258, 0.27577711], device=tokens.device, dtype=tokens.dtype).view(3, 1, 1)
            imagenet_mean = torch.tensor([0.485, 0.456, 0.406], device=tokens.device, dtype=tokens.dtype).view(3, 1, 1)
            imagenet_std = torch.tensor([0.229, 0.224, 0.225], device=tokens.device, dtype=tokens.dtype).view(3, 1, 1)
            semantic_map: torch.Tensor = model.openclip_convnext_xxl(
                tokens * (imagenet_std / clip_std) + (imagenet_mean - clip_mean) / clip_std
            )
        semantic_map = semantic_map.detach().requires_grad_(True)
        semantic: torch.Tensor = model.convnext_proj(model.avgpool(semantic_map).flatten(1))
        logits: torch.Tensor = model.fc(torch.cat([semantic, frequency], dim=1))
        target_index = int(logits.detach().argmax(dim=1)[0].item())
        gradients = torch.autograd.grad(logits[0, target_index], semantic_map)[0]
        weights = gradients.mean(dim=(2, 3), keepdim=True)
        attention = (weights * semantic_map).sum(dim=1, keepdim=True).relu()
        if float(attention.max().detach()) <= 1e-12:
            attention = (weights * semantic_map).sum(dim=1, keepdim=True).abs()
        attention = attention.detach().float()
        attention -= attention.amin(dim=(2, 3), keepdim=True)
        attention /= attention.amax(dim=(2, 3), keepdim=True).clamp_min(1e-12)
        return logits.detach(), attention[0, 0].cpu()

    def _attention_overlay(self, image: Image.Image, attention: torch.Tensor) -> str:
        longest = max(image.size)
        scale = min(1.0, 1024.0 / longest)
        size = (max(1, round(image.width * scale)), max(1, round(image.height * scale)))
        source = image.resize(size, Image.Resampling.LANCZOS)
        mask = Image.fromarray((attention.numpy() * 255).astype(np.uint8), mode="L").resize(
            size, Image.Resampling.BICUBIC
        )
        values = np.asarray(mask, dtype=np.float32) / 255.0
        # Compact blue -> cyan -> yellow -> red palette without an extra plotting dependency.
        red = np.clip(1.5 - np.abs(4.0 * values - 3.0), 0.0, 1.0)
        green = np.clip(1.5 - np.abs(4.0 * values - 2.0), 0.0, 1.0)
        blue = np.clip(1.5 - np.abs(4.0 * values - 1.0), 0.0, 1.0)
        heatmap = Image.fromarray((np.stack([red, green, blue], axis=-1) * 255).astype(np.uint8), mode="RGB")
        alpha = Image.fromarray((values * 150).astype(np.uint8), mode="L")
        overlay = Image.composite(heatmap, source, alpha)
        output = io.BytesIO()
        overlay.save(output, format="PNG", optimize=True)
        return base64.b64encode(output.getvalue()).decode("ascii")

    def _load(self) -> None:
        if self.loaded:
            return
        with self._load_lock:
            if self.loaded:
                return
            if not self.configured:
                raise FileNotFoundError(
                    "AIDE source or checkpoint is missing. Run scripts/setup-aide.ps1 first."
                )
            self._device = self._resolve_device()
            self._precision = self._resolve_precision()
            source = str(self._source_path)
            if source not in sys.path:
                sys.path.insert(0, source)
            try:
                from data.dct import DCT_base_Rec_Module
                from models.AIDE import AIDE
            except ImportError as exception:
                raise RuntimeError("Unable to import the pinned official AIDE source") from exception

            model = AIDE(None, None)
            # The official Model Zoo file contains tensor weights under the "model" key.
            checkpoint = torch.load(self._checkpoint_path, map_location="cpu", weights_only=True)
            state = checkpoint.get("model", checkpoint) if isinstance(checkpoint, dict) else checkpoint
            if not isinstance(state, dict):
                raise TypeError("Official AIDE checkpoint does not contain a model state dictionary")
            model.load_state_dict(state, strict=True)
            del checkpoint, state
            model.eval()
            if self._precision == "float16":
                model = model.half()
            self._model = model.to(self._device)
            self._preprocessor = DCT_base_Rec_Module().eval()
            self._checkpoint_sha256 = self._sha256(self._checkpoint_path)

    def _preprocess(self, image: Image.Image) -> torch.Tensor:
        assert self._preprocessor is not None
        working = image
        longest = max(working.size)
        shortest = min(working.size)
        scale = min(1.0, 1024.0 / longest)
        if shortest * scale < 64:
            scale = 64.0 / shortest
        if scale != 1.0:
            working = working.resize(
                (max(64, round(working.width * scale)), max(64, round(working.height * scale))),
                Image.Resampling.BICUBIC,
            )
        tensor = transforms.ToTensor()(working)
        x_min, x_max, x_min_second, x_max_second = self._preprocessor(tensor)
        normalize = transforms.Compose(
            [
                transforms.Resize([AIDE_INPUT_SIZE, AIDE_INPUT_SIZE]),
                transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
            ]
        )
        return torch.stack(
            [
                normalize(x_min),
                normalize(x_max),
                normalize(x_min_second),
                normalize(x_max_second),
                normalize(tensor),
            ],
            dim=0,
        )

    def _open_image(self, content: bytes) -> Image.Image:
        try:
            with Image.open(io.BytesIO(content)) as source:
                source.verify()
            with Image.open(io.BytesIO(content)) as source:
                return cast(Image.Image, source.convert("RGB"))
        except (UnidentifiedImageError, OSError) as exception:
            raise ValueError("AIDE input is not a supported image") from exception

    def _resolve_device(self) -> torch.device:
        if self._requested_device == "auto":
            return torch.device("cuda" if torch.cuda.is_available() else "cpu")
        if self._requested_device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("AIDE_DEVICE=cuda but CUDA is unavailable")
        if self._requested_device not in {"cpu", "cuda"}:
            raise ValueError("AIDE_DEVICE must be auto, cpu, or cuda")
        return torch.device(self._requested_device)

    def _resolve_precision(self) -> str:
        if self._requested_precision == "auto":
            return "float32"
        if self._requested_precision not in {"float16", "float32"}:
            raise ValueError("AIDE_PRECISION must be auto, float16, or float32")
        if self._requested_precision == "float16" and self._device.type != "cuda":
            raise ValueError("AIDE_PRECISION=float16 is only supported on CUDA")
        return self._requested_precision

    def _classify(self, synthetic_probability: float) -> str:
        if synthetic_probability >= AIDE_SYNTHETIC_THRESHOLD:
            return "LIKELY_SYNTHETIC"
        return "LIKELY_AUTHENTIC"

    def _resolve_path(self, configured: str, repository_root: Path) -> Path:
        path = Path(configured)
        return (path if path.is_absolute() else repository_root / path).resolve()

    def _sha256(self, path: Path) -> str:
        digest = hashlib.sha256()
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
