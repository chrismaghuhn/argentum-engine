"""Strict reader for Kotlin-produced C1 derived learner artifacts."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator

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
from ..contracts.model_facing import require_model_input
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


@dataclass(frozen=True)
class DerivedArtifactReader:
    root: Path
    manifest: dict[str, Any]

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
        _validate_sample_file(samples_path, manifest)
        return cls(root=root, manifest=manifest)

    def iter_samples(self) -> Iterator[dict[str, Any]]:
        samples_path = self.root / "samples.ndjson"
        with samples_path.open("rb") as stream:
            for raw_line in stream:
                sample = _parse_sample_line(raw_line)
                _validate_sample(sample, self.manifest)
                yield sample

    def stream_samples(self) -> Iterator[dict[str, Any]]:
        return self.iter_samples()

    def __iter__(self) -> Iterator[dict[str, Any]]:
        return self.iter_samples()


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


def _validate_sample_file(path: Path, manifest: dict[str, Any]) -> None:
    digest = hashlib.sha256()
    byte_count = 0
    sample_count = 0
    partition_counts = {key: 0 for key in _PARTITIONS}
    seen_references: set[tuple[str, int]] = set()
    with path.open("rb") as stream:
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
    raw_entity_ids = _validate_alias_bindings(binding["entityAliasBindings"])
    _validate_input(sample["input"], raw_entity_ids)
    _validate_ordinals(binding["sourceBindingOrdinals"], domain)
    _validate_tie_discriminators(
        binding["semanticTieDiscriminators"],
        binding["sourceBindingOrdinals"],
        raw_entity_ids,
    )
    _validate_target_membership(target, binding["selectedExactSourceBinding"], domain)
    expected_partition = assign_partition(source["semanticEpisodeId"])
    if sample["partition"] != expected_partition:
        raise DerivedArtifactError("sample partition does not match semanticEpisodeId")


def _validate_input(value: Any, raw_entity_ids: set[str]) -> None:
    try:
        input_value = require_model_input(value)
    except ValueError as exc:
        raise DerivedArtifactError(str(exc)) from exc
    if "contractIdentity" in input_value or "terminated" in input_value or "truncated" in input_value:
        raise DerivedArtifactError("policy input contains a wrapper/admission control field")
    _reject_raw_literals(input_value, raw_entity_ids, "model input")


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
    elif domain["structuredDomain"] is not None:
        raise DerivedArtifactError("flat domain cannot carry a structured domain")
    elif domain["kind"] == "ACTION_CANDIDATES":
        if domain["decisionKind"] is not None or domain["shape"] is not None:
            raise DerivedArtifactError("action domain carries pending-decision fields")
    elif domain["decisionKind"] is None or domain["shape"] is None:
        raise DerivedArtifactError("folded domain is missing pending-decision fields")
    return domain


def _validate_alias_bindings(value: Any) -> set[str]:
    if not isinstance(value, list):
        raise DerivedArtifactError("entityAliasBindings must be a list")
    raw_ids: set[str] = set()
    for index, binding in enumerate(value):
        obj = _expect_object(binding, "entityAliasBinding")
        _expect_keys(obj, {"alias", "sourceEntityId"}, "entityAliasBinding")
        if obj["alias"] != f"entity-{index}" or not isinstance(obj["sourceEntityId"], str):
            raise DerivedArtifactError("entity aliases are not producer-canonical")
        if obj["sourceEntityId"] in raw_ids:
            raise DerivedArtifactError("entity aliases are not injective")
        raw_ids.add(obj["sourceEntityId"])
    return raw_ids


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
        for key, values in selected.items():
            requirement = requirements_by_index.get(int(key))
            if requirement is None:
                raise DerivedArtifactError("target response references an unknown requirement")
            selected_values = _expect_string_list(values, "selected target members")
            candidates = set(_expect_string_list(requirement.get("candidates"), "target candidates"))
            if any(member not in candidates for member in selected_values):
                raise DerivedArtifactError("target response contains an outside-domain target")
        return

    if structured_type in {"card-selection", "search-library"}:
        selected = _expect_string_list(response.get("selectedCards"), "selectedCards")
        options = domain.get("options")
        if not isinstance(options, list):
            options = list(_expect_object(domain.get("cards"), "search cards"))
        allowed = set(_expect_string_list(options, "structured card options"))
        if any(card not in allowed for card in selected):
            raise DerivedArtifactError("card response contains an outside-domain card")
        return

    if structured_type == "mode-selection":
        selected = _expect_int_list(response.get("selectedModes"), "selectedModes")
        modes = _expect_list(domain.get("modes"), "mode options")
        available = {
            _expect_int(_expect_object(mode, "mode option").get("index"), "mode index")
            for mode in modes
        }
        if any(mode not in available for mode in selected):
            raise DerivedArtifactError("mode response contains an outside-domain mode")
        return

    if structured_type == "distribution":
        distribution = _expect_object(response.get("distribution"), "distribution")
        allowed = set(_expect_string_list(domain.get("targets"), "distribution targets"))
        if any(key not in allowed for key in distribution):
            raise DerivedArtifactError("distribution response contains an outside-domain target")
        return

    if structured_type in {"ordering", "reorder-library"}:
        ordered = _expect_list(response.get("orderedObjects"), "orderedObjects")
        if structured_type == "reorder-library":
            allowed = set(_expect_string_list(domain.get("cards"), "reorder cards"))
            if any(not isinstance(item, str) or item not in allowed for item in ordered):
                raise DerivedArtifactError("reorder response contains an outside-domain card")
        else:
            _validate_ordering_references(ordered, domain)
        return

    if structured_type == "split-piles":
        allowed = set(_expect_string_list(domain.get("cards"), "split-pile cards"))
        piles = _expect_list(response.get("piles"), "split piles")
        for pile in piles:
            members = _expect_string_list(pile, "split-pile members")
            if any(member not in allowed for member in members):
                raise DerivedArtifactError("split response contains an outside-domain card")
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
        return

    if structured_type == "replacement":
        from_index = _expect_int(response.get("fromIndex"), "replacement fromIndex")
        to_index = _expect_int(response.get("toIndex"), "replacement toIndex")
        from_options = _expect_list(domain.get("fromOptions"), "replacement from options")
        to_options = _expect_list(domain.get("toOptions"), "replacement to options")
        if from_index < 0 or from_index >= len(from_options) or to_index < 0 or to_index >= len(to_options):
            raise DerivedArtifactError("replacement response contains an outside-domain option")
        return

    if structured_type == "budget-modal":
        selected = _expect_int_list(response.get("selectedModeIndices"), "selectedModeIndices")
        modes = _expect_list(domain.get("modes"), "budget modes")
        if any(index < 0 or index >= len(modes) for index in selected):
            raise DerivedArtifactError("budget response contains an outside-domain mode")
        return

    raise DerivedArtifactError("unsupported structured response membership")


def _expect_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise DerivedArtifactError(f"{label} must be a list")
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


def _validate_ordering_references(ordered: list[Any], domain: dict[str, Any]) -> None:
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
    if not actual.issubset(expected):
        raise DerivedArtifactError("ordering response contains an outside-domain object")
