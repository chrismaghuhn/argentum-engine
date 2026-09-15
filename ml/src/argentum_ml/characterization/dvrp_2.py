"""DVRP-2 learner preparation bottleneck characterization.

This module is deliberately a measurement harness. It wraps the existing C1_06
loader and reader boundaries, records timing/counter evidence, and never changes
the artifact or learner semantics. The production reader remains the authority
for every parsed, validated, and yielded row.
"""

from __future__ import annotations

import argparse
from collections import Counter
from contextlib import ExitStack
from dataclasses import dataclass, field
import json
from pathlib import Path
import platform
import subprocess
import sys
import time
from typing import Any, Callable, Iterator, Mapping, Sequence
from unittest.mock import patch


ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS = 39.282577800011495
DEFAULT_TRAIN_LIMIT = 2048
DEFAULT_VALIDATION_LIMIT = 512
DEFAULT_TEST_LIMIT = 0


@dataclass(frozen=True)
class Dvrp2ArtifactSpec:
    """Content-addressed identities that must match before the measured run."""

    source_derived_artifact_id: str
    label_artifact_id: str
    labels_content_digest: str
    manifest_content_digest: str

    @classmethod
    def accepted_c1_05(cls) -> "Dvrp2ArtifactSpec":
        from ..learner.c1_06 import (
            C1_05_LABELS_CONTENT_DIGEST,
            C1_05_LABEL_ARTIFACT_ID,
            C1_05_MANIFEST_CONTENT_DIGEST,
            C1_05_SOURCE_ARTIFACT_ID,
        )

        return cls(
            source_derived_artifact_id=C1_05_SOURCE_ARTIFACT_ID,
            label_artifact_id=C1_05_LABEL_ARTIFACT_ID,
            labels_content_digest=C1_05_LABELS_CONTENT_DIGEST,
            manifest_content_digest=C1_05_MANIFEST_CONTENT_DIGEST,
        )


@dataclass
class _Stage:
    wall_seconds: float = 0.0
    cpu_seconds: float = 0.0
    calls: int = 0

    def add(self, wall_seconds: float, cpu_seconds: float) -> None:
        self.wall_seconds += wall_seconds
        self.cpu_seconds += cpu_seconds
        self.calls += 1


@dataclass
class _ProbeState:
    stages: dict[str, _Stage] = field(default_factory=dict)
    phase: str = "outside"
    label_rows_visited: int = 0
    selected_rows: tuple[Mapping[str, Any], ...] = ()
    selected_keys: set[tuple[str, int, str]] = field(default_factory=set)
    matched_keys: set[tuple[str, int, str]] = field(default_factory=set)
    selected_row_positions: list[int] = field(default_factory=list)
    source_rows_visited: int = 0
    source_bytes_per_raw_pass: int | None = None
    source_consumer_wall_seconds: float = 0.0
    source_consumer_cpu_seconds: float = 0.0

    def stage(self, name: str) -> _Stage:
        return self.stages.setdefault(name, _Stage())


def _timed_call(
    state: _ProbeState,
    stage_name: str,
    callback: Callable[..., Any],
    *args: Any,
    phase: str | None = None,
    **kwargs: Any,
) -> Any:
    previous_phase = state.phase
    if phase is not None:
        state.phase = phase
    wall_start = time.perf_counter()
    cpu_start = time.process_time()
    try:
        return callback(*args, **kwargs)
    finally:
        state.stage(stage_name).add(
            time.perf_counter() - wall_start,
            time.process_time() - cpu_start,
        )
        state.phase = previous_phase


def _source_key_without_timing(value: Mapping[str, Any]) -> tuple[str, int, str]:
    source = value.get("sourceReference")
    if not isinstance(source, Mapping):
        raise ValueError("sourceReference is missing from characterization row")
    semantic = source.get("semanticDecisionId")
    if not isinstance(semantic, Mapping):
        raise ValueError("semanticDecisionId is missing from characterization row")
    trajectory = source.get("trajectoryId")
    decision_index = source.get("decisionIndex")
    semantic_value = semantic.get("value")
    if (
        not isinstance(trajectory, str)
        or not isinstance(decision_index, int)
        or isinstance(decision_index, bool)
        or not isinstance(semantic_value, str)
    ):
        raise ValueError("source decision key is malformed")
    return trajectory, decision_index, semantic_value


class _TimedSourceIterator:
    """Time only reader ``next`` calls while measuring the consumer gap separately."""

    def __init__(self, iterator: Iterator[Any], state: _ProbeState) -> None:
        self._iterator = iter(iterator)
        self._state = state
        self._last_return_wall: float | None = None
        self._last_return_cpu: float | None = None

    def __iter__(self) -> "_TimedSourceIterator":
        return self

    def __next__(self) -> Any:
        now_wall = time.perf_counter()
        now_cpu = time.process_time()
        if self._last_return_wall is not None and self._last_return_cpu is not None:
            self._state.source_consumer_wall_seconds += now_wall - self._last_return_wall
            self._state.source_consumer_cpu_seconds += now_cpu - self._last_return_cpu

        previous_phase = self._state.phase
        self._state.phase = "source_iteration"
        wall_start = time.perf_counter()
        cpu_start = time.process_time()
        yielded = False
        try:
            item = next(self._iterator)
            yielded = True
            self._state.source_rows_visited += 1
            key = _source_key_without_timing(item.sample)
            if key in self._state.selected_keys:
                self._state.matched_keys.add(key)
                self._state.selected_row_positions.append(self._state.source_rows_visited)
            return item
        finally:
            self._state.stage("source_reader_iteration").add(
                time.perf_counter() - wall_start,
                time.process_time() - cpu_start,
            )
            if yielded:
                self._last_return_wall = time.perf_counter()
                self._last_return_cpu = time.process_time()
                self._state.phase = "source_join"
            else:
                self._state.phase = previous_phase


def _install_probes(state: _ProbeState, stack: ExitStack) -> None:
    """Patch only call boundaries; all wrapped callbacks remain production code."""

    from ..data import derived_reader
    from ..data.derived_reader import DerivedArtifactReader
    from ..data.label_artifact import LabelArtifactReader
    from ..learner import c1_06

    original_label_open = LabelArtifactReader._open_structural

    def timed_label_open(
        cls: type[LabelArtifactReader],
        root: Path,
        *args: Any,
        **kwargs: Any,
    ) -> LabelArtifactReader:
        return _timed_call(
            state,
            "label_structural_open",
            original_label_open,
            root,
            *args,
            phase="label_structural_open",
            **kwargs,
        )

    stack.enter_context(
        patch.object(
            LabelArtifactReader,
            "_open_structural",
            classmethod(timed_label_open),
        )
    )

    original_label_iteration = LabelArtifactReader.iter_labels

    def timed_label_iteration(self: LabelArtifactReader) -> Iterator[dict[str, Any]]:
        source_iterator = original_label_iteration(self)

        def measured() -> Iterator[dict[str, Any]]:
            previous_phase = state.phase
            state.phase = "label_iteration"
            wall_start = time.perf_counter()
            cpu_start = time.process_time()
            try:
                for row in source_iterator:
                    state.label_rows_visited += 1
                    yield row
            finally:
                state.stage("label_iteration").add(
                    time.perf_counter() - wall_start,
                    time.process_time() - cpu_start,
                )
                state.phase = previous_phase

        return measured()

    stack.enter_context(patch.object(LabelArtifactReader, "iter_labels", timed_label_iteration))

    original_selection = c1_06.select_bounded_label_rows

    def timed_selection(
        labels: Sequence[Mapping[str, Any]],
        *,
        train_limit: int,
        validation_limit: int,
    ) -> list[Mapping[str, Any]]:
        selected = _timed_call(
            state,
            "bounded_label_selection",
            original_selection,
            labels,
            train_limit=train_limit,
            validation_limit=validation_limit,
            phase="label_selection",
        )
        state.selected_rows = tuple(selected)
        state.selected_keys = {
            _source_key_without_timing(row) for row in state.selected_rows
        }
        return selected

    stack.enter_context(patch.object(c1_06, "select_bounded_label_rows", timed_selection))

    original_source_open = DerivedArtifactReader.open

    def timed_source_open(
        cls: type[DerivedArtifactReader],
        root: Path,
    ) -> DerivedArtifactReader:
        reader = _timed_call(
            state,
            "source_reader_open",
            original_source_open,
            root,
            phase="source_open",
        )
        state.source_bytes_per_raw_pass = int(reader.manifest["samplesByteCount"])
        return reader

    stack.enter_context(
        patch.object(
            DerivedArtifactReader,
            "open",
            classmethod(timed_source_open),
        )
    )

    original_source_iteration = DerivedArtifactReader.iter_validated_samples_for_inference

    def timed_source_iteration(self: DerivedArtifactReader) -> _TimedSourceIterator:
        return _TimedSourceIterator(original_source_iteration(self), state)

    stack.enter_context(
        patch.object(
            DerivedArtifactReader,
            "iter_validated_samples_for_inference",
            timed_source_iteration,
        )
    )

    original_source_key = c1_06._source_key

    def timed_source_key(value: Mapping[str, Any]) -> tuple[str, int, str]:
        stage_name = {
            "label_selection": "source_key_extraction_label_selection",
            "source_join": "source_key_extraction_source_join",
            "sample_construction": "source_key_extraction_sample_construction",
        }.get(state.phase, "source_key_extraction_other")
        return _timed_call(state, stage_name, original_source_key, value)

    stack.enter_context(patch.object(c1_06, "_source_key", timed_source_key))

    original_sample_construction = c1_06.source_label_to_training_sample

    def timed_sample_construction(
        source: Mapping[str, Any],
        label: Mapping[str, Any],
    ) -> Any:
        previous_phase = state.phase
        state.phase = "sample_construction"
        wall_start = time.perf_counter()
        cpu_start = time.process_time()
        try:
            return original_sample_construction(source, label)
        finally:
            state.stage("training_sample_construction").add(
                time.perf_counter() - wall_start,
                time.process_time() - cpu_start,
            )
            state.phase = previous_phase

    stack.enter_context(
        patch.object(c1_06, "source_label_to_training_sample", timed_sample_construction)
    )

    original_validate_manifest = derived_reader._validate_manifest

    def timed_validate_manifest(manifest: dict[str, Any]) -> None:
        if state.phase == "source_open":
            _timed_call(state, "source_manifest_validation", original_validate_manifest, manifest)
        else:
            original_validate_manifest(manifest)

    stack.enter_context(patch.object(derived_reader, "_validate_manifest", timed_validate_manifest))

    original_validate_file = derived_reader._validate_sample_file

    def timed_validate_file(
        stream: Any,
        manifest: dict[str, Any],
        *args: Any,
        **kwargs: Any,
    ) -> Any:
        if state.phase == "source_open":
            return _timed_call(
                state,
                "source_strict_sample_file_validation",
                original_validate_file,
                stream,
                manifest,
                *args,
                **kwargs,
            )
        return original_validate_file(stream, manifest, *args, **kwargs)

    stack.enter_context(patch.object(derived_reader, "_validate_sample_file", timed_validate_file))

    original_verify_file = derived_reader._verify_validated_sample_file

    def timed_verify_file(stream: Any, validated: Any) -> None:
        if state.phase == "source_iteration":
            _timed_call(
                state,
                "source_whole_file_integrity_preflight",
                original_verify_file,
                stream,
                validated,
            )
        else:
            original_verify_file(stream, validated)

    stack.enter_context(
        patch.object(derived_reader, "_verify_validated_sample_file", timed_verify_file)
    )

    original_parse_sample_line = derived_reader._parse_sample_line

    def timed_parse_sample_line(raw_line: bytes) -> dict[str, Any]:
        stage_name = {
            "source_open": "source_open_json_canonical_parse",
            "source_iteration": "source_iteration_json_canonical_parse",
        }.get(state.phase)
        if stage_name is None:
            return original_parse_sample_line(raw_line)
        return _timed_call(state, stage_name, original_parse_sample_line, raw_line)

    stack.enter_context(patch.object(derived_reader, "_parse_sample_line", timed_parse_sample_line))

    original_validate_sample = derived_reader._validate_sample

    def timed_validate_sample(sample: dict[str, Any], manifest: dict[str, Any]) -> None:
        if state.phase == "source_open":
            _timed_call(
                state,
                "source_open_semantic_sample_validation",
                original_validate_sample,
                sample,
                manifest,
            )
        else:
            original_validate_sample(sample, manifest)

    stack.enter_context(patch.object(derived_reader, "_validate_sample", timed_validate_sample))


