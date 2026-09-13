"""Model-independent Selection V2 over exact source bindings."""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from numbers import Real
from typing import Any, Sequence

from ..contracts.canonical_json import canonical_json
from ..contracts.tie_discriminator import has_forbidden_tie_discriminator_field
from .policy_tie_rng import PolicyTieRngStateV1


class _FrozenDict(dict[str, Any]):
    def __init__(self, values: dict[str, Any]) -> None:
        dict.__init__(self, values)

    @staticmethod
    def _immutable(*args: Any, **kwargs: Any) -> None:
        raise TypeError("source binding JSON is immutable")

    __setitem__ = __delitem__ = clear = pop = popitem = setdefault = update = _immutable

    def __ior__(self, other: Any) -> "_FrozenDict":
        self._immutable()
        return self


class _FrozenList(list[Any]):
    def __init__(self, values: list[Any]) -> None:
        list.__init__(self, values)

    @staticmethod
    def _immutable(*args: Any, **kwargs: Any) -> None:
        raise TypeError("source binding JSON is immutable")

    __setitem__ = __delitem__ = append = clear = extend = insert = pop = remove = reverse = sort = _immutable

    def __iadd__(self, other: Any) -> "_FrozenList":
        self._immutable()
        return self

    def __imul__(self, other: Any) -> "_FrozenList":
        self._immutable()
        return self


def _deep_freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return _FrozenDict({key: _deep_freeze(child) for key, child in value.items()})
    if isinstance(value, list):
        return _FrozenList([_deep_freeze(child) for child in value])
    return value




class SelectionError(ValueError):
    """Raised when Selection V2 input or binding is incomplete or invalid."""


@dataclass(frozen=True)
class ExactSemanticSourceBinding:
    exact_action: dict[str, Any] | None
    exact_response: dict[str, Any] | None
    source_binding_ordinal_audit: int | None

    def __post_init__(self) -> None:
        if (self.exact_action is None) == (self.exact_response is None):
            raise SelectionError("exact source binding requires exactly one action or response")
        exact = self.exact_action if self.exact_action is not None else self.exact_response
        if not isinstance(exact, dict):
            raise SelectionError("exact source binding value must be an object")
        try:
            frozen = _deep_freeze(exact)
            canonical_json(frozen)
            if self.exact_action is not None:
                object.__setattr__(self, "exact_action", frozen)
            else:
                object.__setattr__(self, "exact_response", frozen)
        except (TypeError, ValueError) as exc:
            raise SelectionError("exact source binding is not canonical JSON-compatible") from exc
        if self.source_binding_ordinal_audit is not None:
            if (
                isinstance(self.source_binding_ordinal_audit, bool)
                or not isinstance(self.source_binding_ordinal_audit, int)
                or self.source_binding_ordinal_audit < 0
            ):
                raise SelectionError("source binding ordinal audit must be non-negative")


@dataclass(frozen=True)
class SelectionCandidate:
    source_binding_ordinal: int
    exact_source_binding: ExactSemanticSourceBinding
    score: Real
    candidate_presence: bool
    candidate_executable_support: bool
    deterministic_semantic_tie_discriminator: str | None

    def __post_init__(self) -> None:
        if (
            isinstance(self.source_binding_ordinal, bool)
            or not isinstance(self.source_binding_ordinal, int)
            or self.source_binding_ordinal < 0
        ):
            raise SelectionError("source binding ordinal must be non-negative")
        if not isinstance(self.exact_source_binding, ExactSemanticSourceBinding):
            raise SelectionError("candidate requires an exact source binding")
        audit = self.exact_source_binding.source_binding_ordinal_audit
        if audit is not None and audit != self.source_binding_ordinal:
            raise SelectionError("candidate binding audit ordinal does not match candidate ordinal")
        if isinstance(self.score, bool) or not isinstance(self.score, Real):
            raise SelectionError("candidate score must be a real number")
        if not isinstance(self.candidate_presence, bool) or not isinstance(self.candidate_executable_support, bool):
            raise SelectionError("candidate masks must be booleans")
        if self.candidate_executable_support and not self.candidate_presence:
            raise SelectionError("an absent candidate cannot be executable")
        if self.deterministic_semantic_tie_discriminator is not None and (
            not isinstance(self.deterministic_semantic_tie_discriminator, str)
            or not self.deterministic_semantic_tie_discriminator
        ):
            raise SelectionError("tie discriminator must be a non-empty string")


