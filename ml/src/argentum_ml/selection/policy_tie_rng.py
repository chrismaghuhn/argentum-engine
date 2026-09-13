"""Exact C0-04B PolicyTieRng V1 state and stream derivation."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from ..contracts.canonical_json import canonical_bytes
from ..contracts.identities import POLICY_TIE_RNG_IDENTITY, POLICY_TIE_STREAM_IDENTITY

UINT64_MAX = (1 << 64) - 1
_LONG_MIN = -(1 << 63)
_LONG_MAX = (1 << 63) - 1
_RAW_WORD_NAMESPACE = b"argentum-ml-policy-tie-rng-word@v1\x00"
POLICY_RNG_IDENTITY = POLICY_TIE_RNG_IDENTITY


class PolicyTieRngError(ValueError):
    """Raised when PolicyTieRng V1 input or state is invalid."""


def policy_seed_bits_hex(policy_seed: int) -> str:
    if isinstance(policy_seed, bool) or not isinstance(policy_seed, int):
        raise PolicyTieRngError("policy_seed must be a signed Kotlin Long integer")
    if policy_seed < _LONG_MIN or policy_seed > _LONG_MAX:
        raise PolicyTieRngError("policy_seed is outside the signed Kotlin Long range")
    return f"{policy_seed & UINT64_MAX:016x}"


def _validate_identity(policy_rng_identity: str) -> None:
    if policy_rng_identity != POLICY_RNG_IDENTITY:
        raise PolicyTieRngError("policy_rng_identity is not PolicyTieRng V1")


def _validate_seat_index(seat_index: int) -> None:
    if isinstance(seat_index, bool) or not isinstance(seat_index, int) or seat_index < 0:
        raise PolicyTieRngError("seat_index must be a non-negative integer")


def derive_stream_key(
    policy_seed: int,
    seat_index: int,
    *,
    policy_rng_identity: str = POLICY_RNG_IDENTITY,
) -> bytes:
    """Derive the exact 32-byte stream key for one policy instance."""

    _validate_identity(policy_rng_identity)
    seed_bits = policy_seed_bits_hex(policy_seed)
    _validate_seat_index(seat_index)
    payload = {
        "schema": POLICY_TIE_STREAM_IDENTITY,
        "policySeedBitsHex": seed_bits,
        "seatIndex": seat_index,
    }
    return hashlib.sha256(canonical_bytes(payload)).digest()


@dataclass(frozen=True)
class PolicyTieRngStateV1:
    stream_key: bytes
    cursor: int = 0

    def __post_init__(self) -> None:
        if not isinstance(self.stream_key, bytes) or len(self.stream_key) != 32:
            raise PolicyTieRngError("stream_key must contain exactly 32 raw bytes")
        if isinstance(self.cursor, bool) or not isinstance(self.cursor, int):
            raise PolicyTieRngError("cursor must be an unsigned 64-bit integer")
        if self.cursor < 0 or self.cursor > UINT64_MAX:
            raise PolicyTieRngError("cursor is outside the unsigned 64-bit range")

    @classmethod
    def from_policy_seed(
        cls,
        policy_seed: int,
        seat_index: int,
        *,
        policy_rng_identity: str = POLICY_RNG_IDENTITY,
    ) -> "PolicyTieRngStateV1":
        return cls(
            stream_key=derive_stream_key(
                policy_seed,
                seat_index,
                policy_rng_identity=policy_rng_identity,
            ),
            cursor=0,
        )

    def next_raw_word(self) -> tuple[int, "PolicyTieRngStateV1"]:
        if self.cursor == UINT64_MAX:
            raise PolicyTieRngError("PolicyTieRng cursor exhausted")
        digest = hashlib.sha256(
            _RAW_WORD_NAMESPACE + self.stream_key + self.cursor.to_bytes(8, "big")
        ).digest()
        word = int.from_bytes(digest[:8], "big", signed=False)
        return word, PolicyTieRngStateV1(self.stream_key, self.cursor + 1)

    def uniform_below(self, n: int) -> tuple[int, "PolicyTieRngStateV1"]:
        if isinstance(n, bool) or not isinstance(n, int) or n < 2 or n > UINT64_MAX:
            raise PolicyTieRngError("uniform_below requires 2 <= n <= 2^64-1")
        limit = ((1 << 64) // n) * n
        state = self
        while True:
            word, state = state.next_raw_word()
            if word < limit:
                return word % n, state

    def snapshot(self) -> "PolicyTieRngStateV1":
        return PolicyTieRngStateV1(bytes(self.stream_key), self.cursor)

    @classmethod
    def restore(cls, snapshot: "PolicyTieRngStateV1") -> "PolicyTieRngStateV1":
        if not isinstance(snapshot, cls):
            raise PolicyTieRngError("snapshot must be a PolicyTieRngStateV1")
        return cls(bytes(snapshot.stream_key), snapshot.cursor)

    def fork(self) -> "PolicyTieRngStateV1":
        return self.snapshot()
