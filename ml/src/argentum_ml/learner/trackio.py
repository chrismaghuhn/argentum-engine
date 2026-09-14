"""Local-only scalar observability for optional learner tooling."""

from __future__ import annotations

import math
import os
import re
from collections.abc import Mapping
from dataclasses import dataclass, field
from numbers import Real
from typing import Any

from .optional import require_optional_module


_ALLOWED_METRICS = frozenset(
    {
        "train_loss",
        "validation_loss",
        "candidate_accuracy",
        "samples_per_sec",
        "batches_per_sec",
    }
)
_ALLOWED_REFERENCES = frozenset(
    {
        "checkpointId",
        "weightContentDigest",
        "sourceDatasetIdentity",
        "trainingRunIdentity",
    }
)
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_SHA256_REFERENCES = frozenset(
    {
        "checkpointId",
        "weightContentDigest",
        "sourceDatasetIdentity",
    }
)
_REMOTE_CONFIGURATION = (
    "TRACKIO_SPACE_ID",
    "TRACKIO_SERVER_URL",
    "TRACKIO_WRITE_TOKEN",
    "TRACKIO_WEBHOOK_URL",
)


class TrackioBoundaryError(ValueError):
    """Raised when local Trackio telemetry violates the Argentum boundary."""


@dataclass
class TrackioRun:
    """A bounded local Trackio run with no Argentum identity of its own."""

    _trackio: Any
    _finished: bool = field(default=False, init=False)

    @classmethod
    def start(
        cls,
        project: str,
        semantic_references: Mapping[str, str] | None = None,
    ) -> "TrackioRun":
        """Start one local Trackio run with explicitly allowed metadata only."""
        if not isinstance(project, str) or not project.strip():
            raise TrackioBoundaryError("Trackio project name must be non-empty")
        references = _validate_references(semantic_references)
        for variable in _REMOTE_CONFIGURATION:
            if os.environ.get(variable):
                raise TrackioBoundaryError(
                    f"local Trackio forbids remote configuration: {variable}"
                )
        trackio = require_optional_module("trackio", "trackio")
        trackio.init(
            project=project,
            config=dict(references),
            embed=False,
            auto_log_gpu=False,
            auto_log_cpu=False,
        )
        return cls(trackio)

    def log(
        self,
        metrics: Mapping[str, Real],
        *,
        step: int | None = None,
    ) -> None:
        """Log only finite scalar learner metrics."""
        if self._finished:
            raise TrackioBoundaryError("Trackio run is already finished")
        if not isinstance(metrics, Mapping):
            raise TrackioBoundaryError("Trackio metrics must be a mapping")
        values = dict(metrics)
        if not set(values).issubset(_ALLOWED_METRICS):
            raise TrackioBoundaryError("Trackio metric name is not allowed")
        for name, value in values.items():
            if isinstance(value, bool) or not isinstance(value, Real):
                raise TrackioBoundaryError(
                    f"Trackio metric {name} must be a finite real number"
                )
            try:
                finite = math.isfinite(float(value))
            except (OverflowError, TypeError, ValueError) as exc:
                raise TrackioBoundaryError(
                    f"Trackio metric {name} must be a finite real number"
                ) from exc
            if not finite:
                raise TrackioBoundaryError(
                    f"Trackio metric {name} must be a finite real number"
                )
        if step is not None and (
            isinstance(step, bool) or not isinstance(step, int) or step < 0
        ):
            raise TrackioBoundaryError("Trackio step must be a non-negative integer")
        self._trackio.log(values, step=step)

    def finish(self) -> None:
        """Finish this run once, even if Trackio finalization raises."""
        if self._finished:
            raise TrackioBoundaryError("Trackio run is already finished")
        try:
            self._trackio.finish()
        finally:
            self._finished = True


def _validate_references(
    semantic_references: Mapping[str, str] | None,
) -> dict[str, str]:
    if semantic_references is None:
        return {}
    if not isinstance(semantic_references, Mapping):
        raise TrackioBoundaryError("Trackio semantic references must be a mapping")
    references = dict(semantic_references)
    if not set(references).issubset(_ALLOWED_REFERENCES):
        raise TrackioBoundaryError("Trackio semantic reference is not allowed")
    for key, value in references.items():
        if not isinstance(value, str) or not value:
            raise TrackioBoundaryError(
                f"Trackio semantic reference {key} must be a non-empty string"
            )
        if key in _SHA256_REFERENCES and _SHA256.fullmatch(value) is None:
            raise TrackioBoundaryError(
                f"Trackio semantic reference {key} must be lowercase SHA-256"
            )
    return references
