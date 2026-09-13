"""The dependency-free score-provider boundary used by C1 inference."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class ScoreProvider(Protocol):
    """A model-independent scorer for one immutable model input and its candidates."""

    @property
    def checkpoint_id(self) -> str:
        """Exact checkpoint identity used by the provider's weights."""

    @property
    def numeric_profile_class(self) -> str:
        """Numeric execution profile certified for the provider."""

    def score(
        self,
        model_input: Mapping[str, Any],
        candidates: Sequence[Mapping[str, Any]],
    ) -> Sequence[float]:
        """Return one finite score for each supplied candidate feature view."""
