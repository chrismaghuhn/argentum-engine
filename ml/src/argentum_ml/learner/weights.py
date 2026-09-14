"""Safetensors physical storage for PyTorch tensor state dicts."""

from __future__ import annotations

import hashlib
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..checkpoint.manifest import ArgentumCheckpointManifestV1
from .optional import require_optional_module


SAFETENSORS_CONTAINER_IDENTITY = "argentum-ml-safetensors-state-dict@v1"


class WeightArtifactError(ValueError):
    """Raised when a tensor state dict or physical weight file is invalid."""


@dataclass(frozen=True)
class SafetensorsWeightArtifact:
    """A physical weight file and its exact content digest."""

    path: Path
    content_digest: str
    tensor_names: tuple[str, ...]


def save_state_dict(
    state_dict: Mapping[str, Any],
    path: Path | str,
) -> SafetensorsWeightArtifact:
    """Serialize a tensor-only state dict to one ordinary Safetensors file."""
    torch = require_optional_module("torch", "torch")
    safetensors_torch = require_optional_module("safetensors.torch", "safetensors")
    tensors, tensor_names = _validate_state_dict(state_dict, torch)
    target = _validate_output_path(path)
    try:
        serialized = safetensors_torch.save(tensors)
    except Exception as exc:
        raise WeightArtifactError("Safetensors could not serialize tensor state dict") from exc
    try:
        target.write_bytes(serialized)
        weight_bytes = target.read_bytes()
    except OSError as exc:
        raise WeightArtifactError("weight artifact path could not be written") from exc
    _require_regular_file(target, "weight artifact")
    return SafetensorsWeightArtifact(
        path=target,
        content_digest=hashlib.sha256(weight_bytes).hexdigest(),
        tensor_names=tensor_names,
    )


def load_state_dict(
    path: Path | str,
    manifest: ArgentumCheckpointManifestV1,
) -> dict[str, Any]:
    """Validate manifest-bound file bytes before decoding them as Safetensors."""
    if not isinstance(manifest, ArgentumCheckpointManifestV1):
        raise WeightArtifactError("weight loading requires a validated checkpoint manifest")
    source = _require_regular_file(path, "weight artifact")
    try:
        weight_bytes = source.read_bytes()
    except OSError as exc:
        raise WeightArtifactError("weight artifact could not be read") from exc
    manifest.validate_weight_bytes(weight_bytes)
    safetensors_torch = require_optional_module("safetensors.torch", "safetensors")
    try:
        loaded = safetensors_torch.load(weight_bytes)
    except Exception as exc:
        raise WeightArtifactError("invalid Safetensors weight artifact") from exc
    if not isinstance(loaded, Mapping):
        raise WeightArtifactError("Safetensors weight artifact did not decode to a mapping")
    return dict(loaded)


def _validate_state_dict(
    state_dict: Mapping[str, Any],
    torch: Any,
) -> tuple[dict[str, Any], tuple[str, ...]]:
    if not isinstance(state_dict, Mapping):
        raise WeightArtifactError("weight state dict must be a mapping")
    tensor_type = getattr(torch, "Tensor", None)
    strided_layout = getattr(torch, "strided", None)
    if tensor_type is None or strided_layout is None:
        raise WeightArtifactError("PyTorch tensor runtime is incomplete")
    tensors: dict[str, Any] = {}
    names: list[str] = []
    for name, tensor in state_dict.items():
        if not isinstance(name, str) or not name:
            raise WeightArtifactError("weight tensor names must be non-empty strings")
        if not isinstance(tensor, tensor_type):
            raise WeightArtifactError("weight state dict may contain only torch.Tensor values")
        if getattr(tensor, "layout", None) != strided_layout:
            raise WeightArtifactError("weight tensors must be dense")
        if not tensor.is_contiguous():
            raise WeightArtifactError("weight tensors must be contiguous")
        tensors[name] = tensor
        names.append(name)
    return tensors, tuple(names)


def _validate_output_path(path: Path | str) -> Path:
    try:
        target = Path(path)
    except (TypeError, ValueError) as exc:
        raise WeightArtifactError("weight artifact path must be path-like") from exc
    if target.is_symlink():
        raise WeightArtifactError("weight artifact path must not be a symlink")
    if target.exists() and not target.is_file():
        raise WeightArtifactError("weight artifact path must be a regular file")
    parent = target.parent
    if parent.is_symlink() or not parent.is_dir():
        raise WeightArtifactError("weight artifact parent must be a regular directory")
    return target


def _require_regular_file(path: Path | str, label: str) -> Path:
    try:
        candidate = Path(path)
    except (TypeError, ValueError) as exc:
        raise WeightArtifactError(f"{label} path must be path-like") from exc
    if candidate.is_symlink() or not candidate.is_file():
        raise WeightArtifactError(f"{label} path must be a regular file")
    return candidate