@dataclass(frozen=True)
class SelectionResult:
    exact_source_binding: ExactSemanticSourceBinding
    audit_source_binding_ordinal: int
    rng_state: PolicyTieRngStateV1
    rng_draw_count: int
    cursor_before: int
    cursor_after: int


def select_v2(
    candidates: Sequence[SelectionCandidate],
    rng_state: PolicyTieRngStateV1,
) -> SelectionResult:
    """Resolve one exact-max policy tie without physical-row preference."""

    values = tuple(candidates)
    if not values:
        raise SelectionError("Selection V2 requires at least one candidate")
    if not isinstance(rng_state, PolicyTieRngStateV1):
        raise SelectionError("Selection V2 requires PolicyTieRngStateV1")
    ordinals = [candidate.source_binding_ordinal for candidate in values]
    if len(set(ordinals)) != len(ordinals):
        raise SelectionError("source binding ordinals must be unique")
    binding_keys: set[tuple[str | None, str | None]] = set()
    for candidate in values:
        audit = candidate.exact_source_binding.source_binding_ordinal_audit
        if audit is not None and audit != candidate.source_binding_ordinal:
            raise SelectionError("incomplete source binding membership")
        binding = candidate.exact_source_binding
        binding_key = (
            canonical_json(binding.exact_action) if binding.exact_action is not None else None,
            canonical_json(binding.exact_response) if binding.exact_response is not None else None,
        )
        if binding_key in binding_keys:
            raise SelectionError("source binding inverse map is not injective")
        binding_keys.add(binding_key)
        if candidate.candidate_presence and not math.isfinite(float(candidate.score)):
            raise SelectionError("present candidate scores must be finite")
    eligible = [
        candidate
        for candidate in values
        if candidate.candidate_presence and candidate.candidate_executable_support
    ]
    if not eligible:
        raise SelectionError("Selection V2 has no executable present candidate")
    maximum = max(candidate.score for candidate in eligible)
    tied = [candidate for candidate in eligible if candidate.score == maximum]
    cursor_before = rng_state.cursor
    if len(tied) == 1:
        return _result(tied[0], rng_state, cursor_before)
    discriminator_values = [candidate.deterministic_semantic_tie_discriminator for candidate in tied]
    if all(_valid_discriminator(value) for value in discriminator_values) and len(set(discriminator_values)) == len(tied):
        winner = min(tied, key=lambda candidate: candidate.deterministic_semantic_tie_discriminator or "")
        return _result(winner, rng_state, cursor_before)
    ordered = sorted(tied, key=lambda candidate: candidate.source_binding_ordinal)
    address, after = rng_state.uniform_below(len(ordered))
    return _result(ordered[address], after, cursor_before)


def _valid_discriminator(value: str | None) -> bool:
    if not isinstance(value, str) or not value:
        return False
    try:
        parsed = json.loads(value)
        if not isinstance(parsed, dict) or has_forbidden_tie_discriminator_field(parsed):
            return False
        return canonical_json(parsed) == value
    except (TypeError, ValueError, json.JSONDecodeError):
        return False


def _result(
    winner: SelectionCandidate,
    rng_state: PolicyTieRngStateV1,
    cursor_before: int,
) -> SelectionResult:
    return SelectionResult(
        exact_source_binding=winner.exact_source_binding,
        audit_source_binding_ordinal=winner.source_binding_ordinal,
        rng_state=rng_state,
        rng_draw_count=rng_state.cursor - cursor_before,
        cursor_before=cursor_before,
        cursor_after=rng_state.cursor,
    )
