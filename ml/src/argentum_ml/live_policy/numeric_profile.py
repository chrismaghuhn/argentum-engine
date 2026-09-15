"""Code-owned numeric execution profile for the accepted C1_06 worker."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..contracts.identities import NUMERIC_PROFILE_CONTRACT_IDENTITY


class NumericProfileError(ValueError):
    """Raised when the effective local runtime is outside the certified profile."""


@dataclass(frozen=True, init=False)
class C1ReferenceNumericExecutionProfileV1:
    """The exact numeric conditions certified for the C1_06 reference checkpoint."""

    profile_class: str
    contract_identity: str
    framework: str
    framework_version: str
    cuda_runtime_version: str
    backend: str
    dtype: str
    autocast_enabled: bool
    matmul_allow_tf32: bool
    cudnn_allow_tf32: bool
    float32_matmul_precision: str
    deterministic_algorithms: bool
    inference_mode: str
    device_capability: tuple[int, int]

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError("C1ReferenceNumericExecutionProfileV1 is code-owned; use reference()")

    @classmethod
    def reference(cls) -> "C1ReferenceNumericExecutionProfileV1":
        values = {
            "profile_class": "C1_REFERENCE_NUMERIC_PROFILE",
            "contract_identity": NUMERIC_PROFILE_CONTRACT_IDENTITY,
            "framework": "pytorch",
            "framework_version": "2.14.0+cu130",
            "cuda_runtime_version": "13.0",
            "backend": "cuda:0",
            "dtype": "float32",
            "autocast_enabled": False,
            "matmul_allow_tf32": False,
            "cudnn_allow_tf32": True,
            "float32_matmul_precision": "highest",
            "deterministic_algorithms": True,
            "inference_mode": "eval",
            "device_capability": (8, 9),
        }
        instance = object.__new__(cls)
        for key, value in values.items():
            object.__setattr__(instance, key, value)
        return instance

    def configure_and_validate(self, torch_module: Any, device: Any) -> None:
        """Configure declared settings, then reject any effective-profile mismatch."""

        self._validate_static_runtime(torch_module, device)
        try:
            torch_module.use_deterministic_algorithms(self.deterministic_algorithms)
            torch_module.backends.cuda.matmul.allow_tf32 = self.matmul_allow_tf32
            torch_module.backends.cudnn.allow_tf32 = self.cudnn_allow_tf32
            torch_module.set_float32_matmul_precision(self.float32_matmul_precision)
            torch_module.set_autocast_enabled("cuda", self.autocast_enabled)
        except (AttributeError, TypeError, RuntimeError) as exc:
            raise NumericProfileError("C1 reference numeric runtime cannot be configured") from exc
        self.validate_effective(torch_module, device)

    def validate_effective(self, torch_module: Any, device: Any) -> None:
        """Verify the effective runtime after configuration and before inference."""

        self._validate_static_runtime(torch_module, device)
        try:
            deterministic = bool(torch_module.are_deterministic_algorithms_enabled())
            matmul_tf32 = bool(torch_module.backends.cuda.matmul.allow_tf32)
            cudnn_tf32 = bool(torch_module.backends.cudnn.allow_tf32)
            precision = str(torch_module.get_float32_matmul_precision())
            autocast = bool(torch_module.is_autocast_enabled("cuda"))
            default_dtype = _dtype_name(torch_module.get_default_dtype())
        except (AttributeError, TypeError, RuntimeError) as exc:
            raise NumericProfileError("C1 reference numeric runtime cannot be inspected") from exc
        if deterministic != self.deterministic_algorithms:
            raise NumericProfileError("deterministic-algorithm mode differs from C1 reference")
        if matmul_tf32 != self.matmul_allow_tf32 or cudnn_tf32 != self.cudnn_allow_tf32:
            raise NumericProfileError("TF32 settings differ from C1 reference")
        if precision != self.float32_matmul_precision:
            raise NumericProfileError("float32 matmul precision differs from C1 reference")
        if autocast != self.autocast_enabled:
            raise NumericProfileError("CUDA autocast setting differs from C1 reference")
        if default_dtype != self.dtype:
            raise NumericProfileError("default dtype differs from C1 reference")

    def validate_model(self, torch_module: Any, model: Any, device: Any) -> None:
        """Verify evaluation mode, parameter backend and parameter dtype."""

        if getattr(model, "training", None) is not False:
            raise NumericProfileError("C1 model is not in evaluation mode")
        try:
            parameters = tuple(model.parameters())
        except (AttributeError, TypeError) as exc:
            raise NumericProfileError("C1 model parameters cannot be inspected") from exc
        if not parameters:
            raise NumericProfileError("C1 model has no parameters")
        for parameter in parameters:
            if not _device_is_cuda_zero(getattr(parameter, "device", None)):
                raise NumericProfileError("C1 model parameter is not on cuda:0")
            if _dtype_name(getattr(parameter, "dtype", None)) != self.dtype:
                raise NumericProfileError("C1 model parameter dtype differs from float32")

    def _validate_static_runtime(self, torch_module: Any, device: Any) -> None:
        if getattr(torch_module, "__version__", None) != self.framework_version:
            raise NumericProfileError("PyTorch framework version differs from C1 reference")
        if not _device_is_cuda_zero(device):
            raise NumericProfileError("numeric runtime device is not cuda:0")
        version = getattr(getattr(torch_module, "version", None), "cuda", None)
        if version != self.cuda_runtime_version:
            raise NumericProfileError("CUDA runtime version differs from C1 reference")
        cuda = getattr(torch_module, "cuda", None)
        if cuda is None or not bool(cuda.is_available()) or int(cuda.device_count()) < 1:
            raise NumericProfileError("CUDA device cuda:0 is unavailable")
        try:
            capability = tuple(cuda.get_device_capability(0))
        except (AttributeError, TypeError, RuntimeError) as exc:
            raise NumericProfileError("CUDA device capability cannot be inspected") from exc
        if capability != self.device_capability:
            raise NumericProfileError("CUDA device capability differs from C1 reference")


def _device_is_cuda_zero(device: Any) -> bool:
    return (
        getattr(device, "type", None) == "cuda"
        and getattr(device, "index", None) == 0
    )


def _dtype_name(value: Any) -> str:
    text = str(value)
    return text.removeprefix("torch.")


C1_REFERENCE_NUMERIC_PROFILE = C1ReferenceNumericExecutionProfileV1.reference()
