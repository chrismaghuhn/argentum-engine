"""Shared C0-authority gates for deterministic semantic tie discriminators."""

from __future__ import annotations

from typing import Any


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
