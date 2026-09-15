"""C1_06 bounded feed-forward candidate-scoring smoke contracts."""

from __future__ import annotations

import hashlib
import argparse
import json
import math
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from ..contracts.canonical_json import canonical_bytes, canonical_json, sha256_hex
from ..checkpoint.manifest import ArgentumCheckpointManifestV1
from .weights import SAFETENSORS_CONTAINER_IDENTITY, load_state_dict, save_state_dict


class C1_06GpuGateError(RuntimeError):
    """Raised when the mandatory C1_06 CUDA gate cannot be proven."""


class C1_06DataGateError(ValueError):
    """Raised when a trusted C1_05-to-model-facing join cannot be proven."""


MODEL_ARCHITECTURE_IDENTITY = "argentum-ml-c1-06-feed-forward-candidate-scorer@v1"
MODEL_CONFIG_IDENTITY = "argentum-ml-c1-06-feed-forward-config@v1"
MODEL_IMPLEMENTATION_IDENTITY = "argentum-ml-c1-06-feed-forward-runtime@v1"
TRAINING_RECIPE_IDENTITY = "argentum-ml-c1-06-feed-forward-gpu-smoke@v1"
C1_05_SOURCE_ARTIFACT_ID = "be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a"
C1_05_LABEL_ARTIFACT_ID = "22c6d18fa05301010b7057a4f524ef689a89626aa7dfb7582d73572a4f0f1c52"
C1_05_LABELS_CONTENT_DIGEST = "5f683753dd01f8a7e20c6b6b2ef31c38e6bb4949cac116a49c7ccfbb577f3a24"
C1_05_MANIFEST_CONTENT_DIGEST = "8bbd0060ca18108eb48ca3954e913743cc27fdd062ba78d4228db76a0b448d2d"


@dataclass(frozen=True)
class CudaKernelEvidence:
    peak_memory_bytes: int


@dataclass(frozen=True)
class C1_06TrainingSample:
    model_input: Mapping[str, Any]
    candidate_feature_views: tuple[Mapping[str, Any], ...]
    target_index: int
    partition: str
    source_key: tuple[str, int, str]

    def __post_init__(self) -> None:
        if self.partition not in {"TRAIN", "VALIDATION"}:
            raise ValueError("C1_06 samples may only use TRAIN or VALIDATION")
        validate_candidate_batch(self.candidate_feature_views, target_index=self.target_index)
        if len(self.source_key) != 3:
            raise ValueError("source key must contain trajectory, decision, semantic ID")


def _source_key(value: Mapping[str, Any]) -> tuple[str, int, str]:
    source = value.get("sourceReference")
    if not isinstance(source, Mapping):
        raise C1_06DataGateError("sourceReference is missing")
    semantic = source.get("semanticDecisionId")
    if not isinstance(semantic, Mapping):
        raise C1_06DataGateError("semanticDecisionId is missing")
    trajectory = source.get("trajectoryId")
    decision_index = source.get("decisionIndex")
    semantic_value = semantic.get("value")
    if (
        not isinstance(trajectory, str)
        or not isinstance(decision_index, int)
        or isinstance(decision_index, bool)
        or not isinstance(semantic_value, str)
    ):
        raise C1_06DataGateError("source decision key is malformed")
    return trajectory, decision_index, semantic_value


def source_label_to_training_sample(
    source: Mapping[str, Any],
    label: Mapping[str, Any],
) -> C1_06TrainingSample:
    if source.get("partition") not in {"TRAIN", "VALIDATION"}:
        raise C1_06DataGateError("TEST or unsupported partition cannot enter C1_06")
    if label.get("partition") != source.get("partition"):
        raise C1_06DataGateError("source and label partitions differ")
    if canonical_json(source.get("sourceReference")) != canonical_json(label.get("sourceReference")):
        raise C1_06DataGateError("source and label references differ")
    model_input = source.get("input")
    binding = source.get("binding")
    if not isinstance(model_input, Mapping) or not isinstance(binding, Mapping):
        raise C1_06DataGateError("source model input/binding channels are missing")
    domain = model_input.get("domain")
    candidates = domain.get("candidates") if isinstance(domain, Mapping) else None
    ordinals = binding.get("sourceBindingOrdinals")
    label_binding = label.get("binding")
    ordinal = label_binding.get("sourceBindingOrdinal") if isinstance(label_binding, Mapping) else None
    if not isinstance(candidates, (list, tuple)) or not isinstance(ordinals, (list, tuple)):
        raise C1_06DataGateError("source candidate transport is missing")
    if not isinstance(ordinal, int) or isinstance(ordinal, bool) or ordinal not in ordinals:
        raise C1_06DataGateError("label target ordinal is absent from source transport")
    candidate_index = tuple(ordinals).index(ordinal)
    if candidate_index >= len(candidates):
        raise C1_06DataGateError("label target ordinal exceeds model candidate transport")
    if candidates[candidate_index].get("affordable") is not True:
        raise C1_06DataGateError("label target is not executable in model-facing transport")
    return C1_06TrainingSample(
        model_input=dict(model_input),
        candidate_feature_views=tuple(dict(candidate) for candidate in candidates),
        target_index=candidate_index,
        partition=str(source["partition"]),
        source_key=_source_key(source),
    )


