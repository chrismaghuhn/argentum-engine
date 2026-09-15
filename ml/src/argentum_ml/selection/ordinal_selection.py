"""Binding-free ordinal selection shared by live and exact-bound policies."""

from __future__ import annotations

import math
from dataclasses import dataclass
from numbers import Real
from typing import Sequence

from ..contracts.tie_discriminator import SemanticTieDiscriminator
from .policy_tie_rng import PolicyTieRngStateV1


class OrdinalSelectionError(ValueError):
    """Raised when ordinal-selection input is incomplete or invalid."""


@dataclass(frozen=True)
class SelectionAddressCandidate:
    """A model-facing candidate address with no executable source binding."""

    source_binding_ordinal: int
    score: Real
    candidate_presence: bool
    candidate_executable_support: bool
    deterministic_semantic_tie_discriminator: SemanticTieDiscriminator | None

    def __post_init__(self) -> None:
        if (
            isinstance(self.source_binding_ordinal, bool)
            or not isinstance(self.source_binding_ordinal, int)
            or self.source_binding_ordinal < 0
        ):
            raise OrdinalSelectionError("source binding ordinal must be non-negative")
        if isinstance(self.score, bool) or not isinstance(self.score, Real):
            raise OrdinalSelectionError("candidate score must be a real number")
        if not isinstance(self.candidate_presence, bool) or not isinstance(
            self.candidate_executable_support, bool
        ):
            raise OrdinalSelectionError("candidate masks must be booleans")
        if self.candidate_executable_support and not self.candidate_presence:
            raise OrdinalSelectionError("an absent candidate cannot be executable")
        if self.deterministic_semantic_tie_discriminator is not None and (
            not isinstance(
                self.deterministic_semantic_tie_discriminator,
                SemanticTieDiscriminator,
            )
            or not self.deterministic_semantic_tie_discriminator._source_validated
        ):
            raise OrdinalSelectionError("tie discriminator must be a non-empty string")


@dataclass(frozen=True)
class OrdinalSelectionResult:
    selected_source_binding_ordinal: int
    rng_state: PolicyTieRngStateV1
    rng_draw_count: int
    cursor_before: int
    cursor_after: int


def select_ordinal(
    candidates: Sequence[SelectionAddressCandidate],
    rng_state: PolicyTieRngStateV1,
) -> OrdinalSelectionResult:
    """Resolve one policy tie using only ordinal addresses and control metadata."""

    values = tuple(candidates)
    if not values:
        raise OrdinalSelectionError("ordinal selection requires at least one candidate")
    if not isinstance(rng_state, PolicyTieRngStateV1):
        raise OrdinalSelectionError("ordinal selection requires PolicyTieRngStateV1")

    ordinals = [candidate.source_binding_ordinal for candidate in values]
    if len(set(ordinals)) != len(ordinals):
        raise OrdinalSelectionError("source binding ordinals must be unique")
    for candidate in values:
        if candidate.candidate_presence and not math.isfinite(float(candidate.score)):
            raise OrdinalSelectionError("present candidate scores must be finite")

    eligible = [
        candidate
        for candidate in values
        if candidate.candidate_presence and candidate.candidate_executable_support
    ]
    if not eligible:
        raise OrdinalSelectionError("ordinal selection has no executable present candidate")

    maximum = max(candidate.score for candidate in eligible)
    tied = [candidate for candidate in eligible if candidate.score == maximum]
    cursor_before = rng_state.cursor
    if len(tied) == 1:
        return _result(tied[0], rng_state, cursor_before)

    discriminators = [
        candidate.deterministic_semantic_tie_discriminator for candidate in tied
    ]
    if _has_unique_valid_discriminators(discriminators):
        winner = min(
            tied,
            key=lambda candidate: candidate.deterministic_semantic_tie_discriminator.canonical_value
            if candidate.deterministic_semantic_tie_discriminator
            else "",
        )
        return _result(winner, rng_state, cursor_before)

    ordered = sorted(tied, key=lambda candidate: candidate.source_binding_ordinal)
    address, after = rng_state.uniform_below(len(ordered))
    return _result(ordered[address], after, cursor_before)


def _has_unique_valid_discriminators(
    values: Sequence[SemanticTieDiscriminator | None],
) -> bool:
    return all(_valid_discriminator(value) for value in values) and len(set(values)) == len(values)


def _valid_discriminator(value: SemanticTieDiscriminator | None) -> bool:
    return isinstance(value, SemanticTieDiscriminator) and value._source_validated


def _result(
    winner: SelectionAddressCandidate,
    rng_state: PolicyTieRngStateV1,
    cursor_before: int,
) -> OrdinalSelectionResult:
    return OrdinalSelectionResult(
        selected_source_binding_ordinal=winner.source_binding_ordinal,
        rng_state=rng_state,
        rng_draw_count=rng_state.cursor - cursor_before,
        cursor_before=cursor_before,
        cursor_after=rng_state.cursor,
    )
