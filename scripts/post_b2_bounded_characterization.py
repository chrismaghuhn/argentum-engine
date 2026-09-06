#!/usr/bin/env python3
"""Read-only physical characterization for a finalized Trajectory V1 dataset.

This tool is deliberately not an A7 validator. The accepted Kotlin reader must be run first;
this scanner then reads the same manifest-owned bytes to produce byte distributions and a
deterministic analytical decomposition. It never rewrites canonical dataset files.
"""

from __future__ import annotations

import argparse
import collections
import gzip
import hashlib
import json
import math
import shutil
import statistics
import tarfile
import tempfile
import time
from pathlib import Path
from typing import Any, Iterable, NoReturn


EXPECTED_DATASET_ID = (
    "69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03"
)
EXPECTED_MANIFEST_DIGEST = (
    "de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2"
)

COMPONENTS = (
    "PlayerObservationV1",
    "CompleteLegalDomain",
    "CandidateDomainDigest / decision identity",
    "Chosen semantic input/response",
    "Decision/frame metadata",
    "Episode/provenance",
    "Closure metadata",
    "Replay linkage",
    "Other",
)

DECISION_RECORD_KEYS = {
    "version",
    "schemaIdentity",
    "decisionIndex",
    "replayActionIndex",
    "replayFrameIndex",
    "perspectivePlayerId",
    "decisionKind",
    "observationBefore",
    "completeLegalDomain",
    "candidateDomainDigest",
    "semanticDecisionId",
    "chosenSemanticAction",
    "chosenSemanticResponse",
}

FRAME_KEYS = {
    "recordType",
    "storageSchemaVersion",
    "storageSchemaIdentity",
    "trajectorySchemaVersion",
    "semanticEpisodeId",
    "collectionJobId",
}


class CharacterizationError(RuntimeError):
    """Raised when analytical input does not match the accepted physical contract."""


def fail(message: str) -> NoReturn:
    raise CharacterizationError(message)


def canonical_member_spans(text: str) -> list[dict[str, Any]]:
    """Return exact character spans for one JSON object's members.

    The input is already canonical bytes from the accepted writer. The JSON decoder is used only
    to find value boundaries; byte counts are taken from the original UTF-8 text, not re-encoded
    JSON, so attribution cannot drift because of a second serializer.
    """

    if not text.startswith("{") or not text.endswith("}"):
        fail("expected a JSON object for byte attribution")
    decoder = json.JSONDecoder()
    index = 1
    members: list[dict[str, Any]] = []
    while True:
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text) - 1:
            break
        key_start = index
        key, key_end = decoder.raw_decode(text, index)
        if not isinstance(key, str):
            fail("JSON object member key was not a string")
        index = key_end
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text) or text[index] != ":":
            fail(f"missing colon after JSON member {key!r}")
        value_start = index + 1
        while value_start < len(text) and text[value_start].isspace():
            value_start += 1
        _, value_end = decoder.raw_decode(text, value_start)
        members.append(
            {
                "key": key,
                "start": key_start,
                "valueStart": value_start,
                "valueEnd": value_end,
                "end": value_end,
            }
        )
        index = value_end
        while index < len(text) and text[index].isspace():
            index += 1
        if index < len(text) and text[index] == ",":
            index += 1
            continue
        if index < len(text) and text[index] == "}":
            break
        fail(f"malformed JSON object after member {key!r}")
    return members


def span_bytes(text: str, span: dict[str, Any]) -> int:
    return len(text[span["start"] : span["end"]].encode("utf-8"))


def value_bytes(text: str, span: dict[str, Any]) -> int:
    return len(text[span["valueStart"] : span["valueEnd"]].encode("utf-8"))


def object_overhead_bytes(text: str, spans: Iterable[dict[str, Any]]) -> int:
    return len(text.encode("utf-8")) - sum(span_bytes(text, span) for span in spans)


def object_members(text: str) -> dict[str, dict[str, Any]]:
    return {span["key"]: span for span in canonical_member_spans(text)}


def add_component(components: collections.Counter[str], name: str, amount: int) -> None:
    if name not in COMPONENTS:
        fail(f"unknown component {name}")
    components[name] += amount