def select_bounded_label_rows(
    labels: Sequence[Mapping[str, Any]],
    *,
    train_limit: int,
    validation_limit: int,
) -> list[Mapping[str, Any]]:
    if (
        isinstance(train_limit, bool)
        or isinstance(validation_limit, bool)
        or train_limit < 0
        or validation_limit < 0
    ):
        raise ValueError("bounded label limits must be non-negative integers")

    def ordered(partition: str) -> list[Mapping[str, Any]]:
        rows = [row for row in labels if row.get("partition") == partition]
        return sorted(rows, key=_source_key)

    return ordered("TRAIN")[:train_limit] + ordered("VALIDATION")[:validation_limit]


def load_c1_05_training_view(
    *,
    label_root: Any,
    source_artifact_root: Any,
    expected_source_artifact_id: str,
    expected_label_artifact_id: str,
    expected_labels_content_digest: str,
    expected_manifest_content_digest: str,
    train_limit: int,
    validation_limit: int,
) -> C1_06DataView:
    """Verify C1_05 authoritatively and select a stable bounded TRAIN/VALIDATION view."""

    from ..data.derived_reader import DerivedArtifactReader
    from ..data.label_artifact import LabelArtifactReader

    # C1_05's authoritative source-bound Reader PASS is accepted predecessor
    # evidence. Recheck the complete sidecar bytes/manifest structurally here,
    # then stream the exact source once for the bounded model-facing join.
    label_reader = LabelArtifactReader._open_structural(label_root)
    label_manifest = label_reader.manifest
    if label_manifest["sourceDerivedArtifactId"] != expected_source_artifact_id:
        raise C1_06DataGateError("C1_05 source artifact identity differs from expected")
    if label_manifest["labelArtifactId"] != expected_label_artifact_id:
        raise C1_06DataGateError("C1_05 label artifact identity differs from expected")
    if label_manifest["labelsContentDigest"] != expected_labels_content_digest:
        raise C1_06DataGateError("C1_05 labels content digest differs from expected")
    if label_manifest["manifestContentDigest"] != expected_manifest_content_digest:
        raise C1_06DataGateError("C1_05 manifest content digest differs from expected")
    selected_labels = select_bounded_label_rows(
        tuple(label_reader.iter_labels()),
        train_limit=train_limit,
        validation_limit=validation_limit,
    )
    labels_by_key = {_source_key(label): label for label in selected_labels}
    samples: list[C1_06TrainingSample] = []
    source_reader = DerivedArtifactReader.open(source_artifact_root)
    try:
        source_manifest = source_reader.manifest
        if source_manifest["derivedArtifactId"] != expected_source_artifact_id:
            raise C1_06DataGateError("source derived artifact identity differs from expected")
        if source_manifest["sampleCountsByPartition"] != label_manifest["sourceRowsByPartition"]:
            raise C1_06DataGateError("source partition counts differ from C1_05 manifest")
        for validated in source_reader.iter_validated_samples_for_inference():
            source = validated.sample
            label = labels_by_key.pop(_source_key(source), None)
            if label is not None:
                samples.append(source_label_to_training_sample(source, label))
    finally:
        source_reader.close()
    if labels_by_key:
        raise C1_06DataGateError("selected C1_05 labels were absent from source transport")
    if any(sample.partition == "TEST" for sample in samples):
        raise C1_06DataGateError("C1_06 selected a TEST sample")
    return C1_06DataView(
        samples=tuple(samples),
        label_manifest=label_manifest,
        source_manifest=source_manifest,
    )


@dataclass(frozen=True)
class C1_06TensorBatch:
    observation: Any
    candidates: Any
    candidate_mask: Any
    target_indices: Any


@dataclass(frozen=True)
class C1_06InferenceTensorBatch:
    """C1_06 model inputs for live scoring without a training target or label channel."""

    observation: Any
    candidates: Any


@dataclass(frozen=True)
class C1_06DataView:
    samples: tuple[C1_06TrainingSample, ...]
    label_manifest: Mapping[str, Any]
    source_manifest: Mapping[str, Any]


@dataclass(frozen=True)
class C1_06CheckpointResult:
    manifest_path: Path
    weight_path: Path
    manifest: ArgentumCheckpointManifestV1


@dataclass(frozen=True)
class C1_06TrainingResult:
    initial_train_loss: float
    initial_train_top1_agreement: float
    final_train_loss: float
    validation_loss: float
    train_top1_agreement: float
    validation_top1_agreement: float
    optimizer_steps: int
    training_examples_processed: int
    wall_seconds: float
    nonfinite_scores: int
    nonfinite_losses: int
    nonfinite_gradients: int
    cuda_max_memory_allocated_bytes: int
    cuda_max_memory_reserved_bytes: int


