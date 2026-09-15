"""C1_06 feed-forward ScoreProvider adapter for the local live worker."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Mapping, Sequence

from ..learner.c1_06 import (
    C1_06DataGateError,
    C1_06GpuGateError,
    FeedForwardCandidateScorer,
    load_c1_06_checkpoint,
    require_cuda_device,
    tensorize_live_input,
)
from ..learner.optional import LearnerToolingUnavailable, require_optional_module
from ..learner.weights import WeightArtifactError
from .errors import LivePolicyCheckpointError, LivePolicyInferenceError
from .profile import C1_07BPolicyProfile


class C1_06LiveScoreProvider:
    """A profile-bound C1_06 model that exposes only finite candidate scores."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError("C1_06LiveScoreProvider must be loaded from the fixed profile")

    @classmethod
    def from_checkpoint(
        cls,
        profile: C1_07BPolicyProfile,
        checkpoint_dir: Path | str,
    ) -> "C1_06LiveScoreProvider":
        if not isinstance(profile, C1_07BPolicyProfile):
            raise LivePolicyCheckpointError("C1_06 provider requires the server-owned profile", code="PROFILE_INVALID")
        artifact = profile.validate_checkpoint_artifact(checkpoint_dir)
        try:
            torch = require_optional_module("torch", "torch")
            device = cls.require_cuda(torch)
            config = profile.model_config
            model = FeedForwardCandidateScorer(config, device=device)
            load_c1_06_checkpoint(
                model,
                artifact.manifest_path,
                artifact.weight_path,
                expected_source_dataset_identity=profile.expected_source_dataset_identity,
                expected_label_artifact_id=profile.expected_label_artifact_id,
                expected_labels_content_digest=profile.expected_labels_content_digest,
                expected_manifest_content_digest=profile.expected_source_manifest_content_digest,
                expected_source_commit=profile.expected_source_commit,
                config=config,
            )
            _require_model_on_device(model, device)
        except LivePolicyCheckpointError:
            raise
        except LearnerToolingUnavailable as exc:
            raise LivePolicyCheckpointError(
                "C1_06 learner tooling is unavailable",
                code="CHECKPOINT_TOOLING_UNAVAILABLE",
            ) from exc
        except C1_06GpuGateError as exc:
            raise LivePolicyCheckpointError(
                "C1_07B requires CUDA device cuda:0 and has no CPU fallback",
                code="CUDA_PROFILE_UNAVAILABLE",
            ) from exc
        except (C1_06DataGateError, WeightArtifactError, OSError, RuntimeError, ValueError) as exc:
            raise LivePolicyCheckpointError(
                "C1_06 checkpoint could not be loaded under the fixed profile",
                code="CHECKPOINT_MODEL_LOAD_FAILURE",
            ) from exc
        instance = object.__new__(cls)
        object.__setattr__(instance, "_torch", torch)
        object.__setattr__(instance, "_model", model)
        object.__setattr__(instance, "_device", device)
        object.__setattr__(instance, "_config", config)
        object.__setattr__(instance, "_artifact", artifact)
        object.__setattr__(instance, "checkpoint_id", profile.expected_checkpoint_id)
        object.__setattr__(instance, "numeric_profile_class", profile.expected_numeric_profile_class)
        return instance

    @staticmethod
    def require_cuda(torch_module: Any) -> Any:
        try:
            return require_cuda_device(torch_module)
        except C1_06GpuGateError as exc:
            raise LivePolicyCheckpointError(
                "C1_07B requires CUDA device cuda:0 and has no CPU fallback",
                code="CUDA_PROFILE_UNAVAILABLE",
            ) from exc

    def score(
        self,
        model_input: Mapping[str, Any],
        candidates: Sequence[Mapping[str, Any]],
    ) -> tuple[float, ...]:
        if not isinstance(model_input, Mapping):
            raise LivePolicyInferenceError("live model input must be an object", code="MODEL_INPUT_INVALID")
        try:
            batch = tensorize_live_input(
                model_input,
                candidates,
                torch_module=self._torch,
                device=self._device,
                config=self._config,
            )
            with self._torch.no_grad():
                scores = self._model(batch.observation, batch.candidates)
            if not getattr(scores, "is_cuda", False) or scores.device != self._device:
                raise LivePolicyInferenceError(
                    "C1_06 scores did not remain on cuda:0",
                    code="CUDA_PROFILE_VIOLATION",
                )
            if scores.ndim != 2 or scores.shape[0] != 1 or scores.shape[1] != len(candidates):
                raise LivePolicyInferenceError(
                    "C1_06 score tensor shape does not match candidates",
                    code="SCORE_VECTOR_INVALID",
                )
            if not bool(self._torch.isfinite(scores).all().item()):
                raise LivePolicyInferenceError(
                    "C1_06 produced non-finite scores",
                    code="SCORE_NONFINITE",
                )
            return tuple(float(score) for score in scores[0].detach().cpu().tolist())
        except LivePolicyInferenceError:
            raise
        except (RuntimeError, ValueError, TypeError) as exc:
            raise LivePolicyInferenceError(
                "C1_06 live scoring failed",
                code="SCORE_PROVIDER_FAILURE",
            ) from exc


def _require_model_on_device(model: Any, device: Any) -> None:
    try:
        parameters = tuple(model.parameters())
    except (AttributeError, TypeError) as exc:
        raise LivePolicyCheckpointError(
            "C1_06 model does not expose parameters for CUDA verification",
            code="CUDA_PROFILE_VIOLATION",
        ) from exc
    if not parameters or any(not getattr(parameter, "is_cuda", False) or parameter.device != device for parameter in parameters):
        raise LivePolicyCheckpointError(
            "C1_06 model parameters are not all on cuda:0",
            code="CUDA_PROFILE_VIOLATION",
        )