def decision_partition(text: str, top: dict[str, Any]) -> collections.Counter[str]:
    components: collections.Counter[str] = collections.Counter()
    outer = object_members(text)
    decision_span = outer.get("decision")
    if decision_span is None:
        fail("decision frame has no decision member")
    decision_text = text[decision_span["valueStart"] : decision_span["valueEnd"]]
    nested_spans = canonical_member_spans(decision_text)
    nested = {span["key"]: span for span in nested_spans}

    for key, component in (
        ("observationBefore", "PlayerObservationV1"),
        ("completeLegalDomain", "CompleteLegalDomain"),
    ):
        span = nested.get(key)
        if span is None:
            fail(f"decision record has no {key} member")
        add_component(components, component, span_bytes(decision_text, span))

    identity_bytes = 0
    for key in ("candidateDomainDigest", "semanticDecisionId"):
        span = nested.get(key)
        if span is None:
            fail(f"decision record has no {key} member")
        identity_bytes += span_bytes(decision_text, span)
    add_component(components, "CandidateDomainDigest / decision identity", identity_bytes)

    chosen_bytes = 0
    for key in ("chosenSemanticAction", "chosenSemanticResponse"):
        span = nested.get(key)
        if span is not None:
            chosen_bytes += span_bytes(decision_text, span)
    if chosen_bytes == 0:
        fail("decision record has neither chosen semantic action nor response")
    add_component(components, "Chosen semantic input/response", chosen_bytes)

    nested_metadata = sum(
        span_bytes(decision_text, span)
        for key, span in nested.items()
        if key in DECISION_RECORD_KEYS
        and key
        not in {
            "observationBefore",
            "completeLegalDomain",
            "candidateDomainDigest",
            "semanticDecisionId",
            "chosenSemanticAction",
            "chosenSemanticResponse",
        }
    )
    nested_other = sum(
        span_bytes(decision_text, span)
        for key, span in nested.items()
        if key not in DECISION_RECORD_KEYS
    )
    add_component(components, "Other", nested_other)

    known_outer = sum(
        span_bytes(text, span)
        for key, span in outer.items()
        if key in FRAME_KEYS
    )
    unknown_outer = sum(
        span_bytes(text, span)
        for key, span in outer.items()
        if key not in FRAME_KEYS and key != "decision"
    )
    add_component(components, "Other", unknown_outer)

    # The decision wrapper, both object overheads, and all scalar record/frame fields are framing.
    add_component(
        components,
        "Decision/frame metadata",
        known_outer
        + span_bytes(text, decision_span)
        - value_bytes(text, decision_span)
        + nested_metadata
        + object_overhead_bytes(decision_text, nested_spans)
        + object_overhead_bytes(text, list(outer.values())),
    )
    return components


def start_partition(text: str) -> collections.Counter[str]:
    components: collections.Counter[str] = collections.Counter()
    outer = object_members(text)
    metadata_span = outer.get("episodeMetadata")
    if metadata_span is None:
        fail("episode-start frame has no episodeMetadata member")
    metadata_text = text[metadata_span["valueStart"] : metadata_span["valueEnd"]]
    metadata_spans = canonical_member_spans(metadata_text)
    metadata = {span["key"]: span for span in metadata_spans}

    for key, component in (
        ("compactReplayLink", "Replay linkage"),
        ("closure", "Closure metadata"),
    ):
        span = metadata.get(key)
        if span is None:
            fail(f"episode metadata has no {key} member")
        add_component(components, component, span_bytes(metadata_text, span))

    metadata_other = sum(
        span_bytes(metadata_text, span)
        for key, span in metadata.items()
        if key not in {"compactReplayLink", "closure"}
    )
    add_component(components, "Episode/provenance", metadata_other)
    add_component(
        components,
        "Episode/provenance",
        span_bytes(text, metadata_span)
        - value_bytes(text, metadata_span)
        + object_overhead_bytes(metadata_text, metadata_spans),
    )

    known_outer = sum(
        span_bytes(text, span)
        for key, span in outer.items()
        if key in FRAME_KEYS | {"episodeOrdinal"}
    )
    unknown_outer = sum(
        span_bytes(text, span)
        for key, span in outer.items()
        if key not in FRAME_KEYS and key not in {"episodeOrdinal", "episodeMetadata"}
    )
    add_component(components, "Other", unknown_outer)
    add_component(
        components,
        "Decision/frame metadata",
        known_outer + object_overhead_bytes(text, list(outer.values())),
    )
    return components