@dataclass(frozen=True)
class C1_06ModelConfigV1:
    """Explicit stateless reference architecture configuration."""

    version: int
    architecture_identity: str
    config_identity: str
    observation_width: int
    candidate_width: int
    hidden_width: int
    hidden_layers: int
    dtype: str

    @classmethod
    def reference(cls) -> "C1_06ModelConfigV1":
        return cls(
            version=1,
            architecture_identity=MODEL_ARCHITECTURE_IDENTITY,
            config_identity=MODEL_CONFIG_IDENTITY,
            observation_width=32,
            candidate_width=32,
            hidden_width=128,
            hidden_layers=2,
            dtype="float32",
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "architectureIdentity": self.architecture_identity,
            "configIdentity": self.config_identity,
            "observationWidth": self.observation_width,
            "candidateWidth": self.candidate_width,
            "hiddenWidth": self.hidden_width,
            "hiddenLayers": self.hidden_layers,
            "dtype": self.dtype,
        }

    @property
    def digest(self) -> str:
        return sha256_hex(canonical_bytes(self.to_dict()))


def require_cuda_device(torch_module: Any) -> Any:
    """Require the C1_06 acceptance device; never fall back to CPU."""

    cuda = getattr(torch_module, "cuda", None)
    if cuda is None or not cuda.is_available() or cuda.device_count() < 1:
        raise C1_06GpuGateError("GPU_GATE=BLOCKED: CUDA device cuda:0 is unavailable")
    return torch_module.device("cuda:0")


def run_cuda_kernel_probe(torch_module: Any) -> CudaKernelEvidence:
    device = require_cuda_device(torch_module)
    torch_module.cuda.reset_peak_memory_stats()
    x = torch_module.randn((2048, 2048), device=device)
    y = x @ x
    value = y.sum()
    torch_module.cuda.synchronize(device)
    if not x.is_cuda or not y.is_cuda or not value.is_cuda:
        raise C1_06GpuGateError("CUDA_KERNEL_PROBE=FAIL: tensor did not execute on CUDA")
    peak = int(torch_module.cuda.max_memory_allocated())
    if peak <= 0:
        raise C1_06GpuGateError("CUDA_KERNEL_PROBE=FAIL: no CUDA memory was allocated")
    return CudaKernelEvidence(peak_memory_bytes=peak)


def validate_candidate_batch(
    candidates: Sequence[Mapping[str, Any]],
    *,
    target_index: int,
) -> None:
    if not candidates:
        raise ValueError("candidate batch must not be empty")
    if isinstance(target_index, bool) or not isinstance(target_index, int):
        raise ValueError("target index must be an integer")
    if target_index < 0 or target_index >= len(candidates):
        raise ValueError("target index is outside the supplied candidate batch")


def _feature_vector(value: Any, width: int) -> tuple[float, ...]:
    digest = hashlib.sha256(canonical_json(value).encode("utf-8")).digest()
    if width > len(digest):
        raise ValueError("C1_06 feature width exceeds canonical digest width")
    return tuple((byte / 255.0) * 2.0 - 1.0 for byte in digest[:width])


def _observation_payload(model_input: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "decisionContext": model_input.get("decisionContext"),
        "observation": model_input.get("observation"),
    }


def tensorize_samples(
    samples: Sequence[C1_06TrainingSample],
    *,
    torch_module: Any,
    device: Any,
    config: C1_06ModelConfigV1,
) -> C1_06TensorBatch:
    if not samples:
        raise ValueError("C1_06 tensor batch must not be empty")
    max_candidates = max(len(sample.candidate_feature_views) for sample in samples)
    observation = torch_module.tensor(
        [
            _feature_vector(_observation_payload(sample.model_input), config.observation_width)
            for sample in samples
        ],
        dtype=torch_module.float32,
        device=device,
    )
    candidates = torch_module.zeros(
        (len(samples), max_candidates, config.candidate_width),
        dtype=torch_module.float32,
        device=device,
    )
    candidate_mask = torch_module.zeros(
        (len(samples), max_candidates), dtype=torch_module.bool, device=device
    )
    target_indices = torch_module.tensor(
        [sample.target_index for sample in samples],
        dtype=torch_module.long,
        device=device,
    )
    for item_index, sample in enumerate(samples):
        for candidate_index, candidate in enumerate(sample.candidate_feature_views):
            candidates[item_index, candidate_index] = torch_module.tensor(
                _feature_vector(candidate, config.candidate_width),
                dtype=torch_module.float32,
                device=device,
            )
            candidate_mask[item_index, candidate_index] = bool(
                candidate.get("affordable", True)
            )
    for item_index, target_index in enumerate(target_indices.tolist()):
        if not bool(candidate_mask[item_index, target_index].item()):
            raise ValueError("selected target is not executable in the supplied candidate batch")
    return C1_06TensorBatch(observation, candidates, candidate_mask, target_indices)


