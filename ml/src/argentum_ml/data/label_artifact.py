"""Strict C1_05 supervised-policy label sidecar artifact."""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any, Iterable

from ..contracts.canonical_json import _RawJsonNumber, canonical_bytes, canonical_json, sha256_hex
from ..contracts.identities import (
    LABEL_ARTIFACT_IDENTITY_SCHEMA,
    LABEL_ARTIFACT_SCHEMA_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    SOURCE_DECISION_KEY_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
    SUPERVISED_POLICY_TARGET_IDENTITY,
)
from .split import assign_partition

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GIT_SHA1 = re.compile(r"^[0-9a-f]{40}$")
_PARTITIONS = ("TRAIN", "VALIDATION", "TEST")
_LABEL_PARTITIONS = {"TRAIN", "VALIDATION"}
_FAMILIES = {"ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS"}
_MANIFEST_KEYS = {
    "version",
    "labelArtifactSchemaIdentity",
    "labelArtifactIdentitySchema",
    "labelArtifactId",
    "supervisedPolicyTargetContractIdentity",
    "sourceDatasetId",
    "sourceManifestContentDigest",
    "sourceDerivedArtifactId",
    "sourceDerivedViewSchemaIdentity",
    "trajectorySchemaIdentity",
    "modelFacingContractIdentity",
    "splitContractIdentity",
    "teacherProvenance",
    "labelMaterializerImplementationIdentity",
    "labelMaterializerConfigDigest",
    "allowedPartitions",
    "sourceDecisionKeyIdentity",
    "labelsContentReference",
    "labelsContentDigest",
    "labelsByteCount",
    "labelCount",
    "sourceRowsByPartition",
    "processedRowsByPartition",
    "teacherCallsByPartition",
    "labelCountsByPartition",
    "labelCountsByDecisionFamily",
    "expectedNoLabelByPartitionAndReason",
    "rejectedInvalidSourceBindingByPartition",
    "rejectedSplitByPartition",
    "rejectedProvenanceByPartition",
    "duplicateDecisionKeyCount",
    "conflictingLabelCount",
    "otherFailClosedMaterializerErrorCount",
    "testRowsConsumed",
    "manifestContentDigest",
}
_ROW_KEYS = {"version", "partition", "decisionFamily", "sourceReference", "target", "binding", "provenance"}
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
_BINDING_KEYS = {"selectedExactSourceBinding", "sourceBindingOrdinal"}
_PROVENANCE_KEYS = {
    "teacherResultSchemaIdentity",
    "teacherConfigDigest",
    "selectionContractIdentity",
    "policyRngIdentity",
    "teacherSeatIndex",
    "candidateCount",
    "rngDrawCount",
    "cursorBefore",
    "cursorAfter",
    "tieOccurred",
    "policyTieRngWordsConsumed",
}
_TEACHER_PROVENANCE_KEYS = {
    "teacherContractIdentity",
    "teacherPolicyIdentity",
    "teacherSourceIdentity",
    "teacherSourceCommit",
    "teacherConfigSchemaIdentity",
    "teacherConfigDigest",
    "scorerIdentity",
    "selectionContractIdentity",
    "policyRngIdentity",
    "teacherPolicyTieScheduleIdentity",
    "teacherPolicyTieSeed",
    "initialPolicyTieCursor",
    "teacherTieStateScope",
    "legacyA9PolicySeedReused",
    "teacherExecutionConfigDigest",
    "teacherAdmissionPurposeIdentity",
    "teacherAdmissionResult",
    "teacherAdmissionPlanIdentity",
    "teacherAdmissionPlanDigest",
}
_IMPLEMENTATION_KEYS = {"implementation", "sourceCommit"}


class LabelArtifactError(ValueError):
    """Raised for malformed, noncanonical, or incompatible C1_05 artifacts."""


def _duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise LabelArtifactError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _parse_json(raw: bytes, label: str) -> Any:
    try:
        return json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_duplicate_pairs,
            parse_float=_RawJsonNumber,
            parse_constant=lambda value: (_ for _ in ()).throw(
                LabelArtifactError(f"non-finite JSON constant in {label}: {value}")
            ),
        )
    except LabelArtifactError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise LabelArtifactError(f"malformed {label}") from exc


def _expect_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise LabelArtifactError(f"{label} must be an object")
    return value


def _expect_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise LabelArtifactError(f"{label} fields are not exact")


def _expect_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise LabelArtifactError(f"{label} must be a non-empty string")
    return value


def _expect_sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        raise LabelArtifactError(f"{label} must be lowercase SHA-256 hex")
    return value


