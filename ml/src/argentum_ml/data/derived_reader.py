"""Strict reader for Kotlin-produced C1 derived learner artifacts."""

from __future__ import annotations

import hashlib
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, BinaryIO, Iterator

from ..contracts.canonical_json import (
    _RawJsonNumber,
    canonical_bytes,
    canonical_json,
    sha256_hex,
)
from ..contracts.identities import (
    ARTIFACT_IDENTITY_SCHEMA,
    DERIVED_VIEW_SCHEMA_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)
from ..contracts.model_facing import validate_model_input
from .split import assign_partition

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GIT_SHA1 = re.compile(r"^[0-9a-f]{40}$")
_UTF8_BOM = b"\xef\xbb\xbf"
_MANIFEST_KEYS = {
    "version",
    "derivedViewSchemaIdentity",
    "derivedArtifactId",
    "sourceDatasetId",
    "sourceManifestContentDigest",
    "trajectorySchemaIdentity",
    "modelFacingContractIdentity",
    "splitContractIdentity",
    "materializerImplementationIdentity",
    "materializerConfigDigest",
    "samplesContentReference",
    "samplesContentDigest",
    "samplesByteCount",
    "sampleCount",
    "episodeCount",
    "episodeCountsByPartition",
    "sampleCountsByPartition",
    "manifestContentDigest",
}
_SAMPLE_KEYS = {
    "version",
    "partition",
    "sourceReference",
    "input",
    "target",
    "binding",
    "provenance",
}
_SOURCE_REFERENCE_KEYS = {
    "datasetId",
    "sourceManifestContentDigest",
    "trajectoryId",
    "semanticEpisodeId",
    "collectionJobId",
    "decisionIndex",
    "replayActionIndex",
    "replayFrameIndex",
    "semanticDecisionId",
    "perspectivePlayerId",
}
_TARGET_KEYS = {"chosenSemanticAction", "chosenSemanticResponse"}
_BINDING_KEYS = {
    "completeLegalDomain",
    "selectedExactSourceBinding",
    "sourceBindingOrdinals",
    "semanticTieDiscriminators",
    "entityAliasBindings",
}
_DOMAIN_KEYS = {
    "version",
    "schemaIdentity",
    "kind",
    "decisionKind",
    "shape",
    "candidates",
    "structuredDomain",
}
_PARTITIONS = {"TRAIN", "VALIDATION", "TEST"}
_STRUCTURED_DOMAIN_TYPES = {
    "targets",
    "card-selection",
    "mode-selection",
    "distribution",
    "ordering",
    "split-piles",
    "search-library",
    "reorder-library",
    "combat-resolution",
    "mana-sources",
    "replacement",
    "budget-modal",
}
_SEMANTIC_DECISION_ID_KEYS = {"version", "schemaIdentity", "value"}
_TIE_FORBIDDEN_KEYS = {
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
}
_KOTLIN_INT_MAX = 2**31 - 1
_KOTLIN_LONG_MAX = 2**63 - 1


class DerivedArtifactError(ValueError):
    """Raised for any malformed, non-canonical, or contract-incompatible artifact."""


def _duplicate_rejecting_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DerivedArtifactError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _parse_json(raw: bytes, label: str) -> Any:
    if raw.startswith(_UTF8_BOM):
        raise DerivedArtifactError(f"{label} contains a UTF-8 BOM")
    try:
        text = raw.decode("utf-8")
        return json.loads(
            text,
            object_pairs_hook=_duplicate_rejecting_pairs,
            parse_float=_RawJsonNumber,
            parse_constant=lambda value: (_ for _ in ()).throw(
                DerivedArtifactError(f"non-finite JSON constant in {label}: {value}")
            ),
        )
    except DerivedArtifactError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise DerivedArtifactError(f"malformed {label}") from exc


def _expect_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise DerivedArtifactError(f"{label} must be an object")
    return value


def _expect_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        missing = sorted(expected - set(value))
        extra = sorted(set(value) - expected)
        raise DerivedArtifactError(f"{label} keys mismatch; missing={missing}, extra={extra}")


def _expect_int(
    value: Any,
    label: str,
    *,
    nonnegative: bool = False,
    max_value: int | None = None,
) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise DerivedArtifactError(f"{label} must be an integer")
    if nonnegative and value < 0:
        raise DerivedArtifactError(f"{label} must not be negative")
    if max_value is not None and value > max_value:
        raise DerivedArtifactError(f"{label} is outside the Kotlin integer range")
    return value


def _expect_sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        raise DerivedArtifactError(f"{label} must be lowercase SHA-256 hex")
    return value


def _canonical_element(value: Any, label: str) -> str:
    try:
        return canonical_json(value)
    except (TypeError, ValueError, UnicodeError) as exc:
        raise DerivedArtifactError(f"{label} is not canonical JSON") from exc


def _canonical_bytes(value: Any, label: str) -> bytes:
    try:
        return canonical_bytes(value)
    except (TypeError, ValueError, UnicodeError) as exc:
        raise DerivedArtifactError(f"{label} is not canonical JSON") from exc


def _is_raw_tie_key(key: str) -> bool:
    return key in _TIE_FORBIDDEN_KEYS or key.endswith("Id") or key.endswith("Ids")


def _reject_tie_fields(value: Any, label: str) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if _is_raw_tie_key(key):
                raise DerivedArtifactError(f"{label} contains forbidden tie field: {key}")
            _reject_tie_fields(child, label)
    elif isinstance(value, list):
        for child in value:
            _reject_tie_fields(child, label)


def _partition_counts(value: Any, label: str) -> dict[str, int]:
    obj = _expect_object(value, label)
    _expect_keys(obj, _PARTITIONS, label)
    result = {
        key: _expect_int(
            obj[key],
            f"{label}.{key}",
            nonnegative=True,
            max_value=_KOTLIN_INT_MAX,
        )
        for key in _PARTITIONS
    }
    return result


def _total(counts: dict[str, int]) -> int:
    return sum(counts.values())


def _artifact_identity_payload(manifest: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema": ARTIFACT_IDENTITY_SCHEMA,
        "derivedViewSchemaIdentity": manifest["derivedViewSchemaIdentity"],
        "sourceDatasetId": manifest["sourceDatasetId"],
        "sourceManifestContentDigest": manifest["sourceManifestContentDigest"],
        "trajectorySchemaIdentity": manifest["trajectorySchemaIdentity"],
        "modelFacingContractIdentity": manifest["modelFacingContractIdentity"],
        "splitContractIdentity": manifest["splitContractIdentity"],
        "materializerImplementationIdentity": manifest["materializerImplementationIdentity"],
        "materializerConfigDigest": manifest["materializerConfigDigest"],
        "samplesContentDigest": manifest["samplesContentDigest"],
        "episodeCountsByPartition": manifest["episodeCountsByPartition"],
        "sampleCountsByPartition": manifest["sampleCountsByPartition"],
    }