def tensorize_live_input(
    model_input: Mapping[str, Any],
    candidate_feature_views: Sequence[Mapping[str, Any]],
    *,
    torch_module: Any,
    device: Any,
    config: C1_06ModelConfigV1,
) -> C1_06InferenceTensorBatch:
    """Use the exact C1_06 feature encoding for live candidates without inventing labels."""

    if not isinstance(model_input, Mapping):
        raise ValueError("C1_06 live model input must be an object")
    if (
        isinstance(candidate_feature_views, (str, bytes, bytearray))
        or not isinstance(candidate_feature_views, Sequence)
        or not candidate_feature_views
    ):
        raise ValueError("C1_06 live candidate features must be a non-empty sequence")
    if any(not isinstance(candidate, Mapping) for candidate in candidate_feature_views):
        raise ValueError("C1_06 live candidate features must be objects")
    observation = torch_module.tensor(
        [_feature_vector(_observation_payload(model_input), config.observation_width)],
        dtype=torch_module.float32,
        device=device,
    )
    candidates = torch_module.tensor(
        [
            _feature_vector(candidate, config.candidate_width)
            for candidate in candidate_feature_views
        ],
        dtype=torch_module.float32,
        device=device,
    ).unsqueeze(0)
    return C1_06InferenceTensorBatch(observation=observation, candidates=candidates)


class FeedForwardCandidateScorer:
    """Stateless shared MLP; wraps a torch.nn.Module without importing torch at package load."""

    def __new__(cls, config: C1_06ModelConfigV1, *, device: Any) -> Any:
        torch = __import__("torch")
        nn = torch.nn

        class _Scorer(nn.Module):
            def __init__(self) -> None:
                super().__init__()
                layers: list[Any] = []
                input_width = config.observation_width + config.candidate_width
                for layer_index in range(config.hidden_layers):
                    layers.append(nn.Linear(input_width if layer_index == 0 else config.hidden_width, config.hidden_width))
                    layers.append(nn.ReLU())
                layers.append(nn.Linear(config.hidden_width, 1))
                self.network = nn.Sequential(*layers)

            def forward(self, observation: Any, candidates: Any) -> Any:
                if observation.ndim != 2 or candidates.ndim != 3:
                    raise ValueError("C1_06 tensors have invalid rank")
                if observation.shape[0] != candidates.shape[0]:
                    raise ValueError("observation/candidate batch sizes differ")
                batch_size, candidate_count, _ = candidates.shape
                repeated = observation.unsqueeze(1).expand(-1, candidate_count, -1)
                joined = torch.cat((repeated, candidates), dim=-1)
                return self.network(joined.reshape(batch_size * candidate_count, -1)).reshape(
                    batch_size, candidate_count
                )

        model = _Scorer().to(device)
        return model


def candidate_selection_loss(scores: Any, target_indices: Any, candidate_mask: Any) -> Any:
    torch = __import__("torch")
    if scores.ndim != 2 or target_indices.ndim != 1 or candidate_mask.shape != scores.shape:
        raise ValueError("C1_06 loss tensors have incompatible shapes")
    if target_indices.shape[0] != scores.shape[0]:
        raise ValueError("C1_06 target batch size differs from scores")
    for row, target in enumerate(target_indices.tolist()):
        if target < 0 or target >= scores.shape[1] or not bool(candidate_mask[row, target].item()):
            raise ValueError("C1_06 target is absent from executable candidate mask")
    masked_scores = scores.masked_fill(~candidate_mask, torch.finfo(scores.dtype).min)
    loss = torch.nn.functional.cross_entropy(masked_scores, target_indices)
    if not bool(torch.isfinite(loss).item()):
        raise ValueError("C1_06 loss is not finite")
    return loss


def _evaluate_samples(
    model: Any,
    samples: Sequence[C1_06TrainingSample],
    *,
    config: C1_06ModelConfigV1,
    torch_module: Any,
    device: Any,
    batch_size: int,
) -> tuple[float, float, int, int]:
    losses: list[float] = []
    correct = 0
    total = 0
    nonfinite_scores = 0
    model.eval()
    with torch_module.no_grad():
        for start in range(0, len(samples), batch_size):
            batch = tensorize_samples(
                samples[start : start + batch_size],
                torch_module=torch_module,
                device=device,
                config=config,
            )
            scores = model(batch.observation, batch.candidates)
            if not bool(torch_module.isfinite(scores).all().item()):
                nonfinite_scores += 1
                continue
            loss = candidate_selection_loss(scores, batch.target_indices, batch.candidate_mask)
            losses.append(float(loss.detach().cpu().item()))
            predictions = scores.masked_fill(~batch.candidate_mask, torch_module.finfo(scores.dtype).min).argmax(dim=1)
            correct += int((predictions == batch.target_indices).sum().item())
            total += len(batch.target_indices)
    if not losses:
        raise ValueError("C1_06 evaluation produced no finite losses")
    return sum(losses) / len(losses), correct / total, total, nonfinite_scores


