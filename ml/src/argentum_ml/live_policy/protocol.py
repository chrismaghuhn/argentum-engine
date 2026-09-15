"""Bounded canonical binary framing for the local Python policy subprocess."""

from __future__ import annotations

import json
import struct
from typing import Any, BinaryIO, Mapping

from ..contracts.canonical_json import canonical_bytes
from .errors import LivePolicyProtocolError


FRAME_HEADER_BYTES = 4
MAX_FRAME_BYTES = 16 * 1024 * 1024


class FramedProtocolError(LivePolicyProtocolError):
    """A frame is truncated, oversized, non-canonical, or not a JSON object."""


def encode_frame(payload: Mapping[str, Any]) -> bytes:
    if not isinstance(payload, Mapping) or not isinstance(payload, dict):
        raise FramedProtocolError("framed payload must be a JSON object", code="FRAME_PAYLOAD_INVALID")
    try:
        encoded = canonical_bytes(dict(payload))
    except (TypeError, ValueError) as exc:
        raise FramedProtocolError("framed payload is not canonical JSON", code="FRAME_PAYLOAD_INVALID") from exc
    _require_frame_size(len(encoded))
    return struct.pack(">I", len(encoded)) + encoded


def decode_frame(frame: bytes) -> dict[str, Any]:
    if not isinstance(frame, bytes) or len(frame) < FRAME_HEADER_BYTES:
        raise FramedProtocolError("framed payload has a truncated header", code="FRAME_TRUNCATED")
    size = struct.unpack(">I", frame[:FRAME_HEADER_BYTES])[0]
    _require_frame_size(size)
    if len(frame) != FRAME_HEADER_BYTES + size:
        raise FramedProtocolError("framed payload length does not match its header", code="FRAME_TRUNCATED")
    return _decode_payload(frame[FRAME_HEADER_BYTES:])


def read_frame(stream: BinaryIO) -> dict[str, Any] | None:
    header = _read_exact(stream, FRAME_HEADER_BYTES, allow_clean_eof=True)
    if header is None:
        return None
    size = struct.unpack(">I", header)[0]
    _require_frame_size(size)
    payload = _read_exact(stream, size, allow_clean_eof=False)
    if payload is None:
        raise FramedProtocolError("framed payload is truncated", code="FRAME_TRUNCATED")
    return _decode_payload(payload)


def write_frame(stream: BinaryIO, payload: Mapping[str, Any]) -> None:
    encoded = encode_frame(payload)
    try:
        offset = 0
        while offset < len(encoded):
            written = stream.write(encoded[offset:])
            if not isinstance(written, int) or written <= 0:
                raise FramedProtocolError("framed stream did not accept the payload", code="FRAME_WRITE_FAILURE")
            offset += written
        stream.flush()
    except FramedProtocolError:
        raise
    except (OSError, ValueError) as exc:
        raise FramedProtocolError("framed stream write failed", code="FRAME_WRITE_FAILURE") from exc


def _require_frame_size(size: int) -> None:
    if size <= 0 or size > MAX_FRAME_BYTES:
        raise FramedProtocolError("framed payload exceeds the bounded protocol size", code="FRAME_SIZE_INVALID")


def _read_exact(
    stream: BinaryIO,
    size: int,
    *,
    allow_clean_eof: bool,
) -> bytes | None:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        try:
            chunk = stream.read(remaining)
        except (OSError, ValueError) as exc:
            raise FramedProtocolError("framed stream read failed", code="FRAME_READ_FAILURE") from exc
        if not isinstance(chunk, bytes):
            raise FramedProtocolError("framed stream returned non-bytes", code="FRAME_READ_FAILURE")
        if not chunk:
            if allow_clean_eof and not chunks:
                return None
            return None
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def _decode_payload(payload: bytes) -> dict[str, Any]:
    try:
        decoded = json.loads(
            payload.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_pairs,
            parse_constant=_reject_non_json_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        if isinstance(exc, FramedProtocolError):
            raise
        raise FramedProtocolError("framed payload is not valid JSON", code="FRAME_JSON_INVALID") from exc
    if not isinstance(decoded, dict):
        raise FramedProtocolError("framed payload must decode to a JSON object", code="FRAME_PAYLOAD_INVALID")
    try:
        if canonical_bytes(decoded) != payload:
            raise FramedProtocolError("framed payload is not canonical JSON", code="FRAME_NONCANONICAL")
    except (TypeError, ValueError) as exc:
        if isinstance(exc, FramedProtocolError):
            raise
        raise FramedProtocolError("framed payload contains an unsupported JSON value", code="FRAME_PAYLOAD_INVALID") from exc
    return decoded


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise FramedProtocolError("framed payload contains duplicate JSON keys", code="FRAME_JSON_INVALID")
        result[key] = value
    return result


def _reject_non_json_constant(value: str) -> None:
    raise FramedProtocolError(f"framed payload contains non-JSON constant {value}", code="FRAME_JSON_INVALID")
