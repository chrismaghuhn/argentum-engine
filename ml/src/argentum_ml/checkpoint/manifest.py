"""Strict C0-04 checkpoint manifest and identity validation."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..contracts.canonical_json import canonical_bytes, canonical_json
from ..contracts.identities import (
    CHECKPOINT_MANIFEST_IDENTITY,
    INFERENCE_CONTRACT_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    NUMERIC_PROFILE_CONTRACT_IDENTITY,
    POLICY_TIE_RNG_IDENTITY,
    SELECTION_V1_IDENTITY,
    SELECTION_V2_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GIT_SHA1 = re.compile(r"^[0-9a-f]{40}$")
_MANIFEST_KEYS = {
    "version",
    "manifestContractIdentity",
    "policyArtifactKind",
    "modelImplementationIdentity",
    "modelArchitectureIdentity",
    "modelConfigDigest",
    "modelFacingContractIdentity",
    "candidateScoringContractIdentity",
    "splitContractIdentity",
    "sourceDatasetIdentity",
    "recurrentSequenceContractIdentity",
    "vocabularyIdentity",
    "weightArtifactIdentity",
    "weightContentDigest",
    "inferenceContractIdentity",
    "selectionContractIdentity",
    "requiredNumericProfileClass",
    "policyRngContractIdentity",
    "trainingRecipeIdentity",
    "trainingRunIdentity",
    "parentCheckpointIdentity",
    "teacherBootstrapProvenance",
    "checkpointId",
}
_ARTIFACT_KINDS = {"FEED_FORWARD_POLICY", "RECURRENT_POLICY"}
_NONE_FOR_FEED_FORWARD = "NONE_FOR_FEED_FORWARD"
_RECURRENT_SEQUENCE = "argentum-ml-recurrent-sequence@v1"
_NONE_FOR_DETERMINISTIC_MODE = "NONE_FOR_DETERMINISTIC_MODE"
_CHECKPOINT_IDENTITY_SCHEMA = "argentum-ml-checkpoint-id@v1"


class CheckpointManifestError(ValueError):
    """Raised when a checkpoint manifest or weight artifact is invalid."""


class _FrozenDict(dict[str, Any]):
    """Dict-compatible immutable JSON object retained by a validated manifest."""

    def __init__(self, values: dict[str, Any]) -> None:
        dict.__init__(self, values)

    @staticmethod
    def _immutable(*args: Any, **kwargs: Any) -> None:
        raise TypeError("checkpoint manifest JSON is immutable")

    __setitem__ = __delitem__ = clear = pop = popitem = setdefault = update = _immutable

    def __ior__(self, other: Any) -> "_FrozenDict":
        self._immutable()
        return self


class _FrozenList(list[Any]):
    """List-compatible immutable JSON array retained by a validated manifest."""

    def __init__(self, values: list[Any]) -> None:
        list.__init__(self, values)

    @staticmethod
    def _immutable(*args: Any, **kwargs: Any) -> None:
        raise TypeError("checkpoint manifest JSON is immutable")

    __setitem__ = __delitem__ = append = clear = extend = insert = pop = remove = reverse = sort = _immutable

    def __iadd__(self, other: Any) -> "_FrozenList":
        self._immutable()
        return self

    def __imul__(self, other: Any) -> "_FrozenList":
        self._immutable()
        return self


def _deep_freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return _FrozenDict({key: _deep_freeze(child) for key, child in value.items()})
    if isinstance(value, list):
        return _FrozenList([_deep_freeze(child) for child in value])
    if isinstance(value, tuple):
        return tuple(_deep_freeze(child) for child in value)
    return value


def _deep_thaw(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: _deep_thaw(child) for key, child in value.items()}
    if isinstance(value, list):
        return [_deep_thaw(child) for child in value]
    if isinstance(value, tuple):
        return tuple(_deep_thaw(child) for child in value)
    return value


@dataclass(frozen=True)
class NumericExecutionProfileIdentity:
    contract_identity: str
    required_profile_class: str

    def __post_init__(self) -> None:
        if self.contract_identity != NUMERIC_PROFILE_CONTRACT_IDENTITY:
            raise CheckpointManifestError("unsupported numeric execution profile identity")
        if not isinstance(self.required_profile_class, str) or not self.required_profile_class:
            raise CheckpointManifestError("numeric execution profile class must be non-empty")


@dataclass(frozen=True)
class ArgentumCheckpointManifestV1:
    _data: dict[str, Any] = field(repr=False)

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "ArgentumCheckpointManifestV1":
        if not isinstance(value, dict):
            raise CheckpointManifestError("checkpoint manifest must be an object")
        _validate_manifest(value)
        return cls(_deep_freeze(value))

    @classmethod
    def from_json(cls, raw: str | bytes) -> "ArgentumCheckpointManifestV1":
        if isinstance(raw, str):
            raw_bytes = raw.encode("utf-8")
        elif isinstance(raw, bytes):
            raw_bytes = raw
        else:
            raise CheckpointManifestError("manifest JSON must be text or bytes")
        try:
            text = raw_bytes.decode("utf-8")
            value = json.loads(text, object_pairs_hook=_reject_duplicate_pairs)
        except (UnicodeDecodeError, json.JSONDecodeError, CheckpointManifestError) as exc:
            raise CheckpointManifestError("malformed checkpoint manifest JSON") from exc
        if not isinstance(value, dict):
            raise CheckpointManifestError("checkpoint manifest JSON must be an object")
        return cls.from_dict(value)

    @classmethod
    def from_path(cls, path: Path) -> "ArgentumCheckpointManifestV1":
        path = Path(path)
        if path.is_symlink() or not path.is_file():
            raise CheckpointManifestError("checkpoint manifest path must be a regular file")
        return cls.from_json(path.read_bytes())

    @property
    def checkpoint_id(self) -> str:
        return self._data["checkpointId"]

    @property
    def weight_content_digest(self) -> str:
        return self._data["weightContentDigest"]

    @property
    def required_numeric_profile_class(self) -> str:
        return self._data["requiredNumericProfileClass"]

    def to_dict(self) -> dict[str, Any]:
        return _deep_thaw(self._data)

    def canonical_json(self) -> str:
        return canonical_json(self._data)

    def recompute_checkpoint_id(self, manifest: dict[str, Any] | None = None) -> str:
        source = self._data if manifest is None else manifest
        return hashlib.sha256(canonical_bytes(_identity_payload(source))).hexdigest()

    def validate_weight_bytes(self, weight_bytes: bytes) -> bool:
        if not isinstance(weight_bytes, bytes):
            raise CheckpointManifestError("weight bytes must be bytes")
        digest = hashlib.sha256(weight_bytes).hexdigest()
        if digest != self.weight_content_digest:
            raise CheckpointManifestError("weight content digest mismatch")
        return True

    def validate_weight_file(self, path: Path) -> bool:
        path = Path(path)
        if path.is_symlink() or not path.is_file():
            raise CheckpointManifestError("weight path must be a regular file")
        return self.validate_weight_bytes(path.read_bytes())


CheckpointManifestV1 = ArgentumCheckpointManifestV1


def _reject_duplicate_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CheckpointManifestError(f"duplicate checkpoint manifest key: {key}")
        result[key] = value
    return result


def _validate_manifest(value: dict[str, Any]) -> None:
    if set(value) != _MANIFEST_KEYS:
        raise CheckpointManifestError("checkpoint manifest fields are not exact")
    if type(value["version"]) is not int or value["version"] != 1:
        raise CheckpointManifestError("unsupported checkpoint manifest version")
    if value["manifestContractIdentity"] != CHECKPOINT_MANIFEST_IDENTITY:
        raise CheckpointManifestError("unsupported checkpoint manifest identity")
    kind = _string(value["policyArtifactKind"], "policyArtifactKind")
    if kind not in _ARTIFACT_KINDS:
        raise CheckpointManifestError("unsupported checkpoint artifact kind")
    implementation = _object(value["modelImplementationIdentity"], "modelImplementationIdentity")
    _exact_keys(implementation, {"implementation", "sourceCommit"}, "modelImplementationIdentity")
    if not _string(implementation["implementation"], "implementation"):
        raise CheckpointManifestError("model implementation identity must be non-empty")
    if _GIT_SHA1.fullmatch(_string(implementation["sourceCommit"], "sourceCommit")) is None:
        raise CheckpointManifestError("sourceCommit must be lowercase Git SHA-1")
    for key in ("modelArchitectureIdentity", "modelFacingContractIdentity", "candidateScoringContractIdentity", "splitContractIdentity", "inferenceContractIdentity", "requiredNumericProfileClass"):
        if not _string(value[key], key):
            raise CheckpointManifestError(f"{key} must be non-empty")
    if value["modelFacingContractIdentity"] != MODEL_FACING_CONTRACT_IDENTITY:
        raise CheckpointManifestError("unsupported model-facing contract identity")
    if value["candidateScoringContractIdentity"] != MODEL_FACING_CONTRACT_IDENTITY:
        raise CheckpointManifestError("unsupported candidate-scoring contract identity")
    if value["splitContractIdentity"] != SPLIT_CONTRACT_IDENTITY:
        raise CheckpointManifestError("unsupported split contract identity")
    for key in ("modelConfigDigest", "sourceDatasetIdentity", "weightContentDigest", "checkpointId"):
        if _SHA256.fullmatch(_string(value[key], key)) is None:
            raise CheckpointManifestError(f"{key} must be lowercase SHA-256 hex")
    if value["inferenceContractIdentity"] != INFERENCE_CONTRACT_IDENTITY:
        raise CheckpointManifestError("unsupported inference contract identity")
    recurrent = _string(value["recurrentSequenceContractIdentity"], "recurrentSequenceContractIdentity")
    if kind == "FEED_FORWARD_POLICY" and recurrent != _NONE_FOR_FEED_FORWARD:
        raise CheckpointManifestError("feed-forward artifact has an invalid recurrent contract")
    if kind == "RECURRENT_POLICY" and recurrent != _RECURRENT_SEQUENCE:
        raise CheckpointManifestError("recurrent artifact has an invalid recurrent contract")
    _optional_identity(value["vocabularyIdentity"], "vocabularyIdentity")
    weight_artifact = _object(value["weightArtifactIdentity"], "weightArtifactIdentity")
    _exact_keys(weight_artifact, {"container", "artifact"}, "weightArtifactIdentity")
    if not _string(weight_artifact["container"], "weightArtifactIdentity.container"):
        raise CheckpointManifestError("weight artifact container identity must be non-empty")
    if not _string(weight_artifact["artifact"], "weightArtifactIdentity.artifact"):
        raise CheckpointManifestError("weight artifact identity must be non-empty")
    selection = _string(value["selectionContractIdentity"], "selectionContractIdentity")
    policy_rng = _string(value["policyRngContractIdentity"], "policyRngContractIdentity")
    if selection == SELECTION_V1_IDENTITY and policy_rng == _NONE_FOR_DETERMINISTIC_MODE:
        pass
    elif selection == SELECTION_V2_IDENTITY and policy_rng == POLICY_TIE_RNG_IDENTITY:
        pass
    else:
        raise CheckpointManifestError("selection and policy RNG contracts are incompatible")
    for key in ("trainingRecipeIdentity", "trainingRunIdentity"):
        _optional_identity(value[key], key)
    _optional_sha256_identity(value["parentCheckpointIdentity"], "parentCheckpointIdentity")
    if value["teacherBootstrapProvenance"] is not None:
        raise CheckpointManifestError("teacherBootstrapProvenance is reserved and must be null")
    if hashlib.sha256(canonical_bytes(_identity_payload(value))).hexdigest() != value["checkpointId"]:
        raise CheckpointManifestError("checkpointId does not match its identity payload")


def _identity_payload(value: dict[str, Any]) -> dict[str, Any]:
    payload = {
        key: value[key]
        for key in _MANIFEST_KEYS
        if key != "checkpointId" and key != "version"
    }
    payload["schema"] = _CHECKPOINT_IDENTITY_SCHEMA
    return payload


def _object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise CheckpointManifestError(f"{label} must be an object")
    return value


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise CheckpointManifestError(f"{label} fields are not exact")


def _string(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise CheckpointManifestError(f"{label} must be a string")
    return value


def _optional_identity(value: Any, label: str) -> None:
    if value is not None and (not isinstance(value, str) or not value):
        raise CheckpointManifestError(f"{label} must be a non-empty string or null")


def _optional_sha256_identity(value: Any, label: str) -> None:
    if value is not None and (
        not isinstance(value, str) or _SHA256.fullmatch(value) is None
    ):
        raise CheckpointManifestError(f"{label} must be a lowercase SHA-256 hex or null")
