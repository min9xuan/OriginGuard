from __future__ import annotations

import io
import os
from pathlib import Path
from threading import Lock
from typing import Any, Protocol

import torch
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel
from torchvision import transforms


class DiffusionVerification(BaseModel):
    provider: str
    model: str
    modelVersion: str
    status: str
    reconstructionDistance: float | None = None
    aerobladeScore: float | None = None
    distanceMetric: str
    autoencoder: str
    calibrated: bool
    classification: str
    device: str
    limitations: list[str]


class DiffusionVerifier(Protocol):
    @property
    def configured(self) -> bool: ...

    @property
    def loaded(self) -> bool: ...

    def verify(self, content: bytes, suffix: str) -> DiffusionVerification: ...


class LocalDiffusionVerifier:
    """Minimal production adapter for AEROBLADE's VAE/LPIPS method."""

    def __init__(self, repository_root: Path, runtime_root: Path) -> None:
        configured = os.getenv("AEROBLADE_SOURCE_PATH", str(runtime_root / "vendor-src" / "aeroblade"))
        path = Path(configured)
        self._source_path = (path if path.is_absolute() else repository_root / path).resolve()
        self._runtime_root = runtime_root
        self._autoencoder = os.getenv("AEROBLADE_AUTOENCODER", "stabilityai/sd-vae-ft-mse")
        self._metric = "lpips_vgg_2"
        threshold = os.getenv("AEROBLADE_DISTANCE_THRESHOLD", "").strip()
        self._threshold = float(threshold) if threshold else None
        self._requested_device = os.getenv("AEROBLADE_DEVICE", "auto").lower()
        self._device = torch.device("cpu")
        self._vae: Any | None = None
        self._lpips: Any | None = None
        self._load_lock = Lock()
        self._inference_lock = Lock()

    @property
    def configured(self) -> bool:
        source_present = (self._source_path / "README.md").is_file()
        ready_marker = (self._runtime_root / "models" / "aeroblade" / "ready").is_file()
        return source_present and ready_marker

    @property
    def loaded(self) -> bool:
        return self._vae is not None and self._lpips is not None

    def verify(self, content: bytes, suffix: str) -> DiffusionVerification:
        del suffix
        if not content:
            raise ValueError("Image content cannot be empty")
        if not self.configured:
            raise FileNotFoundError("Diffusion reconstruction verifier is not configured")
        try:
            image = Image.open(io.BytesIO(content)).convert("RGB")
        except (UnidentifiedImageError, OSError) as exception:
            raise ValueError("Image payload cannot be decoded") from exception
        self._load()
        assert self._vae is not None
        assert self._lpips is not None
        image_tensor = transforms.Compose(
            [transforms.Resize((512, 512), antialias=True), transforms.ToTensor()]
        )(image).unsqueeze(0).to(self._device)
        vae_input = image_tensor.mul(2.0).sub(1.0)
        generator = torch.Generator(device=self._device).manual_seed(1)
        with self._inference_lock, torch.inference_mode():
            latent = self._vae.encode(vae_input.to(dtype=self._vae.dtype)).latent_dist.sample(generator=generator)
            reconstruction = self._vae.decode(latent).sample.float().add(1.0).div(2.0).clamp(0.0, 1.0)
            _total, layers = self._lpips(image_tensor.float(), reconstruction, normalize=True, retPerLayer=True)
            distance = float(layers[1].mean().item())
        classification = "INCONCLUSIVE"
        if self._threshold is not None:
            # AEROBLADE: a smaller VAE reconstruction error supports a
            # related latent-diffusion origin.
            classification = "LIKELY_DIFFUSION_GENERATED" if distance <= self._threshold else "NO_DIFFUSION_SIGNAL"
        return DiffusionVerification(
            provider="DIFFUSION_RECONSTRUCTION_VERIFIER",
            model="Training-free diffusion reconstruction verifier",
            modelVersion="aeroblade-method-v1",
            status="SUCCEEDED",
            reconstructionDistance=distance,
            aerobladeScore=-distance,
            distanceMetric=self._metric,
            autoencoder=self._autoencoder,
            calibrated=self._threshold is not None,
            classification=classification,
            device=str(self._device),
            limitations=[
                "重建距离不是 AIGC 概率；未配置验证集阈值时只作为辅助观察。",
                "该复核主要针对与所选扩散 VAE 相关的生成图像，不能覆盖所有生成架构。",
            ],
        )

    def warm_up(self) -> None:
        """Download/load inference assets so setup can fail before enabling the adapter."""
        self._load()

    def _load(self) -> None:
        if self.loaded:
            return
        with self._load_lock:
            if self.loaded:
                return
            try:
                import lpips
                from diffusers import AutoencoderKL
            except ImportError as exception:
                raise RuntimeError("Diffusion verifier dependencies are missing") from exception
            self._device = self._resolve_device()
            cache_dir = self._runtime_root / "cache" / "huggingface"
            dtype = torch.float16 if self._device.type == "cuda" else torch.float32
            load_options: dict[str, Any] = {
                "torch_dtype": dtype,
                "use_safetensors": True,
                "cache_dir": cache_dir,
            }
            if not self._autoencoder.endswith("sd-vae-ft-mse"):
                load_options["subfolder"] = "vae"
            self._vae = AutoencoderKL.from_pretrained(  # type: ignore[no-untyped-call]
                self._autoencoder,
                **load_options,
            ).to(self._device).eval()
            self._lpips = lpips.LPIPS(net="vgg").to(self._device).eval()

    def _resolve_device(self) -> torch.device:
        if self._requested_device == "auto":
            return torch.device("cuda" if torch.cuda.is_available() else "cpu")
        if self._requested_device == "cuda" and not torch.cuda.is_available():
            raise RuntimeError("AEROBLADE_DEVICE=cuda but CUDA is unavailable")
        if self._requested_device not in {"cpu", "cuda"}:
            raise ValueError("AEROBLADE_DEVICE must be auto, cpu, or cuda")
        return torch.device(self._requested_device)