def _expect_int(value: Any, label: str, *, nonnegative: bool = True) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise LabelArtifactError(f"{label} must be an integer")
    if nonnegative and value < 0:
        raise LabelArtifactError(f"{label} must not be negative")
    return value


def _partition_counts(value: Any, label: str) -> dict[str, int]:
    obj = _expect_object(value, label)
    _expect_keys(obj, set(_PARTITIONS), label)
    return {partition: _expect_int(obj[partition], f"{label}.{partition}") for partition in _PARTITIONS}


def _family_counts(value: Any) -> dict[str, int]:
    obj = _expect_object(value, "labelCountsByDecisionFamily")
    _expect_keys(obj, _FAMILIES, "labelCountsByDecisionFamily")
    return {family: _expect_int(obj[family], family) for family in _FAMILIES}


def _accounting_partition_counts(value: Any, label: str) -> dict[str, int]:
    return _partition_counts(value, label)


def _identity_payload(manifest: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema": manifest["labelArtifactIdentitySchema"],
        "labelArtifactSchemaIdentity": manifest["labelArtifactSchemaIdentity"],
        "supervisedPolicyTargetContractIdentity": manifest["supervisedPolicyTargetContractIdentity"],
        "sourceDatasetId": manifest["sourceDatasetId"],
        "sourceManifestContentDigest": manifest["sourceManifestContentDigest"],
        "sourceDerivedArtifactId": manifest["sourceDerivedArtifactId"],
        "sourceDerivedViewSchemaIdentity": manifest["sourceDerivedViewSchemaIdentity"],
        "trajectorySchemaIdentity": manifest["trajectorySchemaIdentity"],
        "modelFacingContractIdentity": manifest["modelFacingContractIdentity"],
        "splitContractIdentity": manifest["splitContractIdentity"],
        "sourceDecisionKeyIdentity": manifest["sourceDecisionKeyIdentity"],
        "teacherProvenance": manifest["teacherProvenance"],
        "labelMaterializerImplementationIdentity": manifest["labelMaterializerImplementationIdentity"],
        "labelMaterializerConfigDigest": manifest["labelMaterializerConfigDigest"],
        "allowedPartitions": manifest["allowedPartitions"],
        "labelsContentDigest": manifest["labelsContentDigest"],
        "sourceRowsByPartition": manifest["sourceRowsByPartition"],
        "processedRowsByPartition": manifest["processedRowsByPartition"],
        "teacherCallsByPartition": manifest["teacherCallsByPartition"],
        "labelCountsByPartition": manifest["labelCountsByPartition"],
        "labelCountsByDecisionFamily": manifest["labelCountsByDecisionFamily"],
        "expectedNoLabelByPartitionAndReason": manifest["expectedNoLabelByPartitionAndReason"],
        "rejectedInvalidSourceBindingByPartition": manifest["rejectedInvalidSourceBindingByPartition"],
        "rejectedSplitByPartition": manifest["rejectedSplitByPartition"],
        "rejectedProvenanceByPartition": manifest["rejectedProvenanceByPartition"],
        "duplicateDecisionKeyCount": manifest["duplicateDecisionKeyCount"],
        "conflictingLabelCount": manifest["conflictingLabelCount"],
        "otherFailClosedMaterializerErrorCount": manifest["otherFailClosedMaterializerErrorCount"],
        "testRowsConsumed": manifest["testRowsConsumed"],
    }


def _source_key(row: dict[str, Any]) -> tuple[str, int, str]:
    source = row["sourceReference"]
    semantic = source["semanticDecisionId"]
    return source["trajectoryId"], source["decisionIndex"], semantic["value"]


def _row_sort_key(row: dict[str, Any]) -> tuple[int, str, str, int, str]:
    source = row["sourceReference"]
    return (
        {"TRAIN": 0, "VALIDATION": 1}[row["partition"]],
        source["semanticEpisodeId"],
        source["trajectoryId"],
        source["decisionIndex"],
        source["semanticDecisionId"]["value"],
    )


