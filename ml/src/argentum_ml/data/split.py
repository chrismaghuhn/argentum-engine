"""Python parity for the Kotlin C0-02 episode split."""

from __future__ import annotations

import hashlib
import re

from ..contracts.identities import SPLIT_CONTRACT_IDENTITY

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


def bucket(semantic_episode_id: str) -> int:
    if not isinstance(semantic_episode_id, str) or not _SHA256.fullmatch(semantic_episode_id):
        raise ValueError("semantic_episode_id must be lowercase SHA-256 hex")
    digest = hashlib.sha256(
        f"{SPLIT_CONTRACT_IDENTITY}\n{semantic_episode_id}".encode("utf-8")
    ).digest()
    return int.from_bytes(digest[:8], "big", signed=False) % 100


def assign_partition(semantic_episode_id: str) -> str:
    value = bucket(semantic_episode_id)
    if value < 80:
        return "TRAIN"
    if value < 90:
        return "VALIDATION"
    return "TEST"
