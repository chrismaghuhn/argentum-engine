"""Shared C0-authority gates for deterministic semantic tie discriminators."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any

from .canonical_json import canonical_json


TIE_DISCRIMINATOR_FORBIDDEN_KEYS = frozenset({
    "id",
    "actionId",
    "decisionId",
    "sourceEntityId",
    "targetEntityIds",
    "entityId",
    "sourceId",
    "targetId",
    "playerId",
    "cardId",
    "rowIndex",
    "sourceBindingOrdinal",
    "allocationOrder",
    "batchSlot",
    "giftRecipient",
    "casualtyCreature",
    "sourceOrdinal",
    "candidateIndex",
    "orderIndex",
    "iterationOrder",
})


def is_forbidden_tie_discriminator_key(key: str) -> bool:
    return key in TIE_DISCRIMINATOR_FORBIDDEN_KEYS or key.endswith("Id") or key.endswith("Ids")


def has_forbidden_tie_discriminator_field(value: Any) -> bool:
    if isinstance(value, dict):
        for key, child in value.items():
            if is_forbidden_tie_discriminator_key(key) or has_forbidden_tie_discriminator_field(child):
                return True
    elif isinstance(value, list):
        return any(has_forbidden_tie_discriminator_field(child) for child in value)
    return False


def has_forbidden_tie_discriminator_literal(value: Any, raw_values: set[str]) -> bool:
    if isinstance(value, str) and value in raw_values:
        return True
    if isinstance(value, dict):
        return any(has_forbidden_tie_discriminator_literal(child, raw_values) for child in value.values())
    if isinstance(value, list):
        return any(has_forbidden_tie_discriminator_literal(child, raw_values) for child in value)
    return False


@dataclass(frozen=True)
class SemanticTieDiscriminator:
    """A source-validated canonical semantic tie discriminator."""

    canonical_value: str
    _source_validated: bool = field(default=False, init=False, repr=False, compare=False)

    @classmethod
    def from_json(cls, value: str, *, forbidden_raw_values: set[str]) -> "SemanticTieDiscriminator":
        parsed = _parse_canonical_object(value)
        if has_forbidden_tie_discriminator_field(parsed):
            raise ValueError("tie discriminator contains a forbidden field")
        if has_forbidden_tie_discriminator_literal(parsed, forbidden_raw_values):
            raise ValueError("tie discriminator contains a raw source entity literal")
        discriminator = cls(value)
        object.__setattr__(discriminator, "_source_validated", True)
        return discriminator

    @classmethod
    def try_from_json(cls, value: str, *, forbidden_raw_values: set[str]) -> "SemanticTieDiscriminator | None":
        try:
            return cls.from_json(value, forbidden_raw_values=forbidden_raw_values)
        except (TypeError, ValueError, json.JSONDecodeError):
            return None

    def __post_init__(self) -> None:
        if not isinstance(self.canonical_value, str) or not self.canonical_value:
            raise ValueError("canonical tie discriminator must be a non-empty string")


def _parse_canonical_object(value: str) -> dict[str, Any]:
    if not isinstance(value, str) or not value:
        raise ValueError("tie discriminator must be a non-empty string")

    def reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, child in pairs:
            if key in result:
                raise ValueError("tie discriminator contains duplicate JSON keys")
            result[key] = child
        return result

    parsed = json.loads(value, object_pairs_hook=reject_duplicate_pairs)
    if not isinstance(parsed, dict) or canonical_json(parsed) != value:
        raise ValueError("tie discriminator is not canonical JSON object text")
    return parsed