def end_partition(text: str) -> collections.Counter[str]:
    components: collections.Counter[str] = collections.Counter()
    outer = object_members(text)
    for key in ("semanticEpisodeId", "collectionJobId"):
        span = outer.get(key)
        if span is None:
            fail(f"episode-end frame has no {key} member")
        add_component(components, "Episode/provenance", span_bytes(text, span))
    for key in ("trajectoryId", "decisionCount", "episodeContentDigest", "closure"):
        span = outer.get(key)
        if span is None:
            fail(f"episode-end frame has no {key} member")
        add_component(components, "Closure metadata", span_bytes(text, span))
    unknown = sum(
        span_bytes(text, span)
        for key, span in outer.items()
        if key
        not in FRAME_KEYS
        | {"trajectoryId", "decisionCount", "episodeContentDigest", "closure"}
    )
    add_component(components, "Other", unknown)
    known_frame = sum(
        span_bytes(text, span)
        for key, span in outer.items()
        if key in FRAME_KEYS - {"semanticEpisodeId", "collectionJobId"}
    )
    add_component(
        components,
        "Decision/frame metadata",
        known_frame + object_overhead_bytes(text, list(outer.values())),
    )
    return components


def frame_partition(raw: bytes, frame: dict[str, Any]) -> collections.Counter[str]:
    if not raw.endswith(b"\n") or b"\r" in raw:
        fail("non-LF frame encountered")
    try:
        text = raw[:-1].decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        fail(f"invalid UTF-8 frame: {error}")
    if len(text.encode("utf-8")) + 1 != len(raw):
        fail("frame UTF-8 length does not match source bytes")
    record_type = frame.get("recordType")
    if record_type == "decision":
        parts = decision_partition(text, frame)
    elif record_type == "episode-start":
        parts = start_partition(text)
    elif record_type == "episode-end":
        parts = end_partition(text)
    else:
        fail(f"unknown record type {record_type!r}")
    # The physical frame contract is LF-delimited; assign the delimiter to framing rather than
    # pretending it belongs to any semantic JSON field.
    add_component(parts, "Decision/frame metadata", 1)
    if sum(parts.values()) != len(raw):
        fail(
            f"component partition does not reconcile for {record_type}: "
            f"{sum(parts.values())} != {len(raw)}"
        )
    return parts


def nearest_rank(values: list[int], percentile: float) -> int:
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(percentile * len(ordered)) - 1))
    return ordered[index]


def stats(values: list[int], *, include_p999: bool = False) -> dict[str, Any]:
    if not values:
        return {"n": 0}
    result: dict[str, Any] = {
        "n": len(values),
        "min": min(values),
        "p50": nearest_rank(values, 0.50),
        "p95": nearest_rank(values, 0.95),
        "p99": nearest_rank(values, 0.99),
        "max": max(values),
        "mean": statistics.fmean(values),
        "percentileMethod": "nearest-rank",
    }
    if include_p999:
        result["p99.9"] = nearest_rank(values, 0.999)
    if len(values) < 1_000:
        result["sampleLabel"] = "LOW_N"
    elif len(values) < 10_000:
        result["sampleLabel"] = "LIMITED_N"
    else:
        result["sampleLabel"] = "SUPPORTED"
    return result


def safe_resolve(root: Path, reference: str) -> Path:
    relative = Path(reference)
    if relative.is_absolute() or ".." in relative.parts:
        fail(f"manifest shard reference escapes dataset root: {reference}")
    path = root.joinpath(relative)
    root_real = root.resolve()
    path_real = path.resolve(strict=False)
    try:
        path_real.relative_to(root_real)
    except ValueError:
        fail(f"manifest shard reference escapes dataset root: {reference}")
    return path


