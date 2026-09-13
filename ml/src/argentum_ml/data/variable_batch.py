"""Immutable transport for variable-size legal domains.

This module preserves every supplied candidate.  Padding is represented only by a mask;
there is deliberately no truncation, top-k, or maximum-candidate operation.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Iterable, Sequence

from ..contracts.canonical_json import canonical_json


class _FrozenDict(dict[str, Any]):
    """Dict-compatible immutable JSON object used at the public boundary."""

    def __init__(self, values: dict[str, Any]) -> None:
        dict.__init__(self, values)

    @staticmethod
    def _immutable(*args: Any, **kwargs: Any) -> None:
        raise TypeError("JSON tree is immutable")

    __setitem__ = __delitem__ = clear = pop = popitem = setdefault = update = _immutable

    def __ior__(self, other: Any) -> "_FrozenDict":
        self._immutable()
        return self


class _FrozenList(list[Any]):
    """List-compatible immutable JSON array used at the public boundary."""

    def __init__(self, values: list[Any]) -> None:
        list.__init__(self, values)

    @staticmethod
    def _immutable(*args: Any, **kwargs: Any) -> None:
        raise TypeError("JSON tree is immutable")

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
    if isinstance(value, tuple):
        return tuple(_deep_freeze(child) for child in value)
    return value


@dataclass(frozen=True)
class CandidateFeature:
    feature_view: dict[str, Any]
    source_binding_ordinal: int
    present: bool
    executable_support: bool

    def __post_init__(self) -> None:
        if not isinstance(self.feature_view, dict):
            raise ValueError("candidate feature_view must be an object")
        frozen_feature_view = _deep_freeze(self.feature_view)
        canonical_json(frozen_feature_view)
        object.__setattr__(self, "feature_view", frozen_feature_view)
        if not isinstance(self.source_binding_ordinal, int) or isinstance(self.source_binding_ordinal, bool):
            raise ValueError("source_binding_ordinal must be an integer")
        if self.source_binding_ordinal < 0:
            raise ValueError("source_binding_ordinal must not be negative")
        if not isinstance(self.present, bool) or not isinstance(self.executable_support, bool):
            raise ValueError("candidate masks must be booleans")
        if self.executable_support and not self.present:
            raise ValueError("an absent candidate cannot have executable support")


@dataclass(frozen=True)
class VariableDomainItem:
    model_input: dict[str, Any]
    candidates: tuple[CandidateFeature, ...] | Sequence[CandidateFeature]
    structured_domain: dict[str, Any] | None
    target_binding_ordinal: int | None

    def __post_init__(self) -> None:
        if not isinstance(self.model_input, dict):
            raise ValueError("model_input must be an object")
        frozen_model_input = _deep_freeze(self.model_input)
        canonical_json(frozen_model_input)
        object.__setattr__(self, "model_input", frozen_model_input)
        candidates = tuple(self.candidates)
        object.__setattr__(self, "candidates", candidates)
        if any(not isinstance(candidate, CandidateFeature) for candidate in candidates):
            raise ValueError("candidates must contain CandidateFeature values")
        ordinals = [candidate.source_binding_ordinal for candidate in candidates]
        if len(set(ordinals)) != len(ordinals):
            raise ValueError("candidate source_binding_ordinals must be unique")
        if self.structured_domain is not None:
            if not isinstance(self.structured_domain, dict):
                raise ValueError("structured_domain must be an object")
            frozen_structured_domain = _deep_freeze(self.structured_domain)
            canonical_json(frozen_structured_domain)
            object.__setattr__(self, "structured_domain", frozen_structured_domain)
            if candidates:
                raise ValueError("structured items cannot carry flat candidates")
            if self.target_binding_ordinal is not None:
                raise ValueError("structured items cannot carry a flat target binding ordinal")
            domain = _domain(self.model_input)
            if canonical_json(domain.get("structuredType")) != canonical_json(self.structured_domain):
                raise ValueError("structured_domain is not retained exactly in model_input")
            if domain.get("candidates") not in (None, []):
                raise ValueError("structured model_input cannot carry flat candidates")
        else:
            if not isinstance(self.target_binding_ordinal, int) or isinstance(self.target_binding_ordinal, bool):
                raise ValueError("flat items require a target_binding_ordinal")
            if self.target_binding_ordinal not in ordinals:
                raise ValueError("target_binding_ordinal is outside the supplied candidates")
            target = next(candidate for candidate in candidates if candidate.source_binding_ordinal == self.target_binding_ordinal)
            if not target.present:
                raise ValueError("target_binding_ordinal cannot address an absent candidate")
            domain = _domain(self.model_input)
            model_candidates = domain.get("candidates")
            if not isinstance(model_candidates, list) or len(model_candidates) != len(candidates):
                raise ValueError("model_input does not retain every candidate")
            for index, candidate in enumerate(candidates):
                if canonical_json(model_candidates[index]) != canonical_json(candidate.feature_view):
                    raise ValueError("model_input candidate order/content differs from transport candidates")
            if domain.get("structuredType") is not None:
                raise ValueError("flat model_input cannot carry a structured domain")

    def permute_candidates(self, permutation: Sequence[int]) -> "VariableDomainItem":
        if self.structured_domain is not None:
            raise ValueError("structured items do not have flat candidate permutations")
        order = _validate_permutation(permutation, len(self.candidates))
        domain = _domain(self.model_input)
        reordered_domain = dict(domain)
        reordered_domain["candidates"] = [
            domain["candidates"][index]
            for index in order
        ]
        reordered_input = dict(self.model_input)
        reordered_input["domain"] = reordered_domain
        return VariableDomainItem(
            model_input=reordered_input,
            candidates=tuple(self.candidates[index] for index in order),
            structured_domain=self.structured_domain,
            target_binding_ordinal=self.target_binding_ordinal,
        )


@dataclass(frozen=True)
class VariableDomainBatch:
    items: tuple[VariableDomainItem, ...] | Sequence[VariableDomainItem]
    item_offsets: tuple[int, ...] | Sequence[int]
    candidate_offsets: tuple[int, ...] | Sequence[int]
    padding_mask: tuple[tuple[bool, ...], ...] | Sequence[Sequence[bool]]
    _padded_width: int = field(init=False, repr=False)

    def __post_init__(self) -> None:
        items = tuple(self.items)
        item_offsets = tuple(self.item_offsets)
        candidate_offsets = tuple(self.candidate_offsets)
        padding_mask = tuple(tuple(row) for row in self.padding_mask)
        object.__setattr__(self, "items", items)
        object.__setattr__(self, "item_offsets", item_offsets)
        object.__setattr__(self, "candidate_offsets", candidate_offsets)
        object.__setattr__(self, "padding_mask", padding_mask)
        if any(not isinstance(item, VariableDomainItem) for item in items):
            raise ValueError("items must contain VariableDomainItem values")
        if item_offsets != tuple(range(len(items) + 1)):
            raise ValueError("item_offsets must address each item exactly once")
        expected_candidate_offsets = [0]
        for item in items:
            expected_candidate_offsets.append(expected_candidate_offsets[-1] + len(item.candidates))
        if candidate_offsets != tuple(expected_candidate_offsets):
            raise ValueError("candidate_offsets must preserve every candidate boundary")
        padded_width = max((len(item.candidates) for item in items), default=0)
        object.__setattr__(self, "_padded_width", padded_width)
        if len(padding_mask) != len(items):
            raise ValueError("padding_mask must contain one row per item")
        for index, (row, item) in enumerate(zip(padding_mask, items)):
            if len(row) != padded_width or any(not isinstance(value, bool) for value in row):
                raise ValueError("padding_mask rows must have the common padded width")
            expected = tuple(slot >= len(item.candidates) for slot in range(padded_width))
            if row != expected:
                raise ValueError(f"padding_mask row {index} does not match candidate padding")

    @classmethod
    def from_items(cls, items: Iterable[VariableDomainItem]) -> "VariableDomainBatch":
        item_values = tuple(items)
        item_offsets = tuple(range(len(item_values) + 1))
        candidate_offsets = [0]
        for item in item_values:
            candidate_offsets.append(candidate_offsets[-1] + len(item.candidates))
        width = max((len(item.candidates) for item in item_values), default=0)
        padding_mask = tuple(
            tuple(slot >= len(item.candidates) for slot in range(width))
            for item in item_values
        )
        return cls(item_values, item_offsets, tuple(candidate_offsets), padding_mask)

    @property
    def padded_width(self) -> int:
        return self._padded_width

    @property
    def flattened_candidates(self) -> tuple[CandidateFeature, ...]:
        return tuple(candidate for item in self.items for candidate in item.candidates)

    @property
    def presence_mask(self) -> tuple[bool, ...]:
        return tuple(candidate.present for candidate in self.flattened_candidates)

    @property
    def executable_support_mask(self) -> tuple[bool, ...]:
        return tuple(candidate.executable_support for candidate in self.flattened_candidates)

    def permute_candidates(
        self,
        item_index: int,
        permutation: Sequence[int],
    ) -> "VariableDomainBatch":
        if not isinstance(item_index, int) or isinstance(item_index, bool):
            raise ValueError("item_index must be an integer")
        if item_index < 0 or item_index >= len(self.items):
            raise IndexError("item_index outside batch")
        reordered = list(self.items)
        reordered[item_index] = reordered[item_index].permute_candidates(permutation)
        return VariableDomainBatch.from_items(reordered)

    def permute_all(
        self,
        permutations: Sequence[Sequence[int]],
    ) -> "VariableDomainBatch":
        if len(permutations) != len(self.items):
            raise ValueError("permutations must contain one order per item")
        return VariableDomainBatch.from_items(
            item.permute_candidates(permutation)
            for item, permutation in zip(self.items, permutations)
        )


def permute_item_candidates(
    item: VariableDomainItem,
    permutation: Sequence[int],
) -> VariableDomainItem:
    """Transport one item's candidate records and source ordinals together."""

    return item.permute_candidates(permutation)


def _validate_permutation(permutation: Sequence[int], size: int) -> tuple[int, ...]:
    values = tuple(permutation)
    if len(values) != size or any(not isinstance(value, int) or isinstance(value, bool) for value in values):
        raise ValueError("permutation must contain every candidate index exactly once")
    if set(values) != set(range(size)):
        raise ValueError("permutation must contain every candidate index exactly once")
    return values


def _domain(model_input: dict[str, Any]) -> dict[str, Any]:
    domain = model_input.get("domain")
    if not isinstance(domain, dict):
        raise ValueError("model_input.domain must be an object")
    return domain
