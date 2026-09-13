"""Pure public-channel scoring for PublicObservationTeacherV1."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from numbers import Real
from typing import Any, Protocol

from ..contracts.canonical_json import canonical_json
from .contracts import GenericScoringConfigurationV1, TeacherInputError


_POLICY_FORBIDDEN_KEYS = frozenset(
    {
        "target",
        "targetEntityIds",
        "sourceEntityId",
        "sourceId",
        "targetId",
        "binding",
        "provenance",
        "sourceReference",
        "actionId",
        "decisionId",
        "policySeed",
        "outcome",
        "winnerId",
        "gameState",
        "hiddenWorld",
        "engineSeed",
        "rowIndex",
        "candidateIndex",
        "workerIndex",
        "batchSlot",
        "allocationOrder",
    }
)


class PublicObservationScorer(Protocol):
    """The scorer sees no binding, ordinal, target, or RNG channel."""

    def score(
        self,
        model_input: Mapping[str, Any],
        candidate_features: Sequence[Mapping[str, Any]],
    ) -> Sequence[Real]:
        ...


@dataclass(frozen=True)
class GenericPublicObservationScorer:
    """Reference scorer using only configured generic candidate-kind weights."""

    scoring_configuration: GenericScoringConfigurationV1

    def score(
        self,
        model_input: Mapping[str, Any],
        candidate_features: Sequence[Mapping[str, Any]],
    ) -> tuple[float, ...]:
        if not isinstance(model_input, Mapping):
            raise TeacherInputError("public model input must be an object")
        _reject_policy_forbidden_fields(model_input, "model input")
        domain = model_input.get("domain")
        if not isinstance(domain, Mapping):
            raise TeacherInputError("public model input domain must be an object")
        domain_candidates = domain.get("candidates")
        if not isinstance(domain_candidates, (list, tuple)):
            raise TeacherInputError("flat public domain must retain its candidate list")
        features = tuple(candidate_features)
        if len(domain_candidates) != len(features):
            raise TeacherInputError("public scorer received an incomplete candidate domain")
        scores: list[float] = []
        for index, (domain_candidate, feature_view) in enumerate(zip(domain_candidates, features)):
            if not isinstance(feature_view, Mapping):
                raise TeacherInputError(f"candidate feature view {index} must be an object")
            if not isinstance(domain_candidate, Mapping):
                raise TeacherInputError(f"domain candidate {index} must be an object")
            _reject_policy_forbidden_fields(feature_view, f"candidate feature view {index}")
            if canonical_json(dict(domain_candidate)) != canonical_json(dict(feature_view)):
                raise TeacherInputError(f"candidate feature view {index} does not retain the public domain")
            kind = feature_view.get("kind")
            if not isinstance(kind, str) or not kind:
                raise TeacherInputError(f"candidate feature view {index} has no generic kind")
            scores.append(self.scoring_configuration.score_for_kind(kind))
        return tuple(scores)


def _reject_policy_forbidden_fields(value: Any, label: str) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            if key in _POLICY_FORBIDDEN_KEYS:
                raise TeacherInputError(f"{label} contains policy-forbidden field {key}")
            _reject_policy_forbidden_fields(child, label)
    elif isinstance(value, (list, tuple)):
        for child in value:
            _reject_policy_forbidden_fields(child, label)