def manifest_owned_files(root: Path, manifest: dict[str, Any]) -> list[tuple[str, Path]]:
    files = [("manifest.json", root / "manifest.json")]
    for shard in manifest.get("shards", []):
        reference = shard.get("contentReference")
        if not isinstance(reference, str):
            fail("manifest shard has no string contentReference")
        files.append((reference, safe_resolve(root, reference)))
    return files


def recursive_key_values(value: Any) -> Iterable[tuple[str, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield key, child
            yield from recursive_key_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from recursive_key_values(child)


def chosen_action(frame: dict[str, Any]) -> dict[str, Any] | None:
    decision = frame["decision"]
    chosen = decision.get("chosenSemanticAction")
    if not isinstance(chosen, dict):
        return None
    candidate = chosen.get("candidate")
    return candidate if isinstance(candidate, dict) else None


def chosen_response_type(frame: dict[str, Any]) -> str | None:
    response = frame["decision"].get("chosenSemanticResponse")
    if not isinstance(response, dict):
        return None
    body = response.get("response")
    return body.get("type") if isinstance(body, dict) and isinstance(body.get("type"), str) else None


def action_surface(frame: dict[str, Any]) -> str:
    candidate = chosen_action(frame)
    if candidate is not None:
        semantics = candidate.get("actionSemantics")
        if isinstance(semantics, dict) and isinstance(semantics.get("type"), str):
            return semantics["type"]
        if isinstance(candidate.get("kind"), str):
            return candidate["kind"]
    return chosen_response_type(frame) or str(frame["decision"].get("decisionKind"))


def has_payload_or_domain(frame: dict[str, Any], field: str) -> bool:
    candidate = chosen_action(frame)
    decision = frame["decision"]
    chosen = decision.get("chosenSemanticAction")
    payload = chosen.get("choicePayload") if isinstance(chosen, dict) else None
    if isinstance(payload, dict) and field in payload:
        return True
    if candidate is None:
        return False
    required = candidate.get("requiredPayloadFields")
    if isinstance(required, list) and field in required:
        return True
    if field in candidate:
        return True
    semantics = candidate.get("actionSemantics")
    if isinstance(semantics, dict) and field in semantics:
        return True
    return False


def decision_dimensions(frame: dict[str, Any]) -> set[str]:
    decision = frame["decision"]
    dimensions: set[str] = set()
    kind = str(decision.get("decisionKind"))
    surface = action_surface(frame)
    response_type = chosen_response_type(frame) or ""
    structured = decision.get("completeLegalDomain", {}).get("structuredDomain")
    structured_type = structured.get("type") if isinstance(structured, dict) else None

    if kind == "PRIORITY":
        dimensions.add("priority")
    if decision.get("chosenSemanticResponse") is not None:
        dimensions.add("structured pending decision")
    if has_payload_or_domain(frame, "costPayment") or has_payload_or_domain(
        frame, "additionalCostPayment"
    ) or "PAYMENT" in kind or "MANA" in kind or (
        isinstance(structured_type, str)
        and ("payment" in structured_type.lower() or "mana" in structured_type.lower())
    ):
        dimensions.add("payment-bearing actions")
    candidate = chosen_action(frame)
    target_domain = candidate.get("targetDomain") if candidate is not None else None
    target_requirements = target_domain.get("requirements") if isinstance(target_domain, dict) else None
    target_ids = candidate.get("targetEntityIds") if candidate is not None else None
    chosen_action_payload = (
        frame["decision"].get("chosenSemanticAction", {}).get("choicePayload")
        if isinstance(frame["decision"].get("chosenSemanticAction"), dict)
        else None
    )
    payload_has_targets = isinstance(chosen_action_payload, dict) and any(
        "target" in str(key).lower() for key in chosen_action_payload
    )
    if (
        (isinstance(target_requirements, list) and bool(target_requirements))
        or (isinstance(target_ids, list) and bool(target_ids))
        or structured_type == "targets"
        or payload_has_targets
        or "Target" in surface
        or "TARGET" in kind
    ):
        dimensions.add("target-bearing actions")
    if has_payload_or_domain(frame, "repeatCount") or (
        candidate is not None and "repeatCountDomain" in candidate
    ):
        dimensions.add("repeatCount-bearing actions")
    if "CARD" in kind or "Cards" in response_type or structured_type == "cards":
        dimensions.add("card selections")
    if "COLOR" in kind or "Color" in response_type or structured_type == "color":
        dimensions.add("color choices")
    if kind == "YES_NO" or response_type == "YesNoResponse" or structured_type == "yesNo":
        dimensions.add("yes/no")
    return dimensions


class CountingWriter:
    """Count the actual uncompressed bytes emitted by tarfile's streaming writer."""

    def __init__(self, target: Any) -> None:
        self.target = target
        self.count = 0

    def write(self, data: bytes) -> int:
        self.count += len(data)
        return self.target.write(data)

    def tell(self) -> int:
        return self.count

    def flush(self) -> None:
        self.target.flush()


def deterministic_tar_gzip(files: list[tuple[str, Path]], level: int) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix="argentum-post-b2-gzip-") as temp_dir:
        output = Path(temp_dir) / f"dataset.tar.gz.{level}"
        compress_started = time.perf_counter()
        with output.open("wb") as raw_output:
            with gzip.GzipFile(
                fileobj=raw_output,
                filename="",
                mode="wb",
                compresslevel=level,
                mtime=0,
            ) as compressed:
                tar_output = CountingWriter(compressed)
                with tarfile.open(
                    fileobj=tar_output,
                    mode="w|",
                    format=tarfile.USTAR_FORMAT,
                ) as archive:
                    for name, path in files:
                        info = tarfile.TarInfo(name.replace("\\", "/"))
                        info.size = path.stat().st_size
                        info.mode = 0o644
                        info.mtime = 0
                        info.uid = 0
                        info.gid = 0
                        info.uname = ""
                        info.gname = ""
                        with path.open("rb") as source:
                            archive.addfile(info, source)
                tar_bytes = tar_output.count
        compress_seconds = time.perf_counter() - compress_started
        compressed_bytes = output.stat().st_size

        decompress_started = time.perf_counter()
        decompressed_bytes = 0
        with gzip.open(output, "rb") as compressed:
            while True:
                chunk = compressed.read(4 * 1024 * 1024)
                if not chunk:
                    break
                decompressed_bytes += len(chunk)
        decompress_seconds = time.perf_counter() - decompress_started
        if decompressed_bytes != tar_bytes:
            fail(f"gzip archive decompressed to {decompressed_bytes}, expected {tar_bytes}")

    return {
        "codec": "gzip",
        "level": level,
        "archiveFormat": "deterministic USTAR tar stream",
        "archiveInputBytes": tar_bytes,
        "compressedArchiveBytes": compressed_bytes,
        "archiveCompressionRatio": tar_bytes / compressed_bytes,
        "canonicalToCompressedRatio": sum(path.stat().st_size for _, path in files)
        / compressed_bytes,
        "compressSeconds": compress_seconds,
        "decompressSeconds": decompress_seconds,
        "compressMiBPerSecond": tar_bytes / (1024 * 1024) / compress_seconds,
        "decompressMiBPerSecond": tar_bytes / (1024 * 1024) / decompress_seconds,
    }


def characterize(
    root: Path,
    expected_dataset_id: str,
    expected_manifest_digest: str,
    gzip_levels: list[int],
    skip_compression: bool,
) -> dict[str, Any]:
    manifest_path = root / "manifest.json"
    if not root.is_dir() or root.is_symlink():
        fail(f"dataset root is not a real directory: {root}")
    if not manifest_path.is_file() or manifest_path.is_symlink():
        fail(f"manifest is not a regular file: {manifest_path}")
    manifest_bytes = manifest_path.read_bytes()
    try:
        manifest = json.loads(manifest_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"manifest is not readable JSON: {error}")
    if manifest.get("datasetId") != expected_dataset_id:
        fail(f"dataset ID mismatch: {manifest.get('datasetId')}")
    if manifest.get("manifestContentDigest") != expected_manifest_digest:
        fail(f"manifest content digest mismatch: {manifest.get('manifestContentDigest')}")

    files = manifest_owned_files(root, manifest)
    episode_bytes: list[int] = []
    decision_bytes: list[int] = []
    decision_record_bytes: list[int] = []
    component_totals: collections.Counter[str] = collections.Counter()
    family_bytes: collections.defaultdict[str, list[int]] = collections.defaultdict(list)
    surface_bytes: collections.defaultdict[str, list[int]] = collections.defaultdict(list)
    dimension_bytes: collections.defaultdict[str, list[int]] = collections.defaultdict(list)
    frame_counts: collections.Counter[str] = collections.Counter()
    decision_kind_counts: collections.Counter[str] = collections.Counter()
    episodes: list[dict[str, Any]] = []
    shard_measurements: list[dict[str, Any]] = []
    total_shard_bytes = 0
    total_decisions = 0

    for shard_meta in manifest.get("shards", []):
        reference = shard_meta["contentReference"]
        shard_path = safe_resolve(root, reference)
        if not shard_path.is_file() or shard_path.is_symlink():
            fail(f"manifest shard is not a regular file: {shard_path}")
        digest = hashlib.sha256()
        shard_bytes = 0
        current: dict[str, Any] | None = None
        shard_episode_count = 0
        with shard_path.open("rb") as shard_file:
            for line_number, raw in enumerate(shard_file, start=1):
                frame = json.loads(raw)
                record_type = frame.get("recordType")
                frame_counts[record_type] += 1
                digest.update(raw)
                shard_bytes += len(raw)
                parts = frame_partition(raw, frame)
                component_totals.update(parts)
                if record_type == "episode-start":
                    if current is not None:
                        fail(f"nested episode-start at {reference}:{line_number}")
                    current = {
                        "bytes": len(raw),
                        "decisions": [],
                        "ordinal": frame.get("episodeOrdinal"),
                    }
                elif record_type == "decision":
                    if current is None:
                        fail(f"decision outside episode at {reference}:{line_number}")
                    line_bytes = len(raw)
                    current["bytes"] += line_bytes
                    current["decisions"].append(line_bytes)
                    decision_bytes.append(line_bytes)
                    decision_span = object_members(raw[:-1].decode("utf-8"))["decision"]
                    decision_text = raw[:-1].decode("utf-8")
                    decision_record_bytes.append(value_bytes(decision_text, decision_span))
                    total_decisions += 1

                    decision = frame["decision"]
                    family = str(decision.get("decisionKind"))
                    surface = action_surface(frame)
                    family_bytes[family].append(line_bytes)
                    surface_bytes[surface].append(line_bytes)
                    decision_kind_counts[family] += 1
                    for dimension in decision_dimensions(frame):
                        dimension_bytes[dimension].append(line_bytes)
                elif record_type == "episode-end":
                    if current is None:
                        fail(f"episode-end without start at {reference}:{line_number}")
                    current["bytes"] += len(raw)
                    current["closureKind"] = frame.get("closure", {}).get("kind")
                    current["decisionCount"] = frame.get("decisionCount")
                    if current["decisionCount"] != len(current["decisions"]):
                        fail(f"decision count mismatch at {reference}:{line_number}")
                    episode_bytes.append(current["bytes"])
                    episodes.append(current)
                    shard_episode_count += 1
                    current = None
                else:
                    fail(f"unknown recordType {record_type!r} at {reference}:{line_number}")
        if current is not None:
            fail(f"unterminated episode in {reference}")
        actual_digest = digest.hexdigest()
        if shard_bytes != shard_meta["byteCount"]:
            fail(f"shard byte count mismatch for {reference}: {shard_bytes}")
        if actual_digest != shard_meta["contentDigest"]:
            fail(f"shard digest mismatch for {reference}: {actual_digest}")
        if shard_episode_count != shard_meta["episodeCount"]:
            fail(f"shard episode count mismatch for {reference}: {shard_episode_count}")
        shard_measurements.append(
            {
                "contentReference": reference,
                "bytes": shard_bytes,
                "episodes": shard_episode_count,
                "decisions": sum(len(episode["decisions"]) for episode in episodes[-shard_episode_count:])
                if shard_episode_count
                else 0,
                "sha256": actual_digest,
            }
        )
        total_shard_bytes += shard_bytes

    manifest_counts = manifest.get("counts", {})
    if len(episodes) != manifest_counts.get("episodeCount"):
        fail(f"manifest episode count mismatch: {len(episodes)}")
    if total_decisions != manifest_counts.get("decisionCount"):
        fail(f"manifest decision count mismatch: {total_decisions}")
    if sum(component_totals.values()) != total_shard_bytes:
        fail("component totals are not internally reconciled to shard frame bytes")

    compression: list[dict[str, Any]] = []
    if shutil.which("zstd") is None:
        compression.append(
            {
                "codec": "zstd",
                "status": "NOT_RUN",
                "reason": "zstd executable and Python zstandard module are unavailable; no dependency was added",
            }
        )
    else:
        compression.append(
            {
                "codec": "zstd",
                "status": "NOT_RUN",
                "reason": "scanner does not invoke an unqualified external codec in this run",
            }
        )
    if not skip_compression:
        for level in gzip_levels:
            compression.append(deterministic_tar_gzip(files, level))

    canonical_ndjson_bytes = total_shard_bytes
    canonical_dataset_bytes = len(manifest_bytes) + canonical_ndjson_bytes
    component_result = {
        name: {
            "bytes": component_totals.get(name, 0),
            "share": component_totals.get(name, 0) / canonical_ndjson_bytes,
        }
        for name in COMPONENTS
    }
    return {
        "provenance": {
            "datasetRoot": str(root.resolve()),
            "datasetId": manifest["datasetId"],
            "manifestContentDigest": manifest["manifestContentDigest"],
            "manifestBytes": len(manifest_bytes),
            "canonicalNdjsonBytes": canonical_ndjson_bytes,
            "canonicalDatasetBytes": canonical_dataset_bytes,
            "shardCount": len(shard_measurements),
            "episodeCount": len(episodes),
            "decisionCount": total_decisions,
        },
        "manifestCounts": manifest_counts,
        "shards": shard_measurements,
        "frames": dict(frame_counts),
        "episodes": stats(episode_bytes),
        "decisions": {
            "frameLineBytes": stats(decision_bytes, include_p999=True),
            "decisionRecordValueBytes": stats(decision_record_bytes, include_p999=True),
        },
        "componentBreakdown": component_result,
        "decisionKindStorage": {
            key: {"n": len(values), "bytes": stats(values)}
            for key, values in sorted(family_bytes.items())
        },
        "actionSurfaceStorage": {
            key: {"n": len(values), "bytes": stats(values)}
            for key, values in sorted(surface_bytes.items())
        },
        "decisionDimensionStorage": {
            key: {"n": len(values), "bytes": stats(values)}
            for key, values in sorted(dimension_bytes.items())
        },
        "decisionKindCounts": dict(sorted(decision_kind_counts.items())),
        "compression": compression,
        "analyticalMethod": {
            "strictReaderRequiredBeforeScan": True,
            "componentDecomposition": "deterministic field-span accounting over canonical frame bytes; JSON punctuation and wrappers assigned to Decision/frame metadata except episode-metadata wrappers assigned to Episode/provenance",
            "decisionByteUnit": "full canonical decision frame line including LF",
            "percentileMethod": "nearest-rank",
            "canonicalDatasetBytesIncludes": ["manifest.json", "manifest-owned shard files"],
        },
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--expected-dataset-id", default=EXPECTED_DATASET_ID)
    parser.add_argument("--expected-manifest-digest", default=EXPECTED_MANIFEST_DIGEST)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--gzip-levels", default="1,6")
    parser.add_argument("--skip-compression", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        gzip_levels = [int(value) for value in args.gzip_levels.split(",") if value]
        if any(level < 1 or level > 9 for level in gzip_levels):
            fail("gzip levels must be between 1 and 9")
        result = characterize(
            root=args.dataset,
            expected_dataset_id=args.expected_dataset_id,
            expected_manifest_digest=args.expected_manifest_digest,
            gzip_levels=gzip_levels,
            skip_compression=args.skip_compression,
        )
    except (CharacterizationError, OSError, KeyError, json.JSONDecodeError) as error:
        print(f"POST_B2_CHARACTERIZATION_FAIL={error}")
        return 1
    rendered = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