def run_training_loop(
    model: Any,
    samples: Sequence[C1_06TrainingSample],
    *,
    validation_samples: Sequence[C1_06TrainingSample],
    config: C1_06ModelConfigV1,
    torch_module: Any,
    device: Any,
    optimizer_steps: int,
    batch_size: int,
    learning_rate: float,
) -> C1_06TrainingResult:
    if not samples or not validation_samples:
        raise ValueError("C1_06 training and validation samples must not be empty")
    if optimizer_steps <= 0 or batch_size <= 0 or learning_rate <= 0:
        raise ValueError("C1_06 training configuration is invalid")
    initial_loss, initial_accuracy, _, initial_nonfinite_scores = _evaluate_samples(
        model,
        samples,
        config=config,
        torch_module=torch_module,
        device=device,
        batch_size=batch_size,
    )
    optimizer = torch_module.optim.Adam(model.parameters(), lr=learning_rate)
    nonfinite_losses = 0
    nonfinite_gradients = 0
    start_time = time.perf_counter()
    model.train()
    for step in range(optimizer_steps):
        start = (step * batch_size) % len(samples)
        indices = [(start + offset) % len(samples) for offset in range(batch_size)]
        batch = tensorize_samples(
            [samples[index] for index in indices],
            torch_module=torch_module,
            device=device,
            config=config,
        )
        optimizer.zero_grad(set_to_none=True)
        scores = model(batch.observation, batch.candidates)
        if not bool(torch_module.isfinite(scores).all().item()):
            raise ValueError("C1_06 scores are not finite")
        loss = candidate_selection_loss(scores, batch.target_indices, batch.candidate_mask)
        if not bool(torch_module.isfinite(loss).item()):
            nonfinite_losses += 1
            raise ValueError("C1_06 loss is not finite")
        loss.backward()
        if any(parameter.grad is None or not bool(torch_module.isfinite(parameter.grad).all().item()) for parameter in model.parameters()):
            nonfinite_gradients += 1
            raise ValueError("C1_06 gradients are not finite")
        optimizer.step()
        if getattr(device, "type", None) == "cuda":
            torch_module.cuda.synchronize(device)
    wall_seconds = time.perf_counter() - start_time
    final_loss, train_accuracy, _, final_nonfinite_scores = _evaluate_samples(
        model,
        samples,
        config=config,
        torch_module=torch_module,
        device=device,
        batch_size=batch_size,
    )
    validation_loss, validation_accuracy, _, validation_nonfinite_scores = _evaluate_samples(
        model,
        validation_samples,
        config=config,
        torch_module=torch_module,
        device=device,
        batch_size=batch_size,
    )
    if getattr(device, "type", None) == "cuda":
        max_allocated = int(torch_module.cuda.max_memory_allocated())
        max_reserved = int(torch_module.cuda.max_memory_reserved())
    else:
        max_allocated = 0
        max_reserved = 0
    return C1_06TrainingResult(
        initial_train_loss=initial_loss,
        initial_train_top1_agreement=initial_accuracy,
        final_train_loss=final_loss,
        validation_loss=validation_loss,
        train_top1_agreement=train_accuracy,
        validation_top1_agreement=validation_accuracy,
        optimizer_steps=optimizer_steps,
        training_examples_processed=optimizer_steps * batch_size,
        wall_seconds=wall_seconds,
        nonfinite_scores=initial_nonfinite_scores + final_nonfinite_scores + validation_nonfinite_scores,
        nonfinite_losses=nonfinite_losses,
        nonfinite_gradients=nonfinite_gradients,
        cuda_max_memory_allocated_bytes=max_allocated,
        cuda_max_memory_reserved_bytes=max_reserved,
    )


def validate_tiny_overfit(result: C1_06TrainingResult) -> None:
    values = (
        result.initial_train_loss,
        result.final_train_loss,
        result.initial_train_top1_agreement,
        result.train_top1_agreement,
    )
    if not all(math.isfinite(value) for value in values):
        raise ValueError("C1_06 tiny overfit metrics are not finite")
    if result.initial_train_loss <= 0:
        raise ValueError("C1_06 tiny overfit initial loss must be positive")
    if result.final_train_loss >= result.initial_train_loss * 0.5:
        raise ValueError("C1_06 tiny overfit did not strongly decrease loss")
    if result.train_top1_agreement <= result.initial_train_top1_agreement:
        raise ValueError("C1_06 tiny overfit did not increase agreement")


def build_feed_forward_checkpoint_identity(
    *,
    source_dataset_identity: str,
    label_artifact_id: str,
    labels_content_digest: str,
    manifest_content_digest: str,
    model_config_digest: str,
    source_commit: str,
) -> dict[str, Any]:
    """Build the semantic identity fields shared by the later strict manifest writer."""

    return {
        "policyArtifactKind": "FEED_FORWARD_POLICY",
        "modelArchitectureIdentity": MODEL_ARCHITECTURE_IDENTITY,
        "modelConfigDigest": model_config_digest,
        "sourceDatasetIdentity": source_dataset_identity,
        "labelArtifactId": label_artifact_id,
        "labelsContentDigest": labels_content_digest,
        "labelManifestContentDigest": manifest_content_digest,
        "modelImplementationSourceCommit": source_commit,
        "recurrentSequenceContractIdentity": "NONE_FOR_FEED_FORWARD",
    }