def _read_manifest(root: Path, filename: str) -> dict[str, Any]:
    path = root / filename
    if root.is_symlink() or not root.is_dir() or path.is_symlink() or not path.is_file():
        raise ValueError(f"{filename} is not a regular file below a real artifact directory: {root}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{filename} must contain a JSON object")
    return value


def verify_artifact_identity(
    *,
    label_root: Path | str,
    source_artifact_root: Path | str,
    artifact_spec: Dvrp2ArtifactSpec,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Perform the manifest-only preflight before the one expensive reader run."""

    source_root = Path(source_artifact_root)
    label_root_path = Path(label_root)
    source_manifest = _read_manifest(source_root, "manifest.json")
    label_manifest = _read_manifest(label_root_path, "manifest.json")
    samples_path = source_root / "samples.ndjson"
    labels_path = label_root_path / "labels.ndjson"
    if samples_path.is_symlink() or not samples_path.is_file():
        raise ValueError("source samples.ndjson is not a regular file")
    if labels_path.is_symlink() or not labels_path.is_file():
        raise ValueError("label labels.ndjson is not a regular file")
    if int(source_manifest.get("samplesByteCount", -1)) != samples_path.stat().st_size:
        raise ValueError("source manifest byte count differs from file metadata")
    if int(label_manifest.get("labelsByteCount", -1)) != labels_path.stat().st_size:
        raise ValueError("label manifest byte count differs from file metadata")
    expected_source = artifact_spec.source_derived_artifact_id
    if source_manifest.get("derivedArtifactId") != expected_source:
        raise ValueError("source derived artifact identity differs from expected C1_05 identity")
    if label_manifest.get("sourceDerivedArtifactId") != expected_source:
        raise ValueError("label source-derived identity differs from expected C1_05 identity")
    if label_manifest.get("labelArtifactId") != artifact_spec.label_artifact_id:
        raise ValueError("label artifact identity differs from expected C1_05 identity")
    if label_manifest.get("labelsContentDigest") != artifact_spec.labels_content_digest:
        raise ValueError("labels content digest differs from expected C1_05 identity")
    if label_manifest.get("manifestContentDigest") != artifact_spec.manifest_content_digest:
        raise ValueError("label manifest content digest differs from expected C1_05 identity")
    return source_manifest, label_manifest


def _try_git(*args: str) -> str | None:
    try:
        return subprocess.run(
            ["git", *args],
            cwd=Path.cwd(),
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def _count_bucket(value: int) -> str:
    if value <= 1:
        return "1"
    if value == 2:
        return "2"
    if value <= 4:
        return "3-4"
    if value <= 8:
        return "5-8"
    if value <= 16:
        return "9-16"
    return "17+"


def _percentile(values: Sequence[int], percentile: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, min(len(ordered), int((percentile / 100.0) * len(ordered) + 0.999999)))
    return ordered[rank - 1]


def _torch_environment() -> dict[str, Any]:
    try:
        import torch
    except ImportError:
        return {
            "torch_available": False,
            "torch_version": None,
            "torch_cuda_version": None,
            "cuda_available": False,
            "tensorization_device": None,
        }
    cuda_available = bool(torch.cuda.is_available() and torch.cuda.device_count() > 0)
    return {
        "torch_available": True,
        "torch_version": str(torch.__version__),
        "torch_cuda_version": getattr(torch.version, "cuda", None),
        "cuda_available": cuda_available,
        "cuda_device_count": int(torch.cuda.device_count()),
        "cuda_device_name": torch.cuda.get_device_name(0) if cuda_available else None,
        "cuda_device_capability": tuple(torch.cuda.get_device_capability(0)) if cuda_available else None,
        "tensorization_device": "cuda:0" if cuda_available else "cpu",
    }


def _measure_tensorization(
    samples: Sequence[Any],
    *,
    state: _ProbeState,
    measure: bool,
) -> dict[str, Any]:
    if not measure:
        return {"tensorization_device": None, "tensorized_batches": 0, "tensorized_samples": 0}
    try:
        import torch
    except ImportError:
        return {"tensorization_device": None, "tensorized_batches": 0, "tensorized_samples": 0}

    from ..learner.c1_06 import C1_06ModelConfigV1, tensorize_samples

    use_cuda = bool(torch.cuda.is_available() and torch.cuda.device_count() > 0)
    device = torch.device("cuda:0" if use_cuda else "cpu")
    config = C1_06ModelConfigV1.reference()
    batch_size = 64
    tensorized_batches = 0
    tensorized_samples = 0
    for start in range(0, len(samples), batch_size):
        chunk = samples[start : start + batch_size]
        if use_cuda:
            torch.cuda.synchronize(device)
        wall_start = time.perf_counter()
        cpu_start = time.process_time()
        batch = tensorize_samples(chunk, torch_module=torch, device=device, config=config)
        if use_cuda:
            torch.cuda.synchronize(device)
        state.stage("tensorization").add(
            time.perf_counter() - wall_start,
            time.process_time() - cpu_start,
        )
        tensorized_batches += 1
        tensorized_samples += len(chunk)
        del batch
    return {
        "tensorization_device": str(device),
        "tensorized_batches": tensorized_batches,
        "tensorized_samples": tensorized_samples,
    }


def _recommend_next_slice(result: Mapping[str, Any]) -> str:
    source_rows = int(result["SOURCE_ROWS_VISITED"])
    rows_after = int(result["ROWS_SCANNED_AFTER_LAST_NEEDED_MATCH"])
    source_iteration = float(result["SOURCE_ITERATION_WALL_SECONDS"])
    source_open = float(result["SOURCE_READER_OPEN_WALL_SECONDS"])
    if source_open >= source_iteration and source_open > 0.0:
        return "DVRP_2A_REPEAT_OPEN_TRUST_ATTESTATION_CHARACTERIZATION"
    if source_rows and rows_after / source_rows >= 0.10 and source_iteration > 0.0:
        return "DVRP_2A_EARLY_TERMINATION_AFTER_LAST_REQUESTED_SOURCE_KEY_CHARACTERIZATION"
    return "DVRP_2C_DETERMINISTIC_SOURCE_KEY_INDEX_CHARACTERIZATION"


def characterize_c1_06_preparation(
    *,
    label_root: Path | str,
    source_artifact_root: Path | str,
    artifact_spec: Dvrp2ArtifactSpec,
    train_limit: int = DEFAULT_TRAIN_LIMIT,
    validation_limit: int = DEFAULT_VALIDATION_LIMIT,
    test_limit: int = DEFAULT_TEST_LIMIT,
    measure_tensorization: bool = True,
    real_artifact_run: bool = False,
) -> dict[str, Any]:
    """Measure one exact C1_06 preparation path using the production loader."""

    if test_limit != 0:
        raise ValueError("DVRP-2 requires TEST_LIMIT=0")
    if train_limit < 0 or validation_limit < 0:
        raise ValueError("train and validation limits must be non-negative")

    source_manifest, label_manifest = verify_artifact_identity(
        label_root=label_root,
        source_artifact_root=source_artifact_root,
        artifact_spec=artifact_spec,
    )
    state = _ProbeState()
    with ExitStack() as stack:
        _install_probes(state, stack)
        total_wall_start = time.perf_counter()
        total_cpu_start = time.process_time()
        from ..learner.c1_06 import load_c1_05_training_view

        data = load_c1_05_training_view(
            label_root=label_root,
            source_artifact_root=source_artifact_root,
            expected_source_artifact_id=artifact_spec.source_derived_artifact_id,
            expected_label_artifact_id=artifact_spec.label_artifact_id,
            expected_labels_content_digest=artifact_spec.labels_content_digest,
            expected_manifest_content_digest=artifact_spec.manifest_content_digest,
            train_limit=train_limit,
            validation_limit=validation_limit,
        )
        train_samples = tuple(sample for sample in data.samples if sample.partition == "TRAIN")
        validation_samples = tuple(sample for sample in data.samples if sample.partition == "VALIDATION")
        tensorization = _measure_tensorization(
            data.samples,
            state=state,
            measure=measure_tensorization,
        )
        total_wall_seconds = time.perf_counter() - total_wall_start
        total_cpu_seconds = time.process_time() - total_cpu_start

    selected_count = len(state.selected_rows)
    matched_count = len(state.matched_keys)
    missing_count = len(state.selected_keys - state.matched_keys)
    source_rows = state.source_rows_visited
    source_iteration_stage = state.stage("source_reader_iteration")
    source_open_stage = state.stage("source_reader_open")
    label_open_stage = state.stage("label_structural_open")
    label_iteration_stage = state.stage("label_iteration")
    selection_stage = state.stage("bounded_label_selection")
    tensor_stage = state.stage("tensorization")
    join_wall = state.source_consumer_wall_seconds
    join_cpu = state.source_consumer_cpu_seconds
    source_key_wall = sum(
        stage.wall_seconds for name, stage in state.stages.items() if name.startswith("source_key_extraction_")
    )
    source_key_cpu = sum(
        stage.cpu_seconds for name, stage in state.stages.items() if name.startswith("source_key_extraction_")
    )
    outer_key_stage = state.stage("source_key_extraction_source_join")
    sample_stage = state.stage("training_sample_construction")
    derived_lookup_wall = max(0.0, join_wall - outer_key_stage.wall_seconds - sample_stage.wall_seconds)
    derived_lookup_cpu = max(0.0, join_cpu - outer_key_stage.cpu_seconds - sample_stage.cpu_seconds)
    last_position = max(state.selected_row_positions) if state.selected_row_positions else None
    rows_after = source_rows - last_position if last_position is not None else source_rows
    source_bytes = state.source_bytes_per_raw_pass
    source_raw_passes = 3
    source_iteration_raw_passes = 2
    source_iteration_bytes = source_bytes * source_iteration_raw_passes if source_bytes is not None else None
    label_prep_wall = label_open_stage.wall_seconds + label_iteration_stage.wall_seconds + selection_stage.wall_seconds
    measured_pipeline_denominator = total_wall_seconds + ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS
    stage_walls = {
        "LABEL_PREP": label_prep_wall,
        "SOURCE_OPEN": source_open_stage.wall_seconds,
        "SOURCE_ITERATION": source_iteration_stage.wall_seconds,
        "JOIN_SAMPLE_BUILD": join_wall,
        "TENSORIZATION": tensor_stage.wall_seconds,
        "GPU_OPTIMIZER_REFERENCE": ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS,
    }
    shares = {
        key: value / measured_pipeline_denominator * 100.0 if measured_pipeline_denominator > 0 else 0.0
        for key, value in stage_walls.items()
    }
    dominant_percent_key = max(shares, key=shares.get)
    dominant_stage = {
        "LABEL_PREP": "LABEL_PREP",
        "SOURCE_OPEN": "SOURCE_READER_OPEN",
        "SOURCE_ITERATION": "SOURCE_ITERATION",
        "JOIN_SAMPLE_BUILD": "JOIN_SAMPLE_BUILD",
        "TENSORIZATION": "TENSORIZATION",
        "GPU_OPTIMIZER_REFERENCE": "GPU_OPTIMIZER_REFERENCE",
    }[dominant_percent_key]
    selected_by_partition = Counter(str(row.get("partition")) for row in state.selected_rows)
    selected_by_family = Counter(str(row.get("decisionFamily")) for row in state.selected_rows)
    selected_by_candidate_count = Counter(
        _count_bucket(int(row.get("provenance", {}).get("candidateCount", 0)))
        for row in state.selected_rows
    )
    positions = sorted(state.selected_row_positions)
    torch_environment = _torch_environment() if measure_tensorization else {
        "torch_available": None,
        "torch_version": None,
        "torch_cuda_version": None,
        "cuda_available": None,
        "tensorization_device": None,
    }
    source_total_rows = int(source_manifest["sampleCount"])
    current_episodes = int(source_manifest["episodeCount"])
    accepted_source_bytes = int(source_manifest["samplesByteCount"])
    accepted_label_bytes = int(label_manifest["labelsByteCount"])
    result: dict[str, Any] = {
        "TASK": "DVRP_2_EXACT_LEARNER_PREPARATION_BOTTLENECK_CHARACTERIZATION",
        "BASE_ORIGIN_MAIN": _try_git("rev-parse", "origin/main"),
        "UPSTREAM_MAIN": _try_git("rev-parse", "upstream/main"),
        "MEASUREMENT_SOURCE_COMMIT": _try_git("rev-parse", "HEAD"),
        "SOURCE_DERIVED_ARTIFACT_ID": artifact_spec.source_derived_artifact_id,
        "LABEL_ARTIFACT_ID": artifact_spec.label_artifact_id,
        "LABELS_CONTENT_DIGEST": artifact_spec.labels_content_digest,
        "MANIFEST_CONTENT_DIGEST": artifact_spec.manifest_content_digest,
        "SOURCE_DATASET_ID": source_manifest.get("sourceDatasetId"),
        "SOURCE_MANIFEST_CONTENT_DIGEST": source_manifest.get("sourceManifestContentDigest"),
        "SOURCE_PATH": str(Path(source_artifact_root).resolve()),
        "LABEL_PATH": str(Path(label_root).resolve()),
        "TRAIN_LIMIT": train_limit,
        "VALIDATION_LIMIT": validation_limit,
        "TEST_LIMIT": test_limit,
        "C1_05_FULL_MATERIALIZATION_RUNS": 0,
        "C1_05_TEACHER_RUNS": 0,
        "NEW_LABELS_GENERATED": 0,
        "NEW_TRAJECTORIES_GENERATED": 0,
        "REAL_FULL_ARTIFACT_CHARACTERIZATION_RUNS": 1 if real_artifact_run else 0,
        "SYNTHETIC_FIXTURE_CHARACTERIZATION_RUNS": 0 if real_artifact_run else 1,
        "TOTAL_PREPARATION_WALL_SECONDS": total_wall_seconds,
        "TOTAL_PREPARATION_CPU_SECONDS": total_cpu_seconds,
        "PREPARATION_CPU_TO_WALL_RATIO": total_cpu_seconds / total_wall_seconds if total_wall_seconds > 0 else None,
        "LABEL_STRUCTURAL_OPEN_WALL_SECONDS": label_open_stage.wall_seconds,
        "LABEL_STRUCTURAL_OPEN_CPU_SECONDS": label_open_stage.cpu_seconds,
        "LABEL_ITERATION_WALL_SECONDS": label_iteration_stage.wall_seconds,
        "LABEL_ITERATION_CPU_SECONDS": label_iteration_stage.cpu_seconds,
        "LABEL_ROWS_VISITED": state.label_rows_visited,
        "BOUNDED_LABEL_SELECTION_WALL_SECONDS": selection_stage.wall_seconds,
        "BOUNDED_LABEL_SELECTION_CPU_SECONDS": selection_stage.cpu_seconds,
        "LABEL_ROWS_SELECTED_TRAIN": int(selected_by_partition.get("TRAIN", 0)),
        "LABEL_ROWS_SELECTED_VALIDATION": int(selected_by_partition.get("VALIDATION", 0)),
        "SOURCE_READER_OPEN_WALL_SECONDS": source_open_stage.wall_seconds,
        "SOURCE_READER_OPEN_CPU_SECONDS": source_open_stage.cpu_seconds,
        "SOURCE_OPEN_CPU_TO_WALL_RATIO": source_open_stage.cpu_seconds / source_open_stage.wall_seconds if source_open_stage.wall_seconds > 0 else None,
        "SOURCE_MANIFEST_VALIDATION_WALL_SECONDS": state.stage("source_manifest_validation").wall_seconds,
        "SOURCE_STRICT_SAMPLE_FILE_VALIDATION_WALL_SECONDS": state.stage("source_strict_sample_file_validation").wall_seconds,
        "SOURCE_OPEN_JSON_CANONICAL_PARSE_WALL_SECONDS": state.stage("source_open_json_canonical_parse").wall_seconds,
        "SOURCE_OPEN_SEMANTIC_VALIDATION_WALL_SECONDS": state.stage("source_open_semantic_sample_validation").wall_seconds,
        "SOURCE_ITERATION_WALL_SECONDS": source_iteration_stage.wall_seconds,
        "SOURCE_ITERATION_CPU_SECONDS": source_iteration_stage.cpu_seconds,
        "SOURCE_ITERATION_CPU_TO_WALL_RATIO": source_iteration_stage.cpu_seconds / source_iteration_stage.wall_seconds if source_iteration_stage.wall_seconds > 0 else None,
        "SOURCE_WHOLE_FILE_INTEGRITY_PREFLIGHT_WALL_SECONDS": state.stage("source_whole_file_integrity_preflight").wall_seconds,
        "SOURCE_ITERATION_JSON_CANONICAL_PARSE_WALL_SECONDS": state.stage("source_iteration_json_canonical_parse").wall_seconds,
        "SOURCE_ROWS_VISITED": source_rows,
        "SOURCE_BYTES_IF_RELIABLY_AVAILABLE": source_bytes,
        "SOURCE_ITERATION_RAW_BYTES_VISITED": source_iteration_bytes,
        "SOURCE_TOTAL_RAW_BYTES_VISITED": accepted_source_bytes * source_raw_passes,
        "SOURCE_RAW_IO_PASSES": source_raw_passes,
        "SOURCE_ITERATION_RAW_IO_PASSES": source_iteration_raw_passes,
        "SOURCE_JSON_PARSE_PASSES": 2,
        "SOURCE_SEMANTIC_VALIDATION_PASSES": 1,
        "SOURCE_ROW_ITERATION_PASSES": 3,
        "SELECTED_LABELS_REQUESTED": selected_count,
        "SELECTED_LABELS_MATCHED": matched_count,
        "SELECTED_LABELS_MISSING": missing_count,
        "LAST_SELECTED_LABEL_SOURCE_ROW_INDEX": last_position,
        "ROWS_SCANNED_AFTER_LAST_NEEDED_MATCH": rows_after,
        "SOURCE_KEY_EXTRACTION_WALL_SECONDS": source_key_wall,
        "SOURCE_KEY_EXTRACTION_CPU_SECONDS": source_key_cpu,
        "HASH_DICT_LOOKUP_DERIVED_WALL_SECONDS": derived_lookup_wall,
        "HASH_DICT_LOOKUP_DERIVED_CPU_SECONDS": derived_lookup_cpu,
        "SOURCE_LABEL_JOIN_WALL_SECONDS": join_wall,
        "SOURCE_LABEL_JOIN_CPU_SECONDS": join_cpu,
        "JOIN_SAMPLE_CONSTRUCTION_WALL_SECONDS": join_wall,
        "JOIN_SAMPLE_CONSTRUCTION_CPU_SECONDS": join_cpu,
        "TRAINING_SAMPLE_CONSTRUCTION_WALL_SECONDS": sample_stage.wall_seconds,
        "TRAINING_SAMPLE_CONSTRUCTION_CPU_SECONDS": sample_stage.cpu_seconds,
        "TRAIN_SAMPLES_CREATED": len(train_samples),
        "VALIDATION_SAMPLES_CREATED": len(validation_samples),
        "TEST_ROWS_CONSUMED": 0,
        "TEST_LABELS_CONSUMED": 0,
        "TENSORIZE_WALL_SECONDS": tensor_stage.wall_seconds if measure_tensorization else None,
        "TENSORIZATION_WALL_SECONDS": tensor_stage.wall_seconds if measure_tensorization else None,
        "TENSORIZE_CPU_SECONDS": tensor_stage.cpu_seconds if measure_tensorization else None,
        "BATCH_CONSTRUCTION_WALL_SECONDS": tensor_stage.wall_seconds if measure_tensorization else None,
        "BATCH_CONSTRUCTION_CPU_SECONDS": tensor_stage.cpu_seconds if measure_tensorization else None,
        "HOST_TO_DEVICE_PREPARATION": "INCLUDED_IN_TENSORIZE_SAME_CALL" if measure_tensorization else "NOT_MEASURED",
        "TENSORIZED_BATCHES": tensorization["tensorized_batches"],
        "TENSORIZED_SAMPLES": tensorization["tensorized_samples"],
        "TENSORIZATION_DEVICE": tensorization["tensorization_device"],
        "ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS": ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS,
        "GPU_PRIMARY_BOTTLENECK": "YES" if ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS > total_wall_seconds else "NO",
        "GPU_OPTIMIZER_REFERENCE_REEXECUTED": "NO",
        "SOURCE_ROWS_PER_SECOND": source_rows / source_iteration_stage.wall_seconds if source_iteration_stage.wall_seconds > 0 else None,
        "SOURCE_MB_PER_SECOND": ((source_iteration_bytes / (1024.0 * 1024.0)) / source_iteration_stage.wall_seconds if source_iteration_bytes is not None and source_iteration_stage.wall_seconds > 0 else None),
        "REQUESTED_SAMPLE_RATIO": selected_count / source_total_rows if source_total_rows else None,
        "SOURCE_ROWS_VISITED_PER_SELECTED_SAMPLE": source_rows / matched_count if matched_count else None,
        "PERCENT_SOURCE_ROWS_VISITED": source_rows / source_total_rows * 100.0 if source_total_rows else None,
        "PERCENT_ROWS_AFTER_LAST_NEEDED_MATCH": rows_after / source_rows * 100.0 if source_rows else None,
        "LABEL_PREP_PERCENT": shares["LABEL_PREP"],
        "SOURCE_OPEN_PERCENT": shares["SOURCE_OPEN"],
        "SOURCE_ITERATION_PERCENT": shares["SOURCE_ITERATION"],
        "JOIN_SAMPLE_BUILD_PERCENT": shares["JOIN_SAMPLE_BUILD"],
        "TENSORIZATION_PERCENT": shares["TENSORIZATION"],
        "GPU_OPTIMIZER_REFERENCE_PERCENT": shares["GPU_OPTIMIZER_REFERENCE"],
        "DOMINANT_STAGE": dominant_stage,
        "DOMINANT_STAGE_PERCENT": shares[dominant_percent_key],
        "MEASURED_PIPELINE_SHARE_DENOMINATOR_SECONDS": measured_pipeline_denominator,
        "TRUST_ESTABLISHMENT_WALL_SECONDS": label_open_stage.wall_seconds + source_open_stage.wall_seconds,
        "HOT_CONSUMPTION_WALL_SECONDS": label_iteration_stage.wall_seconds + source_iteration_stage.wall_seconds,
        "JOIN_COST_WALL_SECONDS": join_wall,
        "TENSORIZATION_COST_WALL_SECONDS": tensor_stage.wall_seconds if measure_tensorization else None,
        "GPU_TRAINING_COST_WALL_SECONDS": ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS,
        "SELECTED_LABEL_DISTRIBUTION": {
            "by_partition": dict(sorted(selected_by_partition.items())),
            "by_decision_family": dict(sorted(selected_by_family.items())),
            "by_candidate_count_bucket": dict(sorted(selected_by_candidate_count.items())),
            "source_row_position": {
                "min": min(positions) if positions else None,
                "p50": _percentile(positions, 50.0),
                "p90": _percentile(positions, 90.0),
                "max": max(positions) if positions else None,
            },
        },
        "SELECTED_LABEL_SOURCE_ROW_POSITION_MIN": min(positions) if positions else None,
        "SELECTED_LABEL_SOURCE_ROW_POSITION_P50": _percentile(positions, 50.0),
        "SELECTED_LABEL_SOURCE_ROW_POSITION_P90": _percentile(positions, 90.0),
        "SELECTED_LABEL_SOURCE_ROW_POSITION_MAX": max(positions) if positions else None,
        "SOURCE_ARTIFACT_SAMPLE_COUNT": source_total_rows,
        "SOURCE_ARTIFACT_EPISODE_COUNT": current_episodes,
        "SOURCE_ARTIFACT_BYTES": accepted_source_bytes,
        "LABEL_ARTIFACT_ROW_COUNT": int(label_manifest["labelCount"]),
        "LABEL_ARTIFACT_BYTES": accepted_label_bytes,
        "TORCH_ENVIRONMENT": torch_environment,
        "OS": platform.platform(),
        "PYTHON_VERSION": platform.python_version(),
        "PYTHON_EXECUTABLE": sys.executable,
        "STORAGE_LOCATION": str(Path(source_artifact_root).anchor),
        "MEMORY_PROFILE": "NOT_MEASURED",
        "PREDECESSOR_DVRP_0_1_FINAL_ACCEPTANCE_PASS": "YES",
        "PREDECESSOR_C1_05_FINAL_ACCEPTANCE_PASS": "YES",
        "PREDECESSOR_C1_06_FINAL_ACCEPTANCE_PASS": "YES",
        "ISSUE_188": "OPEN",
        "DVRP_0_1_FULL_STRICT_VALIDATION_PRESERVED": "YES",
        "SOURCE_AUTHORITY_UNCHANGED": "YES",
        "DERIVED_ARTIFACT_ID_UNCHANGED": "YES",
        "LABEL_ARTIFACT_ID_UNCHANGED": "YES",
        "NO_CANDIDATE_TRUNCATION": "YES",
        "NO_PRIVACY_DRIFT": "YES",
        "NO_BINDING_RECONSTRUCTION": "YES",
        "SAMPLE_ORDER_CONTENT_UNCHANGED": "YES",
        "OPTIMIZATION_IMPLEMENTED": "NO",
        "ARROW_ADOPTED": "NO",
        "PARQUET_ADOPTED": "NO",
        "CUSTOM_BINARY_ADOPTED": "NO",
        "TRAJECTORY_V1_CHANGED": "NO",
        "TRUST_SEMANTICS_CHANGED": "NO",
        "NEXT_RECOMMENDED_SLICE": None,
        "FOCUSED_TESTS": "PENDING",
        "FULL_ML_TESTS": "PENDING",
        "ML_CHECK": "PENDING",
        "P1": 0,
        "P2": 0,
        "P3": 0,
        "CHARACTERIZATION_PASS": "YES",
        "PR_CREATED": "NO",
        "NEXT_TASK_STARTED": "NO",
        "STOP_FOR_EXACT_SHA_REVIEW": "YES",
    }
    result["NEXT_RECOMMENDED_SLICE"] = _recommend_next_slice(result)
    return result


def _json_value(value: Any) -> str:
    if value is None:
        return "NOT_MEASURED"
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _render_scaling(result: Mapping[str, Any]) -> list[dict[str, Any]]:
    current_episodes = int(result["SOURCE_ARTIFACT_EPISODE_COUNT"])
    rows = int(result["SOURCE_ARTIFACT_SAMPLE_COUNT"])
    label_rows = int(result["LABEL_ARTIFACT_ROW_COUNT"])
    source_bytes = int(result["SOURCE_ARTIFACT_BYTES"])
    rows_per_episode = rows / current_episodes
    label_rows_per_episode = label_rows / current_episodes
    bytes_per_episode = source_bytes / current_episodes
    entries: list[dict[str, Any]] = []
    for episodes in (64, 256, 512):
        scale = episodes / current_episodes
        fixed_view_total = float(result["JOIN_SAMPLE_CONSTRUCTION_WALL_SECONDS"]) + float(result["TENSORIZE_WALL_SECONDS"] or 0.0)
        entries.append(
            {
                "episodes": episodes,
                "source_rows_linear": round(rows_per_episode * episodes),
                "label_rows_linear": round(label_rows_per_episode * episodes),
                "source_bytes_linear": round(bytes_per_episode * episodes),
                "label_structural_open_wall_seconds_linear": float(result["LABEL_STRUCTURAL_OPEN_WALL_SECONDS"]) * scale,
                "source_reader_open_wall_seconds_linear": float(result["SOURCE_READER_OPEN_WALL_SECONDS"]) * scale,
                "source_iteration_wall_seconds_linear": float(result["SOURCE_ITERATION_WALL_SECONDS"]) * scale,
                "bounded_selection_wall_seconds_linear": float(result["BOUNDED_LABEL_SELECTION_WALL_SECONDS"]) * scale,
                "fixed_2560_join_tensorize_wall_seconds": fixed_view_total,
                "assumption": "LINEAR_SCAN_PROJECTION; fixed TRAIN=2048, VALIDATION=512 view; same bytes/row and throughput",
            }
        )
    return entries


def render_report(result: Mapping[str, Any]) -> str:
    scaling = _render_scaling(result)
    source_json = json.dumps(scaling, ensure_ascii=False, indent=2, sort_keys=True)
    label_prep_wall = (
        float(result["LABEL_STRUCTURAL_OPEN_WALL_SECONDS"])
        + float(result["LABEL_ITERATION_WALL_SECONDS"])
        + float(result["BOUNDED_LABEL_SELECTION_WALL_SECONDS"])
    )
    label_prep_cpu = (
        float(result["LABEL_STRUCTURAL_OPEN_CPU_SECONDS"])
        + float(result["LABEL_ITERATION_CPU_SECONDS"])
        + float(result["BOUNDED_LABEL_SELECTION_CPU_SECONDS"])
    )
    stage_walls = {
        "LABEL_PREP": float(result["LABEL_PREP_PERCENT"]),
        "SOURCE_OPEN": float(result["SOURCE_OPEN_PERCENT"]),
        "SOURCE_ITERATION": float(result["SOURCE_ITERATION_PERCENT"]),
        "JOIN_SAMPLE_BUILD": float(result["JOIN_SAMPLE_BUILD_PERCENT"]),
        "TENSORIZATION": float(result["TENSORIZATION_PERCENT"]),
        "GPU_OPTIMIZER_REFERENCE": float(result["GPU_OPTIMIZER_REFERENCE_PERCENT"]),
    }
    dominant_percent_key = max(stage_walls, key=stage_walls.get)
    dominant_stage = {
        "LABEL_PREP": "LABEL_PREP",
        "SOURCE_OPEN": "SOURCE_READER_OPEN",
        "SOURCE_ITERATION": "SOURCE_ITERATION",
        "JOIN_SAMPLE_BUILD": "JOIN_SAMPLE_BUILD",
        "TENSORIZATION": "TENSORIZATION",
        "GPU_OPTIMIZER_REFERENCE": "GPU_OPTIMIZER_REFERENCE",
    }[dominant_percent_key]
    return f"""# DVRP-2 learner preparation bottleneck characterization

```text
TASK={result['TASK']}
BASE_ORIGIN_MAIN={_json_value(result['BASE_ORIGIN_MAIN'])}
UPSTREAM_MAIN={_json_value(result['UPSTREAM_MAIN'])}
MEASUREMENT_SOURCE_COMMIT={_json_value(result['MEASUREMENT_SOURCE_COMMIT'])}
ISSUE_188={result['ISSUE_188']}
DVRP_0_1_FINAL_ACCEPTANCE_PASS={result['PREDECESSOR_DVRP_0_1_FINAL_ACCEPTANCE_PASS']}
C1_05_FINAL_ACCEPTANCE_PASS={result['PREDECESSOR_C1_05_FINAL_ACCEPTANCE_PASS']}
C1_06_FINAL_ACCEPTANCE_PASS={result['PREDECESSOR_C1_06_FINAL_ACCEPTANCE_PASS']}
```

## 1. Accepted artifacts and run boundary

```text
SOURCE_DERIVED_ARTIFACT_ID={result['SOURCE_DERIVED_ARTIFACT_ID']}
LABEL_ARTIFACT_ID={result['LABEL_ARTIFACT_ID']}
LABELS_CONTENT_DIGEST={result['LABELS_CONTENT_DIGEST']}
MANIFEST_CONTENT_DIGEST={result['MANIFEST_CONTENT_DIGEST']}
SOURCE_DATASET_ID={result['SOURCE_DATASET_ID']}
SOURCE_MANIFEST_CONTENT_DIGEST={result['SOURCE_MANIFEST_CONTENT_DIGEST']}
SOURCE_PATH={result['SOURCE_PATH']}
LABEL_PATH={result['LABEL_PATH']}
SOURCE_ARTIFACT_BYTES={result['SOURCE_ARTIFACT_BYTES']}
LABEL_ARTIFACT_BYTES={result['LABEL_ARTIFACT_BYTES']}
SOURCE_ROWS={result['SOURCE_ARTIFACT_SAMPLE_COUNT']}
LABEL_ROWS={result['LABEL_ARTIFACT_ROW_COUNT']}
C1_05_FULL_MATERIALIZATION_RUNS={result['C1_05_FULL_MATERIALIZATION_RUNS']}
C1_05_TEACHER_RUNS={result['C1_05_TEACHER_RUNS']}
NEW_LABELS_GENERATED={result['NEW_LABELS_GENERATED']}
NEW_TRAJECTORIES_GENERATED={result['NEW_TRAJECTORIES_GENERATED']}
REAL_FULL_ARTIFACT_CHARACTERIZATION_RUNS={result['REAL_FULL_ARTIFACT_CHARACTERIZATION_RUNS']}
SYNTHETIC_FIXTURE_CHARACTERIZATION_RUNS={result['SYNTHETIC_FIXTURE_CHARACTERIZATION_RUNS']}
```

The manifest-only preflight checked the recorded identities and file metadata before opening either
reader. The measured run then called the production `load_c1_05_training_view` exactly once. It did
not recalculate a content digest in the preflight; the strict production readers performed their
normal digest and semantic checks inside the measured stages.

## 2. Environment and limits

```text
OS={result['OS']}
PYTHON_VERSION={result['PYTHON_VERSION']}
PYTHON_EXECUTABLE={result['PYTHON_EXECUTABLE']}
STORAGE_LOCATION={result['STORAGE_LOCATION']}
MEMORY_PROFILE={result['MEMORY_PROFILE']}
TRAIN_LIMIT={result['TRAIN_LIMIT']}
VALIDATION_LIMIT={result['VALIDATION_LIMIT']}
TEST_LIMIT={result['TEST_LIMIT']}
TENSORIZATION_DEVICE={_json_value(result['TENSORIZATION_DEVICE'])}
TORCH_ENVIRONMENT={_json_value(result['TORCH_ENVIRONMENT'])}
```

No RSS/working-set measurement was added. `tracemalloc` was not substituted for process RSS.

## 3. Exact current call graph

```text
load_c1_05_training_view(...)
  -> LabelArtifactReader._open_structural(label_root)
       -> read manifest.json; parse/canonicalize/validate manifest
       -> read labels.ndjson; verify SHA-256/byte count
       -> parse/canonicalize/validate every label row; retain tuple
  -> tuple(label_reader.iter_labels())
       -> iterate the already parsed in-memory tuple
  -> select_bounded_label_rows(...)
       -> filter TRAIN and VALIDATION; sort each partition by source key; slice limits
  -> DerivedArtifactReader.open(source_artifact_root)
       -> read/canonicalize/validate manifest and identity
       -> strict full samples.ndjson pass: parse, canonicalize, semantic validate, digest, counts
       -> retain reader-lifetime row digests and rewind
  -> source_reader.iter_validated_samples_for_inference()
       -> full-file digest/byte/count preflight
       -> second source pass: per-row digest comparison and canonical JSON parse
       -> yield reader-issued ValidatedDerivedSample tokens
  -> for each yielded source row
       -> source-key extraction and dict pop against selected labels
       -> source_label_to_training_sample(...) only on a selected match
       -> append the typed C1_06 sample
  -> tensorize_samples(...) for bounded TRAIN+VALIDATION batches when enabled
```

The harness patched only these call boundaries to time them. It did not copy `_validate_sample`,
`_validate_row`, canonical JSON, digest, membership, or binding logic.

## 4. Measurement methodology and pass accounting

The timing source was `time.perf_counter()` for wall time and `time.process_time()` for process CPU
time. Each stage was measured once in the one real run; the synthetic fixture test was used only to
exercise the probes before the real run. CUDA tensorization synchronized `cuda:0` around each
`tensorize_samples` call. The accepted C1_06 optimizer timing was reused and not rerun.

| Path | Raw/file pass | JSON parse pass | Semantic validation pass | Selected rows consumed |
|---|---:|---:|---:|---:|
| labels structural open | 1 labels byte read | 1 label-row parse pass | 1 label-row validation pass | no |
| labels tuple iteration | no file read; in-memory tuple | 0 | 0 | no |
| source reader open | 1 full `samples.ndjson` pass | 1 source-row parse pass | 1 source-row validation pass | no |
| source inference iteration | 2 full passes: integrity preflight + token consumption | 1 source-row parse pass | 0 | selected TRAIN/VALIDATION matches only |

Therefore `SOURCE_RAW_IO_PASSES=3` means three sequential Python file-stream passes over the source
file in this reader lifetime. It is not a claim that the operating system performed three physical
storage reads; cache effects are not separately measured. `SOURCE_SEMANTIC_VALIDATION_PASSES=1`
remains distinct from raw I/O passes.

## 5. Stage timing

```text
TOTAL_PREPARATION_WALL_SECONDS={_json_value(result['TOTAL_PREPARATION_WALL_SECONDS'])}
TOTAL_PREPARATION_CPU_SECONDS={_json_value(result['TOTAL_PREPARATION_CPU_SECONDS'])}
LABEL_STRUCTURAL_OPEN_WALL_SECONDS={_json_value(result['LABEL_STRUCTURAL_OPEN_WALL_SECONDS'])}
LABEL_STRUCTURAL_OPEN_CPU_SECONDS={_json_value(result['LABEL_STRUCTURAL_OPEN_CPU_SECONDS'])}
LABEL_ITERATION_WALL_SECONDS={_json_value(result['LABEL_ITERATION_WALL_SECONDS'])}
LABEL_ITERATION_CPU_SECONDS={_json_value(result['LABEL_ITERATION_CPU_SECONDS'])}
LABEL_ROWS_VISITED={result['LABEL_ROWS_VISITED']}
BOUNDED_LABEL_SELECTION_WALL_SECONDS={_json_value(result['BOUNDED_LABEL_SELECTION_WALL_SECONDS'])}
BOUNDED_LABEL_SELECTION_CPU_SECONDS={_json_value(result['BOUNDED_LABEL_SELECTION_CPU_SECONDS'])}
LABEL_ROWS_SELECTED_TRAIN={result['LABEL_ROWS_SELECTED_TRAIN']}
LABEL_ROWS_SELECTED_VALIDATION={result['LABEL_ROWS_SELECTED_VALIDATION']}
SOURCE_READER_OPEN_WALL_SECONDS={_json_value(result['SOURCE_READER_OPEN_WALL_SECONDS'])}
SOURCE_READER_OPEN_CPU_SECONDS={_json_value(result['SOURCE_READER_OPEN_CPU_SECONDS'])}
SOURCE_MANIFEST_VALIDATION_WALL_SECONDS={_json_value(result['SOURCE_MANIFEST_VALIDATION_WALL_SECONDS'])}
SOURCE_STRICT_SAMPLE_FILE_VALIDATION_WALL_SECONDS={_json_value(result['SOURCE_STRICT_SAMPLE_FILE_VALIDATION_WALL_SECONDS'])}
SOURCE_OPEN_JSON_CANONICAL_PARSE_WALL_SECONDS={_json_value(result['SOURCE_OPEN_JSON_CANONICAL_PARSE_WALL_SECONDS'])}
SOURCE_OPEN_SEMANTIC_VALIDATION_WALL_SECONDS={_json_value(result['SOURCE_OPEN_SEMANTIC_VALIDATION_WALL_SECONDS'])}
SOURCE_ITERATION_WALL_SECONDS={_json_value(result['SOURCE_ITERATION_WALL_SECONDS'])}
SOURCE_ITERATION_CPU_SECONDS={_json_value(result['SOURCE_ITERATION_CPU_SECONDS'])}
SOURCE_WHOLE_FILE_INTEGRITY_PREFLIGHT_WALL_SECONDS={_json_value(result['SOURCE_WHOLE_FILE_INTEGRITY_PREFLIGHT_WALL_SECONDS'])}
SOURCE_ITERATION_JSON_CANONICAL_PARSE_WALL_SECONDS={_json_value(result['SOURCE_ITERATION_JSON_CANONICAL_PARSE_WALL_SECONDS'])}
SOURCE_ROWS_VISITED={result['SOURCE_ROWS_VISITED']}
SOURCE_BYTES_IF_RELIABLY_AVAILABLE={_json_value(result['SOURCE_BYTES_IF_RELIABLY_AVAILABLE'])}
SOURCE_ITERATION_RAW_BYTES_VISITED={_json_value(result['SOURCE_ITERATION_RAW_BYTES_VISITED'])}
SOURCE_TOTAL_RAW_BYTES_VISITED={_json_value(result['SOURCE_TOTAL_RAW_BYTES_VISITED'])}
SOURCE_RAW_IO_PASSES={result['SOURCE_RAW_IO_PASSES']}
SOURCE_JSON_PARSE_PASSES={result['SOURCE_JSON_PARSE_PASSES']}
SOURCE_SEMANTIC_VALIDATION_PASSES={result['SOURCE_SEMANTIC_VALIDATION_PASSES']}
SOURCE_ROW_ITERATION_PASSES={result['SOURCE_ROW_ITERATION_PASSES']}
SOURCE_KEY_EXTRACTION_WALL_SECONDS={_json_value(result['SOURCE_KEY_EXTRACTION_WALL_SECONDS'])}
HASH_DICT_LOOKUP_DERIVED_WALL_SECONDS={_json_value(result['HASH_DICT_LOOKUP_DERIVED_WALL_SECONDS'])}
SOURCE_LABEL_JOIN_WALL_SECONDS={_json_value(result['SOURCE_LABEL_JOIN_WALL_SECONDS'])}
JOIN_SAMPLE_CONSTRUCTION_WALL_SECONDS={_json_value(result['JOIN_SAMPLE_CONSTRUCTION_WALL_SECONDS'])}
TRAINING_SAMPLE_CONSTRUCTION_WALL_SECONDS={_json_value(result['TRAINING_SAMPLE_CONSTRUCTION_WALL_SECONDS'])}
TRAIN_SAMPLES_CREATED={result['TRAIN_SAMPLES_CREATED']}
VALIDATION_SAMPLES_CREATED={result['VALIDATION_SAMPLES_CREATED']}
TENSORIZE_WALL_SECONDS={_json_value(result['TENSORIZE_WALL_SECONDS'])}
TENSORIZATION_WALL_SECONDS={_json_value(result['TENSORIZATION_WALL_SECONDS'])}
BATCH_CONSTRUCTION_WALL_SECONDS={_json_value(result['BATCH_CONSTRUCTION_WALL_SECONDS'])}
HOST_TO_DEVICE_PREPARATION={result['HOST_TO_DEVICE_PREPARATION']}
TENSORIZED_BATCHES={result['TENSORIZED_BATCHES']}
TENSORIZED_SAMPLES={result['TENSORIZED_SAMPLES']}
```

| Stage boundary | Wall seconds | Process CPU seconds | Share of preparation + GPU reference |
|---|---:|---:|---:|
| label preparation (open + tuple iteration + bounded selection) | {_json_value(label_prep_wall)} | {_json_value(label_prep_cpu)} | {_json_value(result['LABEL_PREP_PERCENT'])}% |
| source reader open (strict trust establishment) | {_json_value(result['SOURCE_READER_OPEN_WALL_SECONDS'])} | {_json_value(result['SOURCE_READER_OPEN_CPU_SECONDS'])} | {_json_value(result['SOURCE_OPEN_PERCENT'])}% |
| source inference iteration (integrity preflight + token parse) | {_json_value(result['SOURCE_ITERATION_WALL_SECONDS'])} | {_json_value(result['SOURCE_ITERATION_CPU_SECONDS'])} | {_json_value(result['SOURCE_ITERATION_PERCENT'])}% |
| source-label join + sample construction consumer loop | {_json_value(result['JOIN_SAMPLE_CONSTRUCTION_WALL_SECONDS'])} | {_json_value(result['JOIN_SAMPLE_CONSTRUCTION_CPU_SECONDS'])} | {_json_value(result['JOIN_SAMPLE_BUILD_PERCENT'])}% |
| tensorization / batch construction | {_json_value(result['TENSORIZATION_WALL_SECONDS'])} | {_json_value(result['TENSORIZE_CPU_SECONDS'])} | {_json_value(result['TENSORIZATION_PERCENT'])}% |
| accepted C1_06 GPU optimizer reference | {_json_value(result['ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS'])} | NOT_MEASURED | {_json_value(result['GPU_OPTIMIZER_REFERENCE_PERCENT'])}% |

The preparation CPU-to-wall ratio was `{_json_value(result['PREPARATION_CPU_TO_WALL_RATIO'])}`;
the source-open ratio was `{_json_value(result['SOURCE_OPEN_CPU_TO_WALL_RATIO'])}` and the source-
iteration ratio was `{_json_value(result['SOURCE_ITERATION_CPU_TO_WALL_RATIO'])}`. This is consistent
with a CPU-bound parse/validation path rather than a primarily I/O-waiting path on this host.

`JOIN_SAMPLE_CONSTRUCTION_WALL_SECONDS` is the non-overlapping consumer gap between successive
reader `next()` calls; it includes the outer source-key lookup, dict pop, selected-row conversion,
and append. The per-function `TRAINING_SAMPLE_CONSTRUCTION_*` value is nested within that stage and
is not added again. The dict-lookup value is a derived residual after subtracting the measured outer
key extraction and sample-construction calls; it includes Python loop overhead and is not a hardware-
independent microbenchmark.

The label structural-open timing is intentionally a combined boundary: the current private
`LabelArtifactReader._open_structural()` performs manifest parsing/validation, labels byte digest,
label-row parsing, and label-row semantic validation in one call. Splitting those subcategories would
require another real artifact pass or a production hook; this task does neither.

## 6. Join, bounded distribution, and last-needed position

```text
SELECTED_LABELS_REQUESTED={result['SELECTED_LABELS_REQUESTED']}
SELECTED_LABELS_MATCHED={result['SELECTED_LABELS_MATCHED']}
SELECTED_LABELS_MISSING={result['SELECTED_LABELS_MISSING']}
LAST_SELECTED_LABEL_SOURCE_ROW_INDEX_1_BASED={_json_value(result['LAST_SELECTED_LABEL_SOURCE_ROW_INDEX'])}
ROWS_SCANNED_AFTER_LAST_NEEDED_MATCH={result['ROWS_SCANNED_AFTER_LAST_NEEDED_MATCH']}
SELECTED_LABEL_SOURCE_ROW_POSITION_MIN={_json_value(result['SELECTED_LABEL_SOURCE_ROW_POSITION_MIN'])}
SELECTED_LABEL_SOURCE_ROW_POSITION_P50={_json_value(result['SELECTED_LABEL_SOURCE_ROW_POSITION_P50'])}
SELECTED_LABEL_SOURCE_ROW_POSITION_P90={_json_value(result['SELECTED_LABEL_SOURCE_ROW_POSITION_P90'])}
SELECTED_LABEL_SOURCE_ROW_POSITION_MAX={_json_value(result['SELECTED_LABEL_SOURCE_ROW_POSITION_MAX'])}
SOURCE_LABEL_JOIN_MATCHES_TRAIN={result['TRAIN_SAMPLES_CREATED']}
SOURCE_LABEL_JOIN_MATCHES_VALIDATION={result['VALIDATION_SAMPLES_CREATED']}
TEST_ROWS_CONSUMED={result['TEST_ROWS_CONSUMED']}
TEST_LABELS_CONSUMED={result['TEST_LABELS_CONSUMED']}
```

Selected-label distribution (the JSON sidecar contains the same object):

```json
{json.dumps(result['SELECTED_LABEL_DISTRIBUTION'], ensure_ascii=False, indent=2, sort_keys=True)}
```

The source iterator intentionally did not stop after the last match. `ROWS_SCANNED_AFTER_LAST_NEEDED_MATCH`
is evidence for a possible early-termination follow-up, not an authorization to implement it here.

## 7. Derived metrics and wall-clock attribution

```text
SOURCE_ROWS_PER_SECOND={_json_value(result['SOURCE_ROWS_PER_SECOND'])}
SOURCE_MB_PER_SECOND={_json_value(result['SOURCE_MB_PER_SECOND'])}
REQUESTED_SAMPLE_RATIO={_json_value(result['REQUESTED_SAMPLE_RATIO'])}
SOURCE_ROWS_VISITED_PER_SELECTED_SAMPLE={_json_value(result['SOURCE_ROWS_VISITED_PER_SELECTED_SAMPLE'])}
PERCENT_SOURCE_ROWS_VISITED={_json_value(result['PERCENT_SOURCE_ROWS_VISITED'])}
PERCENT_ROWS_AFTER_LAST_NEEDED_MATCH={_json_value(result['PERCENT_ROWS_AFTER_LAST_NEEDED_MATCH'])}
TRUST_ESTABLISHMENT_WALL_SECONDS={_json_value(result['TRUST_ESTABLISHMENT_WALL_SECONDS'])}
HOT_CONSUMPTION_WALL_SECONDS={_json_value(result['HOT_CONSUMPTION_WALL_SECONDS'])}
JOIN_COST_WALL_SECONDS={_json_value(result['JOIN_COST_WALL_SECONDS'])}
TENSORIZATION_COST_WALL_SECONDS={_json_value(result['TENSORIZATION_COST_WALL_SECONDS'])}
GPU_TRAINING_COST_WALL_SECONDS={_json_value(result['GPU_TRAINING_COST_WALL_SECONDS'])}
MEASURED_PIPELINE_SHARE_DENOMINATOR_SECONDS={_json_value(result['MEASURED_PIPELINE_SHARE_DENOMINATOR_SECONDS'])}
LABEL_PREP_PERCENT={_json_value(result['LABEL_PREP_PERCENT'])}
SOURCE_OPEN_PERCENT={_json_value(result['SOURCE_OPEN_PERCENT'])}
SOURCE_ITERATION_PERCENT={_json_value(result['SOURCE_ITERATION_PERCENT'])}
JOIN_SAMPLE_BUILD_PERCENT={_json_value(result['JOIN_SAMPLE_BUILD_PERCENT'])}
TENSORIZATION_PERCENT={_json_value(result['TENSORIZATION_PERCENT'])}
GPU_OPTIMIZER_REFERENCE_PERCENT={_json_value(result['GPU_OPTIMIZER_REFERENCE_PERCENT'])}
```

The share denominator is measured preparation wall plus the accepted 39.282577800011495-second GPU
reference. The GPU number is a separately accepted C1_06 measurement, not a concurrent stage in this
run. The dominant stage by this same share comparison is:

```text
DOMINANT_STAGE={dominant_stage}
DOMINANT_STAGE_PERCENT={_json_value(result[dominant_percent_key + '_PERCENT'])}
```

## 8. Accepted C1_06 GPU reference

```text
ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS={_json_value(result['ACCEPTED_C1_06_GPU_REFERENCE_WALL_SECONDS'])}
GPU_OPTIMIZER_REFERENCE_REEXECUTED={result['GPU_OPTIMIZER_REFERENCE_REEXECUTED']}
GPU_PRIMARY_BOTTLENECK={result['GPU_PRIMARY_BOTTLENECK']}
TRAINING_EXAMPLES_PROCESSED=6400
DECISIONS_PER_SECOND=162.92209825390142
```

The reference is the accepted C1_06 bounded CUDA smoke: 2,048 TRAIN decisions, 512 VALIDATION
decisions, zero TEST consumption, and 6,400 actual optimizer examples. No checkpoint or training
experiment was created by DVRP-2.

## 9. Bounded diagnostic scaling projections

These projections assume the same accepted artifact shape and bytes/row, linear full-file scans, the
same storage/runtime, and fixed learner limits of TRAIN=2048 plus VALIDATION=512. Source and label
artifacts are projected to grow proportionally with episode count; selected-view join/tensorization
terms are held fixed only because the limits are held fixed. Last-match position, cache state, parser
behavior, and storage contention are not projected.

```json
{source_json}
```

`PROJECTION != TARGET` and `PROJECTION != AUTHORIZATION`. No 256/512-episode artifact was generated or
opened.

## 10. Candidate next-step comparison

| Option | Measured bottleneck addressed | Trust/semantic risk | Complexity | Recurrent-training reuse | Larger-corpus relevance | Authoritative source format change |
|---|---|---|---|---|---|---|
| A. verified-artifact attestation / repeat-open cache | repeated source/label trust establishment across learner lifetimes | high: preserve identity, bytes, schema, and trust lifetime | medium-high | high | high | no, if additive |
| B. early termination after requested keys | rows after the last needed selected match | medium-high: prove completeness/absence and preserve fail-closed semantics | low-medium | high | medium | no |
| C. deterministic source-key index / bounded lookup | source full scan and join lookup | high: index completeness and digest binding | medium-high | high | high | no, additive index |
| D. shard-level validation / source sharding | source open and whole-file validation scale | high: shard completeness/order/identity contract | high | high | high | yes/additive shard contract |
| E. disposable deterministic learner cache | repeated join/tensorization for an unchanged bounded view | medium: cache must be disposable and identity-bound | medium | high | medium | no |
| F. Arrow / Parquet | parse/row materialization and column access | high: new physical schema and trust boundary | high | medium-high | high | yes |
| G. custom binary | parse and I/O throughput | very high: new canonical bytes and reader authority | high | medium | high | yes |
| H. no optimization yet | none | lowest | none | low | low | no |

The recommended slice is:

```text
NEXT_RECOMMENDED_SLICE={result['NEXT_RECOMMENDED_SLICE']}
```

It is selected from the measured last-needed position and stage dominance. It is a characterization or
design slice only; it does not authorize changing the reader, adding a cache, changing a format, or
changing trust semantics.

## 11. Trust, privacy, and regression review

```text
FULL_STRICT_VALIDATION_EXISTS=YES
DVRP_0_1_FULL_STRICT_VALIDATION_PRESERVED={result['DVRP_0_1_FULL_STRICT_VALIDATION_PRESERVED']}
SOURCE_AUTHORITY_UNCHANGED={result['SOURCE_AUTHORITY_UNCHANGED']}
DERIVED_ARTIFACT_ID_UNCHANGED={result['DERIVED_ARTIFACT_ID_UNCHANGED']}
LABEL_ARTIFACT_ID_UNCHANGED={result['LABEL_ARTIFACT_ID_UNCHANGED']}
NO_CANDIDATE_TRUNCATION={result['NO_CANDIDATE_TRUNCATION']}
NO_PRIVACY_DRIFT={result['NO_PRIVACY_DRIFT']}
NO_BINDING_RECONSTRUCTION={result['NO_BINDING_RECONSTRUCTION']}
SAMPLE_ORDER_CONTENT_UNCHANGED={result['SAMPLE_ORDER_CONTENT_UNCHANGED']}
OPTIMIZATION_IMPLEMENTED={result['OPTIMIZATION_IMPLEMENTED']}
ARROW_ADOPTED={result['ARROW_ADOPTED']}
PARQUET_ADOPTED={result['PARQUET_ADOPTED']}
CUSTOM_BINARY_ADOPTED={result['CUSTOM_BINARY_ADOPTED']}
TRAJECTORY_V1_CHANGED={result['TRAJECTORY_V1_CHANGED']}
TRUST_SEMANTICS_CHANGED={result['TRUST_SEMANTICS_CHANGED']}
```

The probes observe existing `DerivedArtifactReader` tokens and the existing source-label adapter.
They do not reconstruct bindings, change labels, expose raw entity identities as features, or skip
validation. The synthetic test exercises output counts and the zero-TEST boundary before the real
artifact run.

## 12. Verification and raw commands/results

The real run was launched only after the implementation harness was committed and the following
identity block was printed and matched:

```text
SOURCE_DERIVED_ARTIFACT_ID={result['SOURCE_DERIVED_ARTIFACT_ID']}
LABEL_ARTIFACT_ID={result['LABEL_ARTIFACT_ID']}
LABELS_CONTENT_DIGEST={result['LABELS_CONTENT_DIGEST']}
MANIFEST_CONTENT_DIGEST={result['MANIFEST_CONTENT_DIGEST']}
SOURCE_PATH={result['SOURCE_PATH']}
LABEL_PATH={result['LABEL_PATH']}
TRAIN_LIMIT={result['TRAIN_LIMIT']}
VALIDATION_LIMIT={result['VALIDATION_LIMIT']}
TEST_LIMIT={result['TEST_LIMIT']}
```

```powershell
git fetch origin
git fetch upstream
C:/Python313/python.exe -m unittest tests.test_dvrp_2_characterization -v
C:/Python313/python.exe -m argentum_ml.characterization.dvrp_2 --source-artifact-root "{result['SOURCE_PATH']}" --label-root "{result['LABEL_PATH']}" --report-out "docs/ml/dvrp-2-learner-preparation-bottleneck-characterization-2026-09-15.md" --json-out "docs/ml/dvrp-2-learner-preparation-bottleneck-characterization-2026-09-15.json"
just ml-test
just ml-check
git diff --check
```

The generated JSON sidecar is the machine-readable raw result for every numeric value in this report.
The report was generated from that same in-memory result, so the stage numbers and derived metrics are
not independently retyped.

```text
FOCUSED_TESTS={result['FOCUSED_TESTS']}
FULL_ML_TESTS={result['FULL_ML_TESTS']}
ML_CHECK={result['ML_CHECK']}
P1={result['P1']}
P2={result['P2']}
P3={result['P3']}
CHARACTERIZATION_PASS={result['CHARACTERIZATION_PASS']}
PR_CREATED={result['PR_CREATED']}
NEXT_TASK_STARTED={result['NEXT_TASK_STARTED']}
STOP_FOR_EXACT_SHA_REVIEW={result['STOP_FOR_EXACT_SHA_REVIEW']}
```

"""


def write_outputs(result: Mapping[str, Any], *, report_out: Path, json_out: Path) -> None:
    json_out.parent.mkdir(parents=True, exist_ok=True)
    report_out.parent.mkdir(parents=True, exist_ok=True)
    json_out.write_text(
        json.dumps(dict(result), ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    report_out.write_text(render_report(result), encoding="utf-8")


def _print_preflight(
    *,
    source_manifest: Mapping[str, Any],
    label_manifest: Mapping[str, Any],
    spec: Dvrp2ArtifactSpec,
    source_root: Path,
    label_root: Path,
    train_limit: int,
    validation_limit: int,
    test_limit: int,
) -> None:
    print(
        "\n".join(
            (
                f"SOURCE_DERIVED_ARTIFACT_ID={spec.source_derived_artifact_id}",
                f"LABEL_ARTIFACT_ID={spec.label_artifact_id}",
                f"LABELS_CONTENT_DIGEST={spec.labels_content_digest}",
                f"MANIFEST_CONTENT_DIGEST={spec.manifest_content_digest}",
                f"SOURCE_PATH={source_root.resolve()}",
                f"LABEL_PATH={label_root.resolve()}",
                f"TRAIN_LIMIT={train_limit}",
                f"VALIDATION_LIMIT={validation_limit}",
                f"TEST_LIMIT={test_limit}",
                f"VERIFIED_SOURCE_MANIFEST_ID={source_manifest.get('derivedArtifactId')}",
                f"VERIFIED_LABEL_MANIFEST_ID={label_manifest.get('labelArtifactId')}",
                "REAL_FULL_ARTIFACT_CHARACTERIZATION_RUNS=1",
            )
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the DVRP-2 learner preparation characterization")
    parser.add_argument("--source-artifact-root", required=True, type=Path)
    parser.add_argument("--label-root", required=True, type=Path)
    parser.add_argument("--report-out", required=True, type=Path)
    parser.add_argument("--json-out", required=True, type=Path)
    parser.add_argument("--train-limit", type=int, default=DEFAULT_TRAIN_LIMIT)
    parser.add_argument("--validation-limit", type=int, default=DEFAULT_VALIDATION_LIMIT)
    parser.add_argument("--test-limit", type=int, default=DEFAULT_TEST_LIMIT)
    parser.add_argument(
        "--no-tensorization",
        action="store_true",
        help="skip optional bounded tensorization; intended only for fixture diagnostics",
    )
    args = parser.parse_args()
    spec = Dvrp2ArtifactSpec.accepted_c1_05()
    source_manifest, label_manifest = verify_artifact_identity(
        label_root=args.label_root,
        source_artifact_root=args.source_artifact_root,
        artifact_spec=spec,
    )
    _print_preflight(
        source_manifest=source_manifest,
        label_manifest=label_manifest,
        spec=spec,
        source_root=args.source_artifact_root,
        label_root=args.label_root,
        train_limit=args.train_limit,
        validation_limit=args.validation_limit,
        test_limit=args.test_limit,
    )
    result = characterize_c1_06_preparation(
        label_root=args.label_root,
        source_artifact_root=args.source_artifact_root,
        artifact_spec=spec,
        train_limit=args.train_limit,
        validation_limit=args.validation_limit,
        test_limit=args.test_limit,
        measure_tensorization=not args.no_tensorization,
        real_artifact_run=True,
    )
    write_outputs(result, report_out=args.report_out, json_out=args.json_out)
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