@dataclass
class DerivedArtifactReader:
    root: Path
    manifest: dict[str, Any]
    _samples_stream: BinaryIO = field(repr=False)
    _closed: bool = field(default=False, init=False, repr=False)

    @classmethod
    def open(cls, root: Path) -> "DerivedArtifactReader":
        root = Path(root)
        _require_directory(root, "artifact root")
        manifest_path = root / "manifest.json"
        samples_path = root / "samples.ndjson"
        _require_regular_file(manifest_path, "manifest.json")
        manifest_raw = manifest_path.read_bytes()
        if b"\r" in manifest_raw or b"\n" in manifest_raw:
            raise DerivedArtifactError("manifest.json must not contain a newline")
        manifest = _expect_object(_parse_json(manifest_raw, "manifest.json"), "manifest.json")
        if _canonical_bytes(manifest, "manifest.json") != manifest_raw:
            raise DerivedArtifactError("manifest.json is not canonical UTF-8")
        _validate_manifest(manifest)
        _require_regular_file(samples_path, "samples.ndjson")
        samples_stream = samples_path.open("rb")
        try:
            _validate_sample_file(samples_stream, manifest)
            samples_stream.seek(0)
            return cls(root=root, manifest=manifest, _samples_stream=samples_stream)
        except Exception:
            samples_stream.close()
            raise

    def iter_samples(self) -> Iterator[dict[str, Any]]:
        if self._closed:
            raise DerivedArtifactError("derived artifact reader is closed")
        try:
            if os.fstat(self._samples_stream.fileno()).st_size != self.manifest["samplesByteCount"]:
                raise DerivedArtifactError("samples.ndjson changed after artifact validation")
            _validate_sample_file(self._samples_stream, self.manifest)
            self._samples_stream.seek(0)
            for raw_line in self._samples_stream:
                sample = _parse_sample_line(raw_line)
                _validate_sample(sample, self.manifest)
                yield sample
        finally:
            self.close()

    def stream_samples(self) -> Iterator[dict[str, Any]]:
        return self.iter_samples()

    def __iter__(self) -> Iterator[dict[str, Any]]:
        return self.iter_samples()

    def close(self) -> None:
        if not self._closed:
            self._samples_stream.close()
            self._closed = True

    def __enter__(self) -> "DerivedArtifactReader":
        return self

    def __exit__(self, exc_type: Any, exc: Any, traceback: Any) -> None:
        self.close()

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            pass