def _training_run_identity(
    *,
    source_dataset_identity: str,
    label_artifact_id: str,
    labels_content_digest: str,
    manifest_content_digest: str,
    model_config_digest: str,
) -> str:
    return sha256_hex(
        canonical_bytes(
            {
                "schema": "argentum-ml-c1-06-training-run@v1",
                "sourceDatasetIdentity": source_dataset_identity,
                "labelArtifactId": label_artifact_id,
                "labelsContentDigest": labels_content_digest,
                "labelManifestContentDigest": manifest_content_digest,
                "modelConfigDigest": model_config_digest,
            }
        )
    )


def _checkpoint_id(manifest: Mapping[str, Any]) -> str:
    payload = {
        key: value
        for key, value in manifest.items()
        if key not in {"checkpointId", "version"}
    }
    payload["schema"] = "argentum-ml-checkpoint-id@v1"
    return sha256_hex(canonical_bytes(payload))


def save_c1_06_checkpoint(
    model: Any,
    output_dir: Path | str,
    *,
    source_dataset_identity: str,
    label_artifact_id: str,
    labels_content_digest: str,
    manifest_content_digest: str,
    source_commit: str,
    config: C1_06ModelConfigV1,
) -> C1_06CheckpointResult:
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / "manifest.json"
    weight_path = output_dir / "weights.safetensors"
    if manifest_path.exists() or weight_path.exists():
        raise ValueError("C1_06 checkpoint output already exists")
    weight_artifact = save_state_dict(model.state_dict(), weight_path)
    training_run_identity = _training_run_identity(
        source_dataset_identity=source_dataset_identity,
        label_artifact_id=label_artifact_id,
        labels_content_digest=labels_content_digest,
        manifest_content_digest=manifest_content_digest,
        model_config_digest=config.digest,
    )
    manifest: dict[str, Any] = {
        "version": 1,
        "manifestContractIdentity": "argentum-ml-checkpoint-manifest@v1",
        "policyArtifactKind": "FEED_FORWARD_POLICY",
        "modelImplementationIdentity": {
            "implementation": MODEL_IMPLEMENTATION_IDENTITY,
            "sourceCommit": source_commit,
        },
        "modelArchitectureIdentity": config.architecture_identity,
        "modelConfigDigest": config.digest,
        "modelFacingContractIdentity": "argentum-ml-model-facing-decision-sample@v1",
        "candidateScoringContractIdentity": "argentum-ml-model-facing-decision-sample@v1",
        "splitContractIdentity": "argentum-ml-dataset-split@v1",
        "sourceDatasetIdentity": source_dataset_identity,
        "recurrentSequenceContractIdentity": "NONE_FOR_FEED_FORWARD",
        "vocabularyIdentity": None,
        "weightArtifactIdentity": {
            "container": SAFETENSORS_CONTAINER_IDENTITY,
            "artifact": "c1-06-feed-forward.weights",
        },
        "weightContentDigest": weight_artifact.content_digest,
        "inferenceContractIdentity": "argentum-ml-inference@v1",
        "selectionContractIdentity": "argentum-ml-policy-selection@v2",
        "requiredNumericProfileClass": "C1_REFERENCE_NUMERIC_PROFILE",
        "policyRngContractIdentity": "argentum-ml-policy-tie-rng@v1",
        "trainingRecipeIdentity": TRAINING_RECIPE_IDENTITY,
        "trainingRunIdentity": training_run_identity,
        "parentCheckpointIdentity": None,
        "teacherBootstrapProvenance": None,
        "checkpointId": "0" * 64,
    }
    manifest["checkpointId"] = _checkpoint_id(manifest)
    validated = ArgentumCheckpointManifestV1.from_dict(manifest)
    manifest_path.write_bytes(canonical_bytes(manifest))
    return C1_06CheckpointResult(manifest_path, weight_path, validated)