def _validate_teacher_provenance(value: Any) -> None:
    obj = _expect_object(value, "teacherProvenance")
    _expect_keys(obj, _TEACHER_PROVENANCE_KEYS, "teacherProvenance")
    for key in (
        "teacherContractIdentity",
        "teacherPolicyIdentity",
        "teacherSourceIdentity",
        "teacherConfigSchemaIdentity",
        "scorerIdentity",
        "selectionContractIdentity",
        "policyRngIdentity",
        "teacherPolicyTieScheduleIdentity",
        "teacherTieStateScope",
        "teacherAdmissionPurposeIdentity",
        "teacherAdmissionResult",
        "teacherAdmissionPlanIdentity",
    ):
        _expect_string(obj[key], f"teacherProvenance.{key}")
    if _GIT_SHA1.fullmatch(_expect_string(obj["teacherSourceCommit"], "teacherSourceCommit")) is None:
        raise LabelArtifactError("teacherSourceCommit must be a Git SHA-1")
    for key in ("teacherConfigDigest", "teacherExecutionConfigDigest", "teacherAdmissionPlanDigest"):
        _expect_sha(obj[key], f"teacherProvenance.{key}")
    for key in ("teacherPolicyTieSeed", "initialPolicyTieCursor"):
        _expect_int(obj[key], f"teacherProvenance.{key}")
    if obj["legacyA9PolicySeedReused"] is not False:
        raise LabelArtifactError("legacy A9 policy seed reuse is forbidden")


def _validate_row(row: dict[str, Any], manifest: dict[str, Any]) -> None:
    _expect_keys(row, _ROW_KEYS, "label row")
    if row["version"] != 1 or row["partition"] not in _LABEL_PARTITIONS:
        raise LabelArtifactError("unsupported label row version or partition")
    if row["decisionFamily"] not in _FAMILIES:
        raise LabelArtifactError("unsupported label decision family")
    source = _expect_object(row["sourceReference"], "sourceReference")
    _expect_keys(source, _SOURCE_REFERENCE_KEYS, "sourceReference")
    for key in ("datasetId", "sourceManifestContentDigest", "trajectoryId", "semanticEpisodeId", "collectionJobId"):
        _expect_sha(source[key], f"sourceReference.{key}")
    if source["datasetId"] != manifest["sourceDatasetId"]:
        raise LabelArtifactError("label source dataset mismatch")
    if source["sourceManifestContentDigest"] != manifest["sourceManifestContentDigest"]:
        raise LabelArtifactError("label source manifest mismatch")
    for key in ("decisionIndex", "replayActionIndex", "replayFrameIndex"):
        _expect_int(source[key], f"sourceReference.{key}")
    _expect_string(source["perspectivePlayerId"], "sourceReference.perspectivePlayerId")
    semantic = _expect_object(source["semanticDecisionId"], "semanticDecisionId")
    _expect_keys(semantic, {"version", "schemaIdentity", "value"}, "semanticDecisionId")
    if semantic["version"] != 1 or semantic["schemaIdentity"] != "argentum-trajectory-semantic-decision@v1":
        raise LabelArtifactError("unsupported semantic decision identity")
    _expect_sha(semantic["value"], "semanticDecisionId.value")
    if assign_partition(source["semanticEpisodeId"]) != row["partition"]:
        raise LabelArtifactError("label partition differs from frozen split")
    target = _expect_object(row["target"], "target")
    _expect_keys(target, {"chosenSemanticAction", "chosenSemanticResponse"}, "target")
    if (target["chosenSemanticAction"] is None) == (target["chosenSemanticResponse"] is None):
        raise LabelArtifactError("target must contain exactly one semantic value")
    binding = _expect_object(row["binding"], "binding")
    _expect_keys(binding, _BINDING_KEYS, "binding")
    _expect_int(binding["sourceBindingOrdinal"], "sourceBindingOrdinal")
    selected = _expect_object(binding["selectedExactSourceBinding"], "selectedExactSourceBinding")
    if target["chosenSemanticAction"] is not None:
        if selected.get("type") != "chosen-action" or canonical_json(target["chosenSemanticAction"]) != canonical_json(selected):
            raise LabelArtifactError("exact action target and binding differ")
    else:
        if selected.get("type") != "chosen-response" or canonical_json(target["chosenSemanticResponse"]) != canonical_json(selected):
            raise LabelArtifactError("exact response target and binding differ")
    provenance = _expect_object(row["provenance"], "provenance")
    _expect_keys(provenance, _PROVENANCE_KEYS, "provenance")
    for key in ("teacherResultSchemaIdentity", "selectionContractIdentity", "policyRngIdentity"):
        _expect_string(provenance[key], f"provenance.{key}")
    _expect_sha(provenance["teacherConfigDigest"], "provenance.teacherConfigDigest")
    for key in ("teacherSeatIndex", "candidateCount", "rngDrawCount", "cursorBefore", "cursorAfter", "policyTieRngWordsConsumed"):
        _expect_int(provenance[key], f"provenance.{key}")
    if not isinstance(provenance["tieOccurred"], bool):
        raise LabelArtifactError("provenance.tieOccurred must be boolean")