def _require_directory(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_dir():
        raise DerivedArtifactError(f"{label} must be a real directory")


def _require_regular_file(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise DerivedArtifactError(f"{label} must be a non-symlink regular file")


def _validate_manifest(manifest: dict[str, Any]) -> None:
    _expect_keys(manifest, _MANIFEST_KEYS, "manifest")
    if manifest["version"] != 1:
        raise DerivedArtifactError("unsupported derived manifest version")
    if manifest["derivedViewSchemaIdentity"] != DERIVED_VIEW_SCHEMA_IDENTITY:
        raise DerivedArtifactError("unsupported derived view identity")
    if manifest["trajectorySchemaIdentity"] != "argentum-trajectory@v1":
        raise DerivedArtifactError("unsupported trajectory schema identity")
    if manifest["modelFacingContractIdentity"] != MODEL_FACING_CONTRACT_IDENTITY:
        raise DerivedArtifactError("unsupported model-facing contract identity")
    if manifest["splitContractIdentity"] != SPLIT_CONTRACT_IDENTITY:
        raise DerivedArtifactError("unsupported split contract identity")
    if manifest["samplesContentReference"] != "samples.ndjson":
        raise DerivedArtifactError("unsupported samples content reference")
    for key in (
        "derivedArtifactId",
        "sourceDatasetId",
        "sourceManifestContentDigest",
        "materializerConfigDigest",
        "samplesContentDigest",
        "manifestContentDigest",
    ):
        _expect_sha(manifest[key], key)
    implementation = _expect_object(manifest["materializerImplementationIdentity"], "materializer identity")
    _expect_keys(implementation, {"implementation", "sourceCommit"}, "materializer identity")
    if not isinstance(implementation["implementation"], str) or not implementation["implementation"]:
        raise DerivedArtifactError("materializer implementation must be non-empty")
    if not isinstance(implementation["sourceCommit"], str) or _GIT_SHA1.fullmatch(implementation["sourceCommit"]) is None:
        raise DerivedArtifactError("materializer sourceCommit must be lowercase Git SHA-1")
    _expect_int(
        manifest["samplesByteCount"],
        "samplesByteCount",
        nonnegative=True,
        max_value=_KOTLIN_LONG_MAX,
    )
    for key in ("sampleCount", "episodeCount"):
        _expect_int(manifest[key], key, nonnegative=True, max_value=_KOTLIN_INT_MAX)
    episode_counts = _partition_counts(manifest["episodeCountsByPartition"], "episodeCountsByPartition")
    sample_counts = _partition_counts(manifest["sampleCountsByPartition"], "sampleCountsByPartition")
    if _total(episode_counts) != manifest["episodeCount"]:
        raise DerivedArtifactError("episode partition totals mismatch")
    if _total(sample_counts) != manifest["sampleCount"]:
        raise DerivedArtifactError("sample partition totals mismatch")
    content = dict(manifest)
    content.pop("manifestContentDigest")
    if sha256_hex(_canonical_bytes(content, "manifest content")) != manifest["manifestContentDigest"]:
        raise DerivedArtifactError("manifestContentDigest mismatch")
    if sha256_hex(
        _canonical_bytes(_artifact_identity_payload(manifest), "artifact identity payload")
    ) != manifest["derivedArtifactId"]:
        raise DerivedArtifactError("derivedArtifactId mismatch")


def _parse_sample_line(raw_line: bytes) -> dict[str, Any]:
    if not raw_line.endswith(b"\n"):
        raise DerivedArtifactError("sample line is missing final LF")
    content = raw_line[:-1]
    if not content or content.strip() == b"":
        raise DerivedArtifactError("blank sample line")
    if b"\r" in raw_line or content.startswith(_UTF8_BOM):
        raise DerivedArtifactError("sample line contains CR or BOM")
    sample = _expect_object(_parse_json(content, "sample line"), "sample line")
    if _canonical_bytes(sample, "sample line") != content:
        raise DerivedArtifactError("sample line is not canonical JSON")
    return sample


def _validate_sample_file(stream: BinaryIO, manifest: dict[str, Any]) -> None:
    digest = hashlib.sha256()
    byte_count = 0
    sample_count = 0
    partition_counts = {key: 0 for key in _PARTITIONS}
    seen_references: set[tuple[str, int]] = set()
    stream.seek(0)
    for raw_line in stream:
        sample = _parse_sample_line(raw_line)
        _validate_sample(sample, manifest)
        source = sample["sourceReference"]
        reference = (source["trajectoryId"], source["decisionIndex"])
        if reference in seen_references:
            raise DerivedArtifactError("duplicate sample source reference")
        seen_references.add(reference)
        digest.update(raw_line)
        byte_count += len(raw_line)
        sample_count += 1
        partition_counts[sample["partition"]] += 1
    if byte_count != manifest["samplesByteCount"]:
        raise DerivedArtifactError("samplesByteCount mismatch")
    if sample_count != manifest["sampleCount"]:
        raise DerivedArtifactError("sampleCount mismatch")
    if digest.hexdigest() != manifest["samplesContentDigest"]:
        raise DerivedArtifactError("samplesContentDigest mismatch")
    if partition_counts != manifest["sampleCountsByPartition"]:
        raise DerivedArtifactError("sample partition counts mismatch")


def _validate_sample(sample: dict[str, Any], manifest: dict[str, Any]) -> None:
    _expect_keys(sample, _SAMPLE_KEYS, "sample")
    if sample["version"] != 1 or sample["partition"] not in _PARTITIONS:
        raise DerivedArtifactError("unsupported sample version or partition")
    source = _expect_object(sample["sourceReference"], "sourceReference")
    _expect_keys(source, _SOURCE_REFERENCE_KEYS, "sourceReference")
    for key in ("datasetId", "sourceManifestContentDigest", "trajectoryId", "semanticEpisodeId", "collectionJobId"):
        _expect_sha(source[key], f"sourceReference.{key}")
    if source["datasetId"] != manifest["sourceDatasetId"]:
        raise DerivedArtifactError("sample source dataset mismatch")
    if source["sourceManifestContentDigest"] != manifest["sourceManifestContentDigest"]:
        raise DerivedArtifactError("sample source manifest digest mismatch")
    for key in ("decisionIndex", "replayActionIndex", "replayFrameIndex"):
        _expect_int(
            source[key],
            f"sourceReference.{key}",
            nonnegative=True,
            max_value=_KOTLIN_INT_MAX,
        )
    semantic_decision_id = _expect_object(
        source["semanticDecisionId"],
        "sourceReference.semanticDecisionId",
    )
    _expect_keys(semantic_decision_id, _SEMANTIC_DECISION_ID_KEYS, "semanticDecisionId")
    if (
        semantic_decision_id["version"] != 1
        or semantic_decision_id["schemaIdentity"]
        != "argentum-trajectory-semantic-decision@v1"
    ):
        raise DerivedArtifactError("unsupported semantic decision identity")
    _expect_sha(semantic_decision_id["value"], "semanticDecisionId.value")
    if not isinstance(source["perspectivePlayerId"], str) or not source["perspectivePlayerId"]:
        raise DerivedArtifactError("sourceReference.perspectivePlayerId must be non-empty")
    target = _expect_object(sample["target"], "target")
    _expect_keys(target, _TARGET_KEYS, "target")
    if (target["chosenSemanticAction"] is None) == (target["chosenSemanticResponse"] is None):
        raise DerivedArtifactError("target must contain exactly one semantic value")
    binding = _expect_object(sample["binding"], "binding")
    _expect_keys(binding, _BINDING_KEYS, "binding")
    domain = _validate_domain(binding["completeLegalDomain"])
    aliases = _validate_alias_bindings(binding["entityAliasBindings"])
    _validate_input(sample["input"], aliases, domain)
    _validate_ordinals(binding["sourceBindingOrdinals"], domain)
    _validate_tie_discriminators(
        binding["semanticTieDiscriminators"],
        binding["sourceBindingOrdinals"],
        set(aliases.values()),
    )
    _validate_target_membership(target, binding["selectedExactSourceBinding"], domain)
    expected_partition = assign_partition(source["semanticEpisodeId"])
    if sample["partition"] != expected_partition:
        raise DerivedArtifactError("sample partition does not match semanticEpisodeId")


def _validate_input(value: Any, aliases: dict[str, str], source_domain: dict[str, Any]) -> None:
    try:
        validate_model_input(value, aliases=aliases, source_domain=source_domain)
    except ValueError as exc:
        raise DerivedArtifactError(str(exc)) from exc
    _reject_raw_literals(value, set(aliases.values()), "model input")


def _validate_domain(value: Any) -> dict[str, Any]:
    domain = _expect_object(value, "completeLegalDomain")
    _expect_keys(domain, _DOMAIN_KEYS, "completeLegalDomain")
    if domain["version"] != 2 or domain["schemaIdentity"] != "argentum-gym-action-domain@v2":
        raise DerivedArtifactError("unsupported complete legal domain")
    if domain["kind"] not in {"ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS", "STRUCTURED_DECISION"}:
        raise DerivedArtifactError("unsupported complete legal domain kind")
    if not isinstance(domain["candidates"], list):
        raise DerivedArtifactError("domain candidates must be a list")
    if any(not isinstance(candidate, dict) for candidate in domain["candidates"]):
        raise DerivedArtifactError("domain candidates must be objects")
    if domain["structuredDomain"] is not None and not isinstance(domain["structuredDomain"], dict):
        raise DerivedArtifactError("structuredDomain must be an object or null")
    if domain["kind"] == "STRUCTURED_DECISION":
        if domain["structuredDomain"] is None:
            raise DerivedArtifactError("structured domain is missing")
        if domain["candidates"] or domain["decisionKind"] is None or domain["shape"] is None:
            raise DerivedArtifactError("structured domain has an invalid flat-domain carrier")
        structured_type = domain["structuredDomain"].get("type")
        if structured_type not in _STRUCTURED_DOMAIN_TYPES:
            raise DerivedArtifactError("unsupported structured domain type")
        expected_version = {
            "targets": 2,
            "card-selection": 1,
            "mode-selection": 1,
            "distribution": 1,
            "ordering": 1,
            "split-piles": 1,
            "search-library": 1,
            "reorder-library": 1,
            "combat-resolution": 1,
            "mana-sources": 3,
            "replacement": 1,
            "budget-modal": 1,
        }[structured_type]
        if domain["structuredDomain"].get("version") != expected_version:
            raise DerivedArtifactError("unsupported structured domain version")
    elif domain["structuredDomain"] is not None:
        raise DerivedArtifactError("flat domain cannot carry a structured domain")
    elif domain["kind"] == "ACTION_CANDIDATES":
        if domain["decisionKind"] is not None or domain["shape"] is not None:
            raise DerivedArtifactError("action domain carries pending-decision fields")
    elif domain["decisionKind"] is None or domain["shape"] is None:
        raise DerivedArtifactError("folded domain is missing pending-decision fields")
    return domain


def _validate_alias_bindings(value: Any) -> dict[str, str]:
    if not isinstance(value, list):
        raise DerivedArtifactError("entityAliasBindings must be a list")
    aliases: dict[str, str] = {}
    for index, binding in enumerate(value):
        obj = _expect_object(binding, "entityAliasBinding")
        _expect_keys(obj, {"alias", "sourceEntityId"}, "entityAliasBinding")
        if (
            obj["alias"] != f"entity-{index}"
            or not isinstance(obj["sourceEntityId"], str)
            or not obj["sourceEntityId"].strip()
        ):
            raise DerivedArtifactError("entity aliases are not producer-canonical")
        if obj["sourceEntityId"] in aliases.values():
            raise DerivedArtifactError("entity aliases are not injective")
        aliases[obj["alias"]] = obj["sourceEntityId"]
    return aliases


def _validate_ordinals(value: Any, domain: dict[str, Any]) -> None:
    if not isinstance(value, list) or any(not isinstance(item, int) or isinstance(item, bool) for item in value):
        raise DerivedArtifactError("sourceBindingOrdinals must be integer list")
    if domain["kind"] == "STRUCTURED_DECISION":
        if value:
            raise DerivedArtifactError("structured domain cannot carry flat source ordinals")
    elif value != list(range(len(domain["candidates"]))):
        raise DerivedArtifactError("sourceBindingOrdinals must address every candidate")


def _validate_tie_discriminators(
    value: Any,
    ordinals: list[int],
    raw_entity_ids: set[str],
) -> None:
    obj = _expect_object(value, "semanticTieDiscriminators")
    canonical_values: set[str] = set()
    for key, raw in obj.items():
        if (
            not isinstance(key, str)
            or not key.isdigit()
            or key != str(int(key))
            or int(key) not in ordinals
        ):
            raise DerivedArtifactError("tie discriminator key is not a source ordinal")
        if not isinstance(raw, str):
            raise DerivedArtifactError("tie discriminator value must be canonical JSON text")
        parsed = _expect_object(_parse_json(raw.encode("utf-8"), "tie discriminator"), "tie discriminator")
        if _canonical_element(parsed, "tie discriminator") != raw:
            raise DerivedArtifactError("tie discriminator is not canonical")
        _reject_tie_fields(parsed, "tie discriminator")
        _reject_raw_literals(parsed, raw_entity_ids, "tie discriminator")
        if raw in canonical_values:
            raise DerivedArtifactError("tie discriminators are not unique")
        canonical_values.add(raw)


def _reject_raw_literals(value: Any, raw_values: set[str], label: str) -> None:
    if isinstance(value, str) and value in raw_values:
        raise DerivedArtifactError(f"{label} contains a raw source entity identity")
    if isinstance(value, dict):
        for child in value.values():
            _reject_raw_literals(child, raw_values, label)
    elif isinstance(value, list):
        for child in value:
            _reject_raw_literals(child, raw_values, label)


def _validate_target_membership(
    target: dict[str, Any],
    selected_binding: Any,
    domain: dict[str, Any],
) -> None:
    selected = _expect_object(selected_binding, "selectedExactSourceBinding")
    if target["chosenSemanticAction"] is not None:
        chosen = _expect_object(target["chosenSemanticAction"], "chosenSemanticAction")
        exact = selected.get("exactAction", selected)
        if _canonical_element(chosen, "chosen action") != _canonical_element(exact, "selected action"):
            raise DerivedArtifactError("chosen action does not match selected source binding")
        if chosen.get("type") != "chosen-action" or domain["kind"] != "ACTION_CANDIDATES":
            raise DerivedArtifactError("chosen action has incompatible domain")
        candidate = _expect_object(chosen.get("candidate"), "chosen action candidate")
        matches = [
            item
            for item in domain["candidates"]
            if _canonical_element(item, "domain candidate")
            == _canonical_element(candidate, "chosen action candidate")
        ]
        if len(matches) != 1:
            raise DerivedArtifactError("chosen action is not uniquely in the complete domain")
        if matches[0].get("affordable") is not True:
            raise DerivedArtifactError("chosen action is not executable")
        _validate_action_choice_payload(matches[0], chosen)
    else:
        chosen = _expect_object(target["chosenSemanticResponse"], "chosenSemanticResponse")
        exact = selected.get("exactResponse", selected)
        if _canonical_element(chosen, "chosen response") != _canonical_element(exact, "selected response"):
            raise DerivedArtifactError("chosen response does not match selected source binding")
        if chosen.get("type") != "chosen-response":
            raise DerivedArtifactError("chosen response has malformed type")
        response = _expect_object(chosen.get("response"), "chosen response body")
        if domain["kind"] == "STRUCTURED_DECISION" and domain["structuredDomain"] is None:
            raise DerivedArtifactError("structured response has no structured domain")
        if domain["kind"] == "STRUCTURED_DECISION":
            _validate_structured_response_membership(
                response,
                _expect_object(domain["structuredDomain"], "structuredDomain"),
            )
        if domain["kind"] == "FOLDED_DECISION_OPTIONS":
            matches = []
            for candidate in domain["candidates"]:
                candidate_obj = _expect_object(candidate, "folded candidate")
                semantic = candidate_obj.get("actionSemantics")
                if isinstance(semantic, dict):
                    if _canonical_element(semantic, "folded candidate semantics") == _canonical_element(
                        response,
                        "chosen folded response",
                    ):
                        matches.append(candidate_obj)
                    elif "optionMetadata" in semantic:
                        without_metadata = dict(semantic)
                        without_metadata.pop("optionMetadata", None)
                        if _canonical_element(
                            without_metadata,
                            "folded candidate semantics",
                        ) == _canonical_element(response, "chosen folded response"):
                            matches.append(candidate_obj)
            if len(matches) != 1:
                raise DerivedArtifactError("chosen folded response is not uniquely in the domain")


def _validate_action_choice_payload(candidate: dict[str, Any], chosen: dict[str, Any]) -> None:
    payload = _expect_object(chosen.get("choicePayload"), "chosen action choicePayload")
    required_value = candidate.get("requiredPayloadFields")
    if not isinstance(required_value, list) or any(not isinstance(field, str) for field in required_value):
        raise DerivedArtifactError("chosen action candidate has malformed requiredPayloadFields")
    if len(set(required_value)) != len(required_value) or set(payload) != set(required_value):
        raise DerivedArtifactError("chosen action payload keys do not match the source contract")
    if any(value is None for value in payload.values()):
        raise DerivedArtifactError("chosen action payload contains a null choice")
    supported = {
        "targets", "xValue", "repeatCount", "manaColorChoice", "attackers", "bands", "blockers",
        "orderedBlockers", "paymentStrategy", "costPayment", "additionalCostPayment",
    }
    unsupported = set(payload) - supported
    if unsupported:
        raise DerivedArtifactError(
            "chosen action payload has no complete stored-domain validator: " + ",".join(sorted(unsupported))
        )
    if "targets" in payload:
        _validate_action_target_choice(candidate, payload["targets"])
    if "repeatCount" in payload:
        domain = _expect_object(candidate.get("repeatCountDomain"), "repeatCountDomain")
        _expect_keys(domain, {"version", "minCount", "maxCount"}, "repeatCountDomain")
        if domain["version"] != 1 or domain["minCount"] != 1:
            raise DerivedArtifactError("unsupported repeat-count domain")
        count = _expect_int(payload["repeatCount"], "repeatCount")
        if count < domain["minCount"] or count > domain["maxCount"]:
            raise DerivedArtifactError("repeatCount is outside the source domain")
    if "xValue" in payload:
        if candidate.get("hasXCost") is not True or not isinstance(candidate.get("maxAffordableX"), int):
            raise DerivedArtifactError("xValue has no complete source bound")
        x_value = _expect_int(payload["xValue"], "xValue")
        if x_value < 0 or x_value > candidate["maxAffordableX"]:
            raise DerivedArtifactError("xValue is outside the source domain")
    if "manaColorChoice" in payload:
        colors = candidate.get("availableManaColors")
        if not isinstance(colors, list) or payload["manaColorChoice"] not in colors:
            raise DerivedArtifactError("manaColorChoice is outside the source domain")
    if "paymentStrategy" in payload:
        _validate_payment_strategy(candidate, payload["paymentStrategy"], payload)
    for key in ("attackers", "bands", "blockers", "orderedBlockers"):
        if key in payload:
            if candidate.get("attackDeclarationDomain") is None and key in {"attackers", "bands"}:
                raise DerivedArtifactError("attack declaration has no complete source domain")
            if candidate.get("blockerDeclarationDomain") is None and key in {"blockers", "orderedBlockers"}:
                raise DerivedArtifactError("blocker declaration has no complete source domain")
            _validate_payload_references_against_candidate(candidate, payload[key], key)
    for key in ("costPayment", "additionalCostPayment"):
        if key in payload:
            _expect_object(payload[key], key)
            _validate_payload_references_against_candidate(candidate, payload[key], key)


def _validate_action_target_choice(candidate: dict[str, Any], value: Any) -> None:
    domain = _expect_object(candidate.get("targetDomain"), "targetDomain")
    _expect_keys(domain, {"version", "composition", "requirements"}, "targetDomain")
    if domain["version"] != 1 or domain["composition"] != "FIXED":
        raise DerivedArtifactError("unsupported action target domain")
    requirements = []
    for expected_index, raw in enumerate(_expect_list(domain["requirements"], "targetDomain.requirements")):
        requirement = _expect_object(raw, "target requirement")
        _expect_keys(
            requirement,
            {
                "index", "description", "minTargets", "maxTargets", "candidates", "targetZone",
                "mustDifferFromEarlier", "sameController", "sameOwner", "sameCreatureType",
                "sameCardType", "totalManaValueAtMost", "differentNames", "xConstrainsManaValue",
                "xConstrainsManaValueExactly", "xConstrainsPower", "xConstrainsCount",
            },
            "target requirement",
        )
        if requirement["index"] != expected_index:
            raise DerivedArtifactError("action target requirements are not producer ordered")
        minimum = _expect_int(requirement["minTargets"], "target requirement minTargets", nonnegative=True)
        maximum = _expect_int(requirement["maxTargets"], "target requirement maxTargets", nonnegative=True)
        if maximum < minimum:
            raise DerivedArtifactError("target requirement has an invalid cardinality")
        candidates = _string_set(requirement["candidates"], "target requirement candidates")
        unresolved = any(
            requirement[key] is not False
            for key in (
                "sameController", "sameOwner", "sameCreatureType", "sameCardType", "differentNames",
                "xConstrainsManaValue", "xConstrainsManaValueExactly", "xConstrainsPower", "xConstrainsCount",
            )
        ) or requirement["totalManaValueAtMost"] is not None
        if unresolved:
            raise DerivedArtifactError("target choice requires unavailable source metadata")
        requirements.append((minimum, maximum, candidates, requirement["mustDifferFromEarlier"]))
    selected = [_chosen_target_entity_id(item) for item in _expect_list(value, "chosen targets")]
    variable = [index for index, (minimum, maximum, _, _) in enumerate(requirements) if minimum != maximum]
    if len(variable) > 1:
        raise DerivedArtifactError("target domain has ambiguous flat payload partition")
    total_min = sum(item[0] for item in requirements)
    total_max = sum(item[1] for item in requirements)
    if not total_min <= len(selected) <= total_max:
        raise DerivedArtifactError("chosen targets have an outside-domain cardinality")
    variable_index = variable[0] if variable else None
    fixed_minimum = sum(item[0] for index, item in enumerate(requirements) if index != variable_index)
    counts = [
        len(selected) - fixed_minimum if index == variable_index else item[0]
        for index, item in enumerate(requirements)
    ]
    if any(count < requirements[index][0] or count > requirements[index][1] for index, count in enumerate(counts)):
        raise DerivedArtifactError("chosen targets cannot be partitioned by the source domain")
    offset = 0
    earlier: set[str] = set()
    for index, count in enumerate(counts):
        slot = selected[offset:offset + count]
        if len(set(slot)) != len(slot) or any(item not in requirements[index][2] for item in slot):
            raise DerivedArtifactError("chosen target is outside the source requirement")
        if requirements[index][3] and any(item in earlier for item in slot):
            raise DerivedArtifactError("chosen target violates source distinctness")
        earlier.update(slot)
        offset += count
    if offset != len(selected):
        raise DerivedArtifactError("chosen target contains an unassigned member")


def _chosen_target_entity_id(value: Any) -> str:
    target = _expect_object(value, "chosen target")
    target_type = target.get("type")
    key_by_type = {"Player": "playerId", "Permanent": "entityId", "Card": "cardId", "Spell": "spellEntityId"}
    key = key_by_type.get(target_type)
    if key is None:
        raise DerivedArtifactError("unsupported chosen target type")
    return _expect_string(target.get(key), f"chosen target {key}")


def _string_set(value: Any, label: str) -> set[str]:
    values = _expect_list(value, label)
    if any(not isinstance(item, str) or not item for item in values):
        raise DerivedArtifactError(f"{label} must contain non-empty strings")
    if len(set(values)) != len(values):
        raise DerivedArtifactError(f"{label} contains duplicate members")
    return set(values)


def _validate_payload_references_against_candidate(candidate: dict[str, Any], value: Any, label: str) -> None:
    known = set(candidate.get("validSacrificeTargets", []))
    semantics = candidate.get("actionSemantics")
    if isinstance(semantics, dict):
        for key in ("sourceEntityId", "attackerId", "vehicleId", "mountId"):
            if isinstance(semantics.get(key), str):
                known.add(semantics[key])
    if not known:
        return
    def visit(child: Any) -> None:
        if isinstance(child, dict):
            for key, item in child.items():
                if key.endswith("Id") or key.endswith("Ids"):
                    if isinstance(item, str) and item not in known:
                        raise DerivedArtifactError(f"{label} references a member outside the source candidate")
                    if isinstance(item, list) and any(entry not in known for entry in item if isinstance(entry, str)):
                        raise DerivedArtifactError(f"{label} references a member outside the source candidate")
                visit(item)
        elif isinstance(child, list):
            for item in child:
                visit(item)
    visit(value)


def _validate_payment_strategy(candidate: dict[str, Any], value: Any, payload: dict[str, Any]) -> None:
    strategy = _expect_object(value, "paymentStrategy")
    if strategy.get("type") != "ExplicitV3":
        raise DerivedArtifactError("only ExplicitV3 payment is admitted")
    plan = _expect_object(strategy.get("paymentPlan"), "paymentStrategy.paymentPlan")
    domain = candidate.get("paymentDomain")
    if domain is None and candidate.get("targetPaymentDomain") is None:
        raise DerivedArtifactError("payment strategy has no complete source domain")
    _validate_payment_plan(domain or {}, plan)


def _validate_payment_plan(domain: dict[str, Any], plan: dict[str, Any]) -> None:
    _expect_keys(plan, {"activations", "outerAllocation"}, "paymentPlan")
    _expect_keys(domain, {"version", "requiredCost", "outerAtomicCostUnits", "initialPoolBuckets", "sourceActivationOptions", "reservedOuterLifePayment", "fixedSelfDamageBudget"}, "paymentDomain")
    if domain["version"] != 5:
        raise DerivedArtifactError("unsupported PaymentDomainV5 version")
    options = {}
    for option in _expect_list(domain["sourceActivationOptions"], "paymentDomain.sourceActivationOptions"):
        obj = _expect_object(option, "payment source option")
        key = (obj.get("sourceId"), obj.get("manaAbilityKey"))
        if not isinstance(key[0], str) or not isinstance(key[1], str) or key in options:
            raise DerivedArtifactError("malformed payment source option")
        options[key] = obj
    expected_targets: dict[str, dict[str, Any]] = {}
    selected_options: list[dict[str, Any]] = []
    activations = _expect_list(plan["activations"], "paymentPlan.activations")
    selected_sources: set[str] = set()
    for activation_index, activation in enumerate(activations):
        obj = _expect_object(activation, "payment activation")
        _expect_keys(obj, {"sourceId", "manaAbilityKey", "productionChoice", "activationCostOrder", "activationCostAllocation"}, "payment activation")
        source_key = (obj["sourceId"], obj["manaAbilityKey"])
        if source_key not in options or obj["sourceId"] in selected_sources:
            raise DerivedArtifactError("payment plan selects a source outside the source domain")
        selected_sources.add(obj["sourceId"])
        option = options[source_key]
        if not any(_canonical_element(obj["productionChoice"], "production choice") == _canonical_element(choice, "source production choice") for choice in _expect_list(option["productionChoices"], "source production choices")):
            raise DerivedArtifactError("payment plan selects an outside production choice")
        orders = _expect_list(option["activationCostOrderOptions"], "activation cost order options")
        if not any(_canonical_element(obj["activationCostOrder"], "activation cost order") == _canonical_element(order, "source activation cost order") for order in orders):
            raise DerivedArtifactError("payment plan selects an outside activation-cost order")
        selected_options.append(option)
        for unit in _expect_list(option["atomicActivationManaCostUnits"], "activation cost units"):
            unit_obj = _expect_object(unit, "activation cost unit")
            expected_targets[_canonical_element({"type": "ActivationCostUnit", "activationIndex": activation_index, "symbolIndex": unit_obj["symbolIndex"], "unitIndexWithinSymbol": unit_obj["unitIndexWithinSymbol"]}, "activation cost target")] = unit_obj
    for unit in _expect_list(domain["outerAtomicCostUnits"], "outer cost units"):
        unit_obj = _expect_object(unit, "outer cost unit")
        expected_targets[_canonical_element({"type": "OuterCostUnit", "symbolIndex": unit_obj["symbolIndex"], "unitIndexWithinSymbol": unit_obj["unitIndexWithinSymbol"]}, "outer cost target")] = unit_obj
    pool_buckets: dict[str, tuple[int, str]] = {}
    for bucket in _expect_list(domain["initialPoolBuckets"], "initial pool buckets"):
        bucket_obj = _expect_object(bucket, "initial pool bucket")
        key = _canonical_element(bucket_obj.get("key"), "initial pool bucket key")
        key_obj = _expect_object(bucket_obj.get("key"), "initial pool bucket key")
        if key_obj.get("type") == "UnrestrictedPoolBucket":
            color = _expect_string(key_obj.get("color"), "initial pool color")
        elif key_obj.get("type") == "CertifiedFloatingBucket":
            color = _expect_string(_expect_object(key_obj.get("key"), "certified pool bucket key").get("poolColor"), "certified pool color")
        else:
            raise DerivedArtifactError("unsupported initial pool bucket key")
        pool_buckets[key] = (_expect_int(bucket_obj.get("availableAmount"), "initial pool availability", nonnegative=True), color)
    output_colors = [_production_colors(_expect_object(activation, "payment activation")["productionChoice"]) for activation in activations]
    fixed_self_damage = sum(
        _expect_int(option.get("fixedSelfDamageAmount"), "fixed self-damage amount", nonnegative=True)
        for option in selected_options
    )
    if domain["fixedSelfDamageBudget"] is not None and fixed_self_damage > _expect_int(domain["fixedSelfDamageBudget"], "fixed self-damage budget", nonnegative=True):
        raise DerivedArtifactError("payment plan exceeds the source fixed self-damage budget")
    used_pool: dict[str, int] = {}
    used_outputs: set[tuple[int, int]] = set()
    seen_targets: set[str] = set()
    for activation_index, activation in enumerate(activations):
        for allocation in _expect_list(_expect_object(activation, "payment activation")["activationCostAllocation"], "activation cost allocation"):
            _validate_payment_allocation(
                allocation,
                expected_targets,
                seen_targets,
                activation_index,
                output_colors,
                pool_buckets,
                used_pool,
                used_outputs,
            )
    for allocation in _expect_list(plan["outerAllocation"], "outer allocation"):
        _validate_payment_allocation(
            allocation,
            expected_targets,
            seen_targets,
            None,
            output_colors,
            pool_buckets,
            used_pool,
            used_outputs,
        )
    if seen_targets != set(expected_targets):
        raise DerivedArtifactError("payment plan does not allocate every source-domain cost target")


def _production_colors(value: Any) -> list[str]:
    choice = _expect_object(value, "production choice")
    if choice.get("fixedOutputs") is not None:
        if choice.get("bonusChoice") is not None:
            raise DerivedArtifactError("fixed production carries a bonus choice")
        outputs = _expect_list(choice["fixedOutputs"], "fixed production outputs")
        if not outputs or [
            _expect_int(_expect_object(output, "fixed output").get("index"), "fixed output index")
            for output in outputs
        ] != list(range(len(outputs))):
            raise DerivedArtifactError("fixed production output indices are not canonical")
        colors = []
        for output in outputs:
            output_obj = _expect_object(output, "fixed output")
            if _expect_int(output_obj.get("amount"), "fixed output amount") != 1:
                raise DerivedArtifactError("fixed production output amount is not canonical")
            colors.append(_expect_string(output_obj.get("color"), "fixed output color"))
        if choice.get("producedColor") != colors[0]:
            raise DerivedArtifactError("fixed production first color mismatch")
        return colors
    if choice.get("amount") != 1 or choice.get("bonusChoice") is not None:
        raise DerivedArtifactError("single-output production is not canonical")
    return [_expect_string(choice.get("producedColor"), "production color")]


def _validate_payment_allocation(
    value: Any,
    expected_targets: dict[str, dict[str, Any]],
    seen_targets: set[str],
    activation_index: int | None,
    output_colors: list[list[str]],
    pool_buckets: dict[str, tuple[int, str]],
    used_pool: dict[str, int],
    used_outputs: set[tuple[int, int]],
) -> None:
    allocation = _expect_object(value, "payment allocation")
    _expect_keys(allocation, {"target", "resource"}, "payment allocation")
    target = _expect_object(allocation["target"], "payment allocation target")
    target_key = _canonical_element(target, "payment allocation target")
    if target_key not in expected_targets or target_key in seen_targets:
        raise DerivedArtifactError("payment allocation target is outside or duplicated in the source domain")
    if target.get("type") == "ActivationCostUnit" and target.get("activationIndex") != activation_index:
        raise DerivedArtifactError("activation allocation targets the wrong source activation")
    if target.get("type") == "OuterCostUnit" and activation_index is not None:
        raise DerivedArtifactError("outer allocation appears in an activation allocation")
    seen_targets.add(target_key)
    expected = expected_targets[target_key]
    resource = _expect_object(allocation["resource"], "payment allocation resource")
    resource_type = resource.get("type")
    if resource_type == "ActivationOutputUnit":
        resource_activation = resource.get("activationIndex")
        resource_output = resource.get("outputIndex")
        if not isinstance(resource_activation, int) or isinstance(resource_activation, bool) or not isinstance(resource_output, int) or isinstance(resource_output, bool):
            raise DerivedArtifactError("malformed activation output resource")
        if activation_index is not None and resource_activation >= activation_index:
            raise DerivedArtifactError("activation payment references a future output")
        if resource_activation < 0 or resource_activation >= len(output_colors) or resource_output < 0 or resource_output >= len(output_colors[resource_activation]):
            raise DerivedArtifactError("payment plan references an unknown activation output")
        if (resource_activation, resource_output) in used_outputs:
            raise DerivedArtifactError("payment plan consumes an activation output more than once")
        used_outputs.add((resource_activation, resource_output))
        color = output_colors[resource_activation][resource_output]
    elif resource_type == "InitialPoolResource":
        bucket_key = _canonical_element(resource.get("bucketKey"), "initial pool bucket resource")
        if bucket_key not in pool_buckets:
            raise DerivedArtifactError("payment plan references an unknown initial pool bucket")
        used = used_pool.get(bucket_key, 0)
        capacity, color = pool_buckets[bucket_key]
        if used >= capacity:
            raise DerivedArtifactError("payment plan exceeds an initial pool bucket capacity")
        used_pool[bucket_key] = used + 1
    else:
        raise DerivedArtifactError("unsupported payment resource")
    kind = expected.get("kind")
    allowed_colors = expected.get("allowedColors", [])
    if kind == "COLORED" and color not in allowed_colors:
        raise DerivedArtifactError("payment resource does not satisfy a colored source cost")
    if kind == "COLORLESS" and color != "COLORLESS":
        raise DerivedArtifactError("payment resource does not satisfy a colorless source cost")


def _validate_structured_response_membership(
    response: dict[str, Any],
    domain: dict[str, Any],
) -> None:
    response_type = response.get("type")
    structured_type = domain.get("type")
    if response_type == "CancelDecisionResponse":
        if structured_type != "targets" or domain.get("canCancel") is not True:
            raise DerivedArtifactError("cancel response is not admitted by the structured domain")
        return

    expected_response_types = {
        "targets": "TargetsResponse",
        "card-selection": "CardsSelectedResponse",
        "mode-selection": "ModesChosenResponse",
        "distribution": "DistributionResponse",
        "ordering": "OrderedResponse",
        "split-piles": "PilesSplitResponse",
        "search-library": "CardsSelectedResponse",
        "reorder-library": "OrderedResponse",
        "combat-resolution": "CombatResolutionResponse",
        "mana-sources": "ManaSourcesSelectedResponse",
        "replacement": "ReplacementChosenResponse",
        "budget-modal": "BudgetModalResponse",
    }
    if response_type != expected_response_types.get(structured_type):
        raise DerivedArtifactError("structured response type does not match its domain")

    if structured_type == "targets":
        requirements = _expect_list(domain.get("requirements"), "target requirements")
        requirements_by_index: dict[int, dict[str, Any]] = {}
        for requirement in requirements:
            obj = _expect_object(requirement, "target requirement")
            index = _expect_int(obj.get("index"), "target requirement index")
            if index in requirements_by_index:
                raise DerivedArtifactError("target requirements contain duplicate indices")
            requirements_by_index[index] = obj
        selected = _expect_object(response.get("selectedTargets"), "selectedTargets")
        if set(selected) != {str(index) for index in requirements_by_index}:
            raise DerivedArtifactError("target response does not cover the stored requirements")
        earlier: set[str] = set()
        for key, values in selected.items():
            requirement = requirements_by_index.get(int(key))
            if requirement is None:
                raise DerivedArtifactError("target response references an unknown requirement")
            selected_values = _expect_string_list(values, "selected target members")
            candidates = set(_expect_string_list(requirement.get("candidates"), "target candidates"))
            if any(member not in candidates for member in selected_values):
                raise DerivedArtifactError("target response contains an outside-domain target")
            minimum = _expect_int(requirement.get("minTargets"), "target minimum", nonnegative=True)
            maximum = _expect_int(requirement.get("maxTargets"), "target maximum", nonnegative=True)
            if len(selected_values) < minimum or len(selected_values) > maximum:
                raise DerivedArtifactError("target response violates source cardinality")
            if len(set(selected_values)) != len(selected_values):
                raise DerivedArtifactError("target response duplicates a source target")
            if requirement.get("mustDifferFromEarlier") is True and any(member in earlier for member in selected_values):
                raise DerivedArtifactError("target response violates source distinctness")
            if any(
                requirement.get(field) is True
                for field in (
                    "sameController", "sameOwner", "sameCreatureType", "sameCardType", "differentNames",
                    "xConstrainsManaValue", "xConstrainsManaValueExactly", "xConstrainsPower", "xConstrainsCount",
                )
            ) or requirement.get("totalManaValueAtMost") is not None:
                raise DerivedArtifactError("target response requires unavailable source metadata")
            earlier.update(selected_values)
        return

    if structured_type in {"card-selection", "search-library"}:
        selected = _expect_string_list(response.get("selectedCards"), "selectedCards")
        options = domain.get("options")
        if not isinstance(options, list):
            options = list(_expect_object(domain.get("cards"), "search cards"))
        allowed = set(_expect_string_list(options, "structured card options"))
        if any(card not in allowed for card in selected):
            raise DerivedArtifactError("card response contains an outside-domain card")
        if len(set(selected)) != len(selected):
            raise DerivedArtifactError("card response duplicates a source card")
        minimum = _expect_int(domain.get("minSelections"), "card minimum", nonnegative=True)
        maximum = _expect_int(domain.get("maxSelections"), "card maximum", nonnegative=True)
        if len(selected) < minimum or len(selected) > maximum:
            raise DerivedArtifactError("card response violates source cardinality")
        non_selectable = set(_expect_string_list(domain.get("nonSelectableOptions", []), "non-selectable options"))
        if any(card in non_selectable for card in selected):
            raise DerivedArtifactError("card response contains a non-selectable source card")
        for minimum_domain in _expect_list(domain.get("conditionalMinimums", []), "conditional minimums"):
            minimum_obj = _expect_object(minimum_domain, "conditional minimum")
            if len(selected) >= _expect_int(minimum_obj.get("requiredSelections"), "conditional required selections"):
                continue
            matching = set(_expect_string_list(minimum_obj.get("matchingOptions"), "conditional matching options"))
            if len(selected) < _expect_int(minimum_obj.get("minimumSelections"), "conditional minimum selections") or sum(card in matching for card in selected) < _expect_int(minimum_obj.get("requiredMatches"), "conditional required matches"):
                raise DerivedArtifactError("card response violates a source conditional minimum")
        return

    if structured_type == "mode-selection":
        selected = _expect_int_list(response.get("selectedModes"), "selectedModes")
        modes = _expect_list(domain.get("modes"), "mode options")
        mode_by_index = {
            _expect_int(_expect_object(mode, "mode option").get("index"), "mode index"): _expect_object(mode, "mode option")
            for mode in modes
        }
        if len(set(selected)) != len(selected) or any(mode not in mode_by_index or mode_by_index[mode].get("available") is not True for mode in selected):
            raise DerivedArtifactError("mode response contains an outside-domain mode")
        minimum = _expect_int(domain.get("minModes"), "mode minimum", nonnegative=True)
        maximum = _expect_int(domain.get("maxModes"), "mode maximum", nonnegative=True)
        if len(selected) < minimum or len(selected) > maximum:
            raise DerivedArtifactError("mode response violates source cardinality")
        return

    if structured_type == "distribution":
        distribution = _expect_object(response.get("distribution"), "distribution")
        allowed = set(_expect_string_list(domain.get("targets"), "distribution targets"))
        if any(key not in allowed for key in distribution):
            raise DerivedArtifactError("distribution response contains an outside-domain target")
        maxima = domain.get("maxPerTarget") or {}
        minimum = _expect_int(domain.get("minPerTarget"), "distribution minimum", nonnegative=True)
        total = 0
        for key, raw_amount in distribution.items():
            amount = _expect_int(raw_amount, f"distribution[{key}]", nonnegative=True)
            if amount < minimum:
                raise DerivedArtifactError("distribution response violates source minimum")
            if key in maxima and amount > _expect_int(maxima[key], f"distribution maximum[{key}]", nonnegative=True):
                raise DerivedArtifactError("distribution response exceeds source maximum")
            total += amount
        total_amount = _expect_int(domain.get("totalAmount"), "distribution total", nonnegative=True)
        if (not domain.get("allowPartial") and total != total_amount) or (domain.get("allowPartial") and total > total_amount):
            raise DerivedArtifactError("distribution response violates source total")
        if minimum > 0 and any(target not in distribution for target in allowed):
            raise DerivedArtifactError("distribution response omits a required source target")
        return

    if structured_type in {"ordering", "reorder-library"}:
        ordered = _expect_list(response.get("orderedObjects"), "orderedObjects")
        if structured_type == "reorder-library":
            allowed = set(_expect_string_list(domain.get("cards"), "reorder cards"))
            if any(not isinstance(item, str) or item not in allowed for item in ordered):
                raise DerivedArtifactError("reorder response contains an outside-domain card")
            if len(ordered) != len(allowed) or set(ordered) != allowed:
                raise DerivedArtifactError("reorder response is not the source permutation")
        else:
            _validate_ordering_references(ordered, domain, require_exact=True)
        return

    if structured_type == "split-piles":
        allowed = set(_expect_string_list(domain.get("cards"), "split-pile cards"))
        piles = _expect_list(response.get("piles"), "split piles")
        for pile in piles:
            members = _expect_string_list(pile, "split-pile members")
            if any(member not in allowed for member in members):
                raise DerivedArtifactError("split response contains an outside-domain card")
        flattened = [member for pile in piles for member in _expect_string_list(pile, "split-pile members")]
        if len(piles) != _expect_int(domain.get("numberOfPiles"), "split-pile count"):
            raise DerivedArtifactError("split response has the wrong source pile count")
        if len(flattened) != len(set(flattened)) or set(flattened) != allowed:
            raise DerivedArtifactError("split response is not the source card partition")
        return

    if structured_type == "combat-resolution":
        allowed = {
            _expect_object(edge, "combat edge").get("id")
            for edge in _expect_list(domain.get("edges"), "combat edges")
        }
        edges = _expect_list(response.get("edges"), "combat response edges")
        for edge in edges:
            edge_obj = _expect_object(edge, "combat response edge")
            if edge_obj.get("edgeId") not in allowed:
                raise DerivedArtifactError("combat response contains an outside-domain edge")
        if len({ _expect_object(edge, "combat response edge").get("edgeId") for edge in edges }) != len(edges):
            raise DerivedArtifactError("combat response duplicates a source edge")
        amounts = {
            _expect_object(edge, "combat domain edge").get("id"): _expect_object(edge, "combat domain edge").get("amount")
            for edge in _expect_list(domain.get("edges"), "combat edges")
        }
        for edge in edges:
            edge_obj = _expect_object(edge, "combat response edge")
            source_edge = next(item for item in _expect_list(domain.get("edges"), "combat edges") if _expect_object(item, "combat edge").get("id") == edge_obj.get("edgeId"))
            maximum = _expect_int(_expect_object(source_edge, "combat edge").get("maximum"), "combat edge maximum", nonnegative=True)
            amount = _expect_int(edge_obj.get("amount"), "combat edge amount", nonnegative=True)
            if amount > maximum:
                raise DerivedArtifactError("combat response exceeds a source edge maximum")
            amounts[edge_obj.get("edgeId")] = amount
        by_source: dict[Any, list[int]] = {}
        for edge in _expect_list(domain.get("edges"), "combat edges"):
            edge_obj = _expect_object(edge, "combat edge")
            by_source.setdefault(edge_obj.get("sourceId"), []).append(amounts[edge_obj.get("id")])
        for source_id, source_amounts in by_source.items():
            source_edges = [
                _expect_object(edge, "combat edge")
                for edge in _expect_list(domain.get("edges"), "combat edges")
                if _expect_object(edge, "combat edge").get("sourceId") == source_id
            ]
            maximums = {_expect_int(edge.get("maximum"), "combat edge maximum") for edge in source_edges}
            if len(maximums) != 1 or sum(source_amounts) != next(iter(maximums)):
                raise DerivedArtifactError("combat response does not satisfy the source edge total")
        return

    if structured_type == "mana-sources":
        payment = _expect_object(domain.get("paymentDomain"), "payment domain")
        source_options = _expect_list(
            payment.get("sourceActivationOptions"),
            "mana source options",
        )
        allowed = {
            _expect_object(option, "mana source option").get("sourceId")
            for option in source_options
        }
        selected_sources = response.get("selectedSources", [])
        selected = _expect_string_list(selected_sources, "selectedSources")
        if any(source not in allowed for source in selected):
            raise DerivedArtifactError("mana response contains an outside-domain source")
        if response.get("paymentPlan") is not None:
            _validate_payment_plan(payment, _expect_object(response["paymentPlan"], "paymentPlan"))
        elif response.get("declined") is not True:
            raise DerivedArtifactError("mana response has no explicit V3 payment plan")
        return

    if structured_type == "replacement":
        from_index = _expect_int(response.get("fromIndex"), "replacement fromIndex")
        to_index = _expect_int(response.get("toIndex"), "replacement toIndex")
        from_options = _expect_list(domain.get("fromOptions"), "replacement from options")
        to_options = _expect_list(domain.get("toOptions"), "replacement to options")
        if from_index < 0 or from_index >= len(from_options) or to_index < 0 or to_index >= len(to_options):
            raise DerivedArtifactError("replacement response contains an outside-domain option")
        allowed = _expect_list(domain.get("allowedToByFrom"), "replacement relations")
        if from_index >= len(allowed) or to_index not in _expect_list(allowed[from_index], "replacement relation"):
            raise DerivedArtifactError("replacement response violates the source relation")
        return

    if structured_type == "budget-modal":
        selected = _expect_int_list(response.get("selectedModeIndices"), "selectedModeIndices")
        modes = _expect_list(domain.get("modes"), "budget modes")
        if any(index < 0 or index >= len(modes) for index in selected):
            raise DerivedArtifactError("budget response contains an outside-domain mode")
        if sum(_expect_int(_expect_object(modes[index], "budget mode").get("cost"), "budget mode cost") for index in selected) > _expect_int(domain.get("budget"), "budget"):
            raise DerivedArtifactError("budget response exceeds the source budget")
        return

    raise DerivedArtifactError("unsupported structured response membership")


def _expect_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise DerivedArtifactError(f"{label} must be a list")
    return value


def _expect_string(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise DerivedArtifactError(f"{label} must be a string")
    return value


def _expect_string_list(value: Any, label: str) -> list[str]:
    values = _expect_list(value, label)
    if any(not isinstance(item, str) for item in values):
        raise DerivedArtifactError(f"{label} must contain strings")
    return values


def _expect_int_list(value: Any, label: str) -> list[int]:
    values = _expect_list(value, label)
    if any(not isinstance(item, int) or isinstance(item, bool) for item in values):
        raise DerivedArtifactError(f"{label} must contain integers")
    return values


def _validate_ordering_references(
    ordered: list[Any],
    domain: dict[str, Any],
    *,
    require_exact: bool = False,
) -> None:
    objects = _expect_string_list(domain.get("objects"), "ordering objects")
    labels = domain.get("objectLabels") or {}
    card_info = domain.get("cardInfo") or {}
    expected: set[str] = set()
    for object_id in objects:
        if object_id.startswith("trigger-order-object-"):
            semantic: dict[str, Any] = {}
            label = labels.get(object_id)
            if isinstance(label, str) and label:
                semantic["label"] = label
            info = card_info.get(object_id)
            if isinstance(info, dict):
                semantic["cardInfo"] = {
                    key: value for key, value in info.items()
                    if key not in {"description", "filterDescription", "iconKey", "imageUri", "pileLabels", "remainderLabel", "selectedLabel", "text", "useTargetingUI"}
                }
            if semantic:
                expected.add(
                    _canonical_element(
                        {"referenceType": "TRIGGER", "semantic": semantic},
                        "ordering domain reference",
                    )
                )
        else:
            expected.add(
                _canonical_element(
                    {"referenceType": "ENTITY", "entityId": object_id},
                    "ordering domain reference",
                )
            )
    actual: set[str] = set()
    for reference in ordered:
        if not isinstance(reference, dict):
            raise DerivedArtifactError("ordering response contains a malformed reference")
        actual.add(_canonical_element(reference, "ordering response reference"))
    if len(actual) != len(ordered) or not actual.issubset(expected):
        raise DerivedArtifactError("ordering response contains an outside-domain object")
    if require_exact and actual != expected:
        raise DerivedArtifactError("ordering response is not the source permutation")