def load_c1_06_checkpoint(
    model: Any,
    manifest_path: Path | str,
    weight_path: Path | str,
    *,
    expected_source_dataset_identity: str,
    expected_label_artifact_id: str,
    expected_labels_content_digest: str,
    expected_manifest_content_digest: str,
    expected_source_commit: str,
    config: C1_06ModelConfigV1,
) -> ArgentumCheckpointManifestV1:
    manifest = ArgentumCheckpointManifestV1.from_path(Path(manifest_path))
    manifest_data = manifest.to_dict()
    implementation = manifest_data["modelImplementationIdentity"]
    if manifest_data["sourceDatasetIdentity"] != expected_source_dataset_identity:
        raise C1_06DataGateError("checkpoint source dataset identity differs from expected")
    if manifest_data["modelArchitectureIdentity"] != config.architecture_identity:
        raise C1_06DataGateError("checkpoint model architecture identity differs from expected")
    if manifest_data["modelConfigDigest"] != config.digest:
        raise C1_06DataGateError("checkpoint model config digest differs from expected")
    if manifest_data["trainingRecipeIdentity"] != TRAINING_RECIPE_IDENTITY:
        raise C1_06DataGateError("checkpoint training recipe identity differs from expected")
    if implementation["implementation"] != MODEL_IMPLEMENTATION_IDENTITY:
        raise C1_06DataGateError("checkpoint model implementation identity differs from expected")
    if implementation["sourceCommit"] != expected_source_commit:
        raise C1_06DataGateError("checkpoint source commit differs from expected")
    expected_training_run_identity = _training_run_identity(
        source_dataset_identity=expected_source_dataset_identity,
        label_artifact_id=expected_label_artifact_id,
        labels_content_digest=expected_labels_content_digest,
        manifest_content_digest=expected_manifest_content_digest,
        model_config_digest=config.digest,
    )
    if manifest_data["trainingRunIdentity"] != expected_training_run_identity:
        raise C1_06DataGateError("checkpoint training run identity differs from expected provenance")
    state_dict = load_state_dict(Path(weight_path), manifest)
    model.load_state_dict(state_dict, strict=True)
    model.eval()
    return manifest


def _current_source_commit() -> str:
    try:
        value = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=Path.cwd(),
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise C1_06DataGateError("C1_06 could not resolve its source commit") from exc
    dirty = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=no"],
        cwd=Path.cwd(),
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if dirty:
        raise C1_06DataGateError("C1_06 requires a clean committed source tree")
    if len(value) != 40 or any(character not in "0123456789abcdef" for character in value):
        raise C1_06DataGateError("C1_06 source commit is malformed")
    return value