def _validate_manifest(manifest: dict[str, Any]) -> None:
    _expect_keys(manifest, _MANIFEST_KEYS, "label manifest")
    if manifest["version"] != 1:
        raise LabelArtifactError("unsupported label manifest version")
    if manifest["labelArtifactSchemaIdentity"] != LABEL_ARTIFACT_SCHEMA_IDENTITY:
        raise LabelArtifactError("unsupported label artifact schema")
    if manifest["labelArtifactIdentitySchema"] != LABEL_ARTIFACT_IDENTITY_SCHEMA:
        raise LabelArtifactError("unsupported label artifact identity schema")
    if manifest["supervisedPolicyTargetContractIdentity"] != SUPERVISED_POLICY_TARGET_IDENTITY:
        raise LabelArtifactError("unsupported supervised target identity")
    if manifest["modelFacingContractIdentity"] != MODEL_FACING_CONTRACT_IDENTITY:
        raise LabelArtifactError("unsupported model-facing contract")
    if manifest["splitContractIdentity"] != SPLIT_CONTRACT_IDENTITY:
        raise LabelArtifactError("unsupported split contract")
    if manifest["sourceDecisionKeyIdentity"] != SOURCE_DECISION_KEY_IDENTITY:
        raise LabelArtifactError("unsupported source decision key")
    for key in (
        "sourceDatasetId",
        "sourceManifestContentDigest",
        "sourceDerivedArtifactId",
        "labelMaterializerConfigDigest",
        "labelsContentDigest",
        "labelArtifactId",
        "manifestContentDigest",
    ):
        _expect_sha(manifest[key], key)
    for key in ("sourceDerivedViewSchemaIdentity", "trajectorySchemaIdentity", "labelsContentReference"):
        _expect_string(manifest[key], key)
    if manifest["labelsContentReference"] != "labels.ndjson":
        raise LabelArtifactError("unsupported label content reference")
    allowed = manifest["allowedPartitions"]
    if allowed != ["TRAIN", "VALIDATION"]:
        raise LabelArtifactError("label artifact allowed partitions are not exact")
    _validate_teacher_provenance(manifest["teacherProvenance"])
    implementation = _expect_object(manifest["labelMaterializerImplementationIdentity"], "materializer identity")
    _expect_keys(implementation, _IMPLEMENTATION_KEYS, "materializer identity")
    _expect_string(implementation["implementation"], "materializer implementation")
    if _GIT_SHA1.fullmatch(_expect_string(implementation["sourceCommit"], "materializer sourceCommit")) is None:
        raise LabelArtifactError("materializer sourceCommit must be a Git SHA-1")
    for key in (
        "sourceRowsByPartition",
        "processedRowsByPartition",
        "teacherCallsByPartition",
        "labelCountsByPartition",
        "rejectedInvalidSourceBindingByPartition",
        "rejectedSplitByPartition",
        "rejectedProvenanceByPartition",
    ):
        _accounting_partition_counts(manifest[key], key)
    _family_counts(manifest["labelCountsByDecisionFamily"])
    _expect_object(manifest["expectedNoLabelByPartitionAndReason"], "expectedNoLabelByPartitionAndReason")
    for key in ("duplicateDecisionKeyCount", "conflictingLabelCount", "otherFailClosedMaterializerErrorCount", "testRowsConsumed", "labelsByteCount", "labelCount"):
        _expect_int(manifest[key], key)
    if sum(manifest["labelCountsByPartition"].values()) != manifest["labelCount"]:
        raise LabelArtifactError("label partition counts do not total labelCount")
    content = dict(manifest)
    content.pop("manifestContentDigest")
    if sha256_hex(canonical_bytes(content)) != manifest["manifestContentDigest"]:
        raise LabelArtifactError("label manifest content digest mismatch")
    if sha256_hex(canonical_bytes(_identity_payload(manifest))) != manifest["labelArtifactId"]:
        raise LabelArtifactError("label artifact identity mismatch")


def _parse_label_line(raw: bytes) -> dict[str, Any]:
    if not raw.endswith(b"\n") or b"\r" in raw or not raw[:-1]:
        raise LabelArtifactError("label line framing is invalid")
    value = _expect_object(_parse_json(raw[:-1], "label line"), "label line")
    if canonical_bytes(value) != raw[:-1]:
        raise LabelArtifactError("label line is not canonical JSON")
    return value


