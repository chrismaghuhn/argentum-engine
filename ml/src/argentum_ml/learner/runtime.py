"""PyTorch runtime provenance for optional learner tooling."""

from __future__ import annotations

import platform
from dataclasses import dataclass

from .optional import require_optional_module


@dataclass(frozen=True)
class TorchRuntimeProvenance:
    """Physical PyTorch runtime facts, not semantic model identity."""

    framework: str
    torch_version: str
    python_version: str
    cuda_available: bool


def torch_runtime_provenance() -> TorchRuntimeProvenance:
    """Return the selected PyTorch runtime provenance without importing it at package load."""
    torch = require_optional_module("torch", "torch")
    return TorchRuntimeProvenance(
        framework="pytorch",
        torch_version=str(torch.__version__),
        python_version=platform.python_version(),
        cuda_available=bool(torch.cuda.is_available()),
    )
