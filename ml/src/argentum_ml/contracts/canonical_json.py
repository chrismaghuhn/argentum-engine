"""A3-compatible canonical JSON with fail-closed value admission."""

from __future__ import annotations

import hashlib
import json
import math
from functools import lru_cache
from typing import Any


class _RawJsonNumber:
    """A JSON number whose source spelling must survive A3 canonicalization."""

    __slots__ = ("text",)

    def __init__(self, text: str) -> None:
        self.text = text


def _validate(value: Any, path: str = "$") -> None:
    if value is None or isinstance(value, (str, bool, int)):
        return
    if isinstance(value, _RawJsonNumber):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError(f"non-finite number at {path}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _validate(item, f"{path}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise TypeError(f"non-string object key at {path}")
            _validate(item, f"{path}.{key}")
        return
    raise TypeError(f"unsupported JSON value at {path}: {type(value).__name__}")


def canonical_json(value: Any) -> str:
    """Return compact UTF-8-independent canonical JSON text.

    Object keys are recursively sorted by the A3 UTF-16 key order; list order is retained.
    """

    _validate(value)
    return _canonical_text(value)


def _canonical_text(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("non-finite number")
        return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    if isinstance(value, _RawJsonNumber):
        return value.text
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    if isinstance(value, list):
        return "[" + ",".join(_canonical_text(item) for item in value) + "]"
    if isinstance(value, dict):
        entries = sorted(value.items(), key=lambda entry: _utf16_sort_key(entry[0]))
        return "{" + ",".join(
            _canonical_text(key) + ":" + _canonical_text(child)
            for key, child in entries
        ) + "}"
    raise TypeError(f"unsupported JSON value: {type(value).__name__}")


@lru_cache(maxsize=1024)
def _utf16_sort_key(value: str) -> bytes:
    return value.encode("utf-16-be", errors="surrogatepass")


def canonical_bytes(value: Any) -> bytes:
    return canonical_json(value).encode("utf-8")


def sha256_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()