class LabelArtifactReader:
    def __init__(self, root: Path, manifest: dict[str, Any], labels: tuple[dict[str, Any], ...]) -> None:
        self.root = root
        self.manifest = manifest
        self._labels = labels

    @classmethod
    def open(cls, root: Path) -> "LabelArtifactReader":
        root = Path(root)
        manifest_path = root / "manifest.json"
        labels_path = root / "labels.ndjson"
        if not root.is_dir() or manifest_path.is_symlink() or not manifest_path.is_file() or labels_path.is_symlink() or not labels_path.is_file():
            raise LabelArtifactError("label artifact files must be regular files in a directory")
        manifest_raw = manifest_path.read_bytes()
        if b"\n" in manifest_raw or b"\r" in manifest_raw:
            raise LabelArtifactError("manifest.json must not contain a newline")
        manifest = _expect_object(_parse_json(manifest_raw, "manifest.json"), "manifest.json")
        if canonical_bytes(manifest) != manifest_raw:
            raise LabelArtifactError("manifest.json is not canonical UTF-8")
        _validate_manifest(manifest)
        labels_raw = labels_path.read_bytes()
        digest = hashlib.sha256(labels_raw).hexdigest()
        if digest != manifest["labelsContentDigest"] or len(labels_raw) != manifest["labelsByteCount"]:
            raise LabelArtifactError("labels content digest or byte count mismatch")
        labels: list[dict[str, Any]] = []
        keys: set[tuple[str, int, str]] = set()
        for raw in labels_raw.splitlines(keepends=True):
            row = _parse_label_line(raw)
            _validate_row(row, manifest)
            key = _source_key(row)
            if key in keys:
                raise LabelArtifactError("duplicate label source key")
            keys.add(key)
            labels.append(row)
        if len(labels) != manifest["labelCount"]:
            raise LabelArtifactError("label count mismatch")
        return cls(root, manifest, tuple(labels))

    def iter_labels(self) -> Iterable[dict[str, Any]]:
        return iter(self._labels)


def write_label_artifact(
    root: Path,
    *,
    rows: Iterable[dict[str, Any]],
    identity: dict[str, Any],
    accounting: dict[str, Any],
) -> dict[str, Any]:
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    manifest_path = root / "manifest.json"
    labels_path = root / "labels.ndjson"
    if manifest_path.exists() or labels_path.exists():
        raise LabelArtifactError("label artifact output already exists")
    raw_rows = [dict(row) for row in rows]
    if any(row.get("partition") == "TEST" for row in raw_rows):
        raise LabelArtifactError("TEST label is forbidden")
    ordered = sorted(raw_rows, key=_row_sort_key)
    keys: set[tuple[str, int, str]] = set()
    label_lines: list[bytes] = []
    for row in ordered:
        key = _source_key(row)
        if key in keys:
            raise LabelArtifactError("duplicate or conflicting label source key")
        keys.add(key)
        label_lines.append(canonical_bytes(row) + b"\n")
    labels_bytes = b"".join(label_lines)
    manifest = {
        "version": 1,
        "labelArtifactSchemaIdentity": LABEL_ARTIFACT_SCHEMA_IDENTITY,
        "labelArtifactIdentitySchema": LABEL_ARTIFACT_IDENTITY_SCHEMA,
        "labelArtifactId": "0" * 64,
        "supervisedPolicyTargetContractIdentity": SUPERVISED_POLICY_TARGET_IDENTITY,
        **identity,
        "labelsContentReference": "labels.ndjson",
        "labelsContentDigest": hashlib.sha256(labels_bytes).hexdigest(),
        "labelsByteCount": len(labels_bytes),
        "labelCount": len(ordered),
        **accounting,
        "manifestContentDigest": "0" * 64,
    }
    manifest["labelArtifactId"] = sha256_hex(canonical_bytes(_identity_payload(manifest)))
    manifest["manifestContentDigest"] = sha256_hex(canonical_bytes({key: value for key, value in manifest.items() if key != "manifestContentDigest"}))
    _validate_manifest(manifest)
    manifest_bytes = canonical_bytes(manifest)
    staging = Path(tempfile.mkdtemp(prefix=".c1-05-label-", dir=root))
    try:
        (staging / "labels.ndjson").write_bytes(labels_bytes)
        (staging / "manifest.json").write_bytes(manifest_bytes)
        LabelArtifactReader.open(staging)
        os.replace(staging / "labels.ndjson", labels_path)
        os.replace(staging / "manifest.json", manifest_path)
    finally:
        for child in staging.iterdir():
            child.unlink(missing_ok=True)
        staging.rmdir()
    return manifest