def run_c1_06_gpu_smoke(
    *,
    label_root: Path | str,
    source_artifact_root: Path | str,
    checkpoint_output_dir: Path | str,
    train_limit: int = 2048,
    validation_limit: int = 512,
    tiny_limit: int = 64,
    tiny_steps: int = 40,
    optimizer_steps: int = 100,
    batch_size: int = 64,
    learning_rate: float = 0.001,
) -> dict[str, Any]:
    torch = __import__("torch")
    device = require_cuda_device(torch)
    kernel = run_cuda_kernel_probe(torch)
    torch.manual_seed(20260915)
    torch.cuda.manual_seed_all(20260915)
    torch.use_deterministic_algorithms(True)
    config = C1_06ModelConfigV1.reference()
    data = load_c1_05_training_view(
        label_root=label_root,
        source_artifact_root=source_artifact_root,
        expected_source_artifact_id=C1_05_SOURCE_ARTIFACT_ID,
        expected_label_artifact_id=C1_05_LABEL_ARTIFACT_ID,
        expected_labels_content_digest=C1_05_LABELS_CONTENT_DIGEST,
        expected_manifest_content_digest=C1_05_MANIFEST_CONTENT_DIGEST,
        train_limit=train_limit,
        validation_limit=validation_limit,
    )
    train_samples = [sample for sample in data.samples if sample.partition == "TRAIN"]
    validation_samples = [sample for sample in data.samples if sample.partition == "VALIDATION"]
    if not train_samples or not validation_samples:
        raise C1_06DataGateError("C1_06 requires non-empty TRAIN and VALIDATION views")
    tiny_samples = train_samples[: min(tiny_limit, len(train_samples))]
    tiny_validation = validation_samples[: min(tiny_limit, len(validation_samples))]
    tiny_model = FeedForwardCandidateScorer(config, device=device)
    tiny_result = run_training_loop(
        tiny_model,
        tiny_samples,
        validation_samples=tiny_validation,
        config=config,
        torch_module=torch,
        device=device,
        optimizer_steps=tiny_steps,
        batch_size=min(batch_size, len(tiny_samples)),
        learning_rate=learning_rate,
    )
    validate_tiny_overfit(tiny_result)
    torch.manual_seed(20260915)
    torch.cuda.manual_seed_all(20260915)
    model = FeedForwardCandidateScorer(config, device=device)
    torch.cuda.reset_peak_memory_stats()
    result = run_training_loop(
        model,
        train_samples,
        validation_samples=validation_samples,
        config=config,
        torch_module=torch,
        device=device,
        optimizer_steps=optimizer_steps,
        batch_size=batch_size,
        learning_rate=learning_rate,
    )
    if any(parameter.device.type != "cuda" or parameter.device.index != 0 for parameter in model.parameters()):
        raise C1_06GpuGateError("C1_06 model parameters are not entirely on cuda:0")
    fixed_samples = validation_samples[: min(batch_size, len(validation_samples))]
    fixed_batch = tensorize_samples(
        fixed_samples,
        torch_module=torch,
        device=device,
        config=config,
    )
    model.eval()
    with torch.no_grad():
        scores_before = model(fixed_batch.observation, fixed_batch.candidates).detach().cpu().clone()
    source_commit = _current_source_commit()
    checkpoint = save_c1_06_checkpoint(
        model,
        checkpoint_output_dir,
        source_dataset_identity=data.label_manifest["sourceDerivedArtifactId"],
        label_artifact_id=data.label_manifest["labelArtifactId"],
        labels_content_digest=data.label_manifest["labelsContentDigest"],
        manifest_content_digest=data.label_manifest["manifestContentDigest"],
        source_commit=source_commit,
        config=config,
    )
    reloaded = FeedForwardCandidateScorer(config, device=device)
    reloaded_manifest = load_c1_06_checkpoint(
        reloaded,
        checkpoint.manifest_path,
        checkpoint.weight_path,
        expected_source_dataset_identity=data.label_manifest["sourceDerivedArtifactId"],
        expected_label_artifact_id=data.label_manifest["labelArtifactId"],
        expected_labels_content_digest=data.label_manifest["labelsContentDigest"],
        expected_manifest_content_digest=data.label_manifest["manifestContentDigest"],
        expected_source_commit=source_commit,
        config=config,
    )
    with torch.no_grad():
        scores_after = reloaded(fixed_batch.observation, fixed_batch.candidates).detach().cpu()
    if not torch.equal(scores_before, scores_after):
        raise ValueError("C1_06 post-reload inference is not deterministic")
    torch.cuda.synchronize(device)
    return {
        "sourceDerivedArtifactId": data.label_manifest["sourceDerivedArtifactId"],
        "labelArtifactId": data.label_manifest["labelArtifactId"],
        "labelsContentDigest": data.label_manifest["labelsContentDigest"],
        "manifestContentDigest": data.label_manifest["manifestContentDigest"],
        "trainDecisionsUsed": len(train_samples),
        "validationDecisionsUsed": len(validation_samples),
        "testRowsConsumed": 0,
        "testLabelsConsumed": 0,
        "testBatchesConsumed": 0,
        "teacherCalls": 0,
        "newLabelsGenerated": 0,
        "modelArchitectureIdentity": config.architecture_identity,
        "modelConfigDigest": config.digest,
        "trainableParameters": sum(parameter.numel() for parameter in model.parameters()),
        "optimizerSteps": result.optimizer_steps,
        "trainingExamplesProcessed": result.training_examples_processed,
        "trainingDevice": str(device),
        "cudaKernelPeakMemoryBytes": kernel.peak_memory_bytes,
        "cudaMaxMemoryAllocatedBytes": result.cuda_max_memory_allocated_bytes,
        "cudaMaxMemoryReservedBytes": result.cuda_max_memory_reserved_bytes,
        "tinyOverfitExamples": len(tiny_samples),
        "tinyOverfitInitialLoss": tiny_result.initial_train_loss,
        "tinyOverfitFinalLoss": tiny_result.final_train_loss,
        "tinyOverfitInitialAccuracy": tiny_result.initial_train_top1_agreement,
        "tinyOverfitFinalAccuracy": tiny_result.train_top1_agreement,
        "trainInitialLoss": result.initial_train_loss,
        "trainFinalLoss": result.final_train_loss,
        "trainTop1Agreement": result.train_top1_agreement,
        "validationLoss": result.validation_loss,
        "validationTop1Agreement": result.validation_top1_agreement,
        "nonfiniteScores": result.nonfinite_scores,
        "nonfiniteLosses": result.nonfinite_losses,
        "nonfiniteGradients": result.nonfinite_gradients,
        "checkpointManifestPath": str(checkpoint.manifest_path),
        "checkpointWeightPath": str(checkpoint.weight_path),
        "checkpointId": reloaded_manifest.checkpoint_id,
        "weightContentDigest": reloaded_manifest.weight_content_digest,
        "checkpointStrictReload": "PASS",
        "postReloadInference": "PASS",
        "deterministicInference": "PASS",
        "trainingWallSeconds": result.wall_seconds,
        "decisionsPerSecond": result.training_examples_processed / max(result.wall_seconds, 1e-9),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the C1_06 CUDA-only bounded learner smoke")
    parser.add_argument("--label-root", required=True, type=Path)
    parser.add_argument("--source-artifact-root", required=True, type=Path)
    parser.add_argument("--checkpoint-output-dir", required=True, type=Path)
    parser.add_argument("--train-limit", type=int, default=2048)
    parser.add_argument("--validation-limit", type=int, default=512)
    parser.add_argument("--tiny-limit", type=int, default=64)
    parser.add_argument("--tiny-steps", type=int, default=40)
    parser.add_argument("--optimizer-steps", type=int, default=100)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--learning-rate", type=float, default=0.001)
    args = parser.parse_args()
    print(json.dumps(run_c1_06_gpu_smoke(**vars(args)), sort_keys=True, indent=2))


if __name__ == "__main__":
    main()
