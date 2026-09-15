"""Content-addressed derived-artifact publication contracts.

The local adapter in this module deliberately implements only ``ATOMIC_ONLY``.
It provides a publication seam and crash-safe visibility states, but it never
turns filesystem placement into cross-process trust.  A future immutable-store
adapter must provide the stronger external no-overwrite/version authority before
it may issue :class:`TrustedImmutableHandleV1`.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import time
import uuid
from typing import Any

from ..contracts.canonical_json import canonical_bytes
from .derived_reader import DerivedArtifactReader


_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_GIT_SHA1 = re.compile(r"^[0-9a-f]{40}$")
_RECEIPT_FILENAME = "publication.receipt.json"
_STAGING_DIRNAME = ".staging"
_OBJECTS_DIRNAME = "objects"
_QUARANTINE_DIRNAME = ".quarantine"
_DERIVED_NAMESPACE = "derived"
_VALIDATOR_CONTRACT_IDENTITY = "argentum-ml-derived-reader-full-strict@v1"
_VALIDATOR_IMPLEMENTATION_IDENTITY = "argentum-ml-python-derived-reader@v1"
_VALIDATOR_SOURCE_COMMIT = "03bce91dea0502d74c7af2a1bab5acc2e43f1fa2"
_VALIDATOR_POLICY_DIGEST = hashlib.sha256(
    canonical_bytes(
        {
            "validatorContractIdentity": _VALIDATOR_CONTRACT_IDENTITY,
            "validatorImplementationIdentity": _VALIDATOR_IMPLEMENTATION_IDENTITY,
            "validationEntryPoint": "DerivedArtifactReader.open",
            "validationProfile": "canonical-manifest-and-strict-sample-file-v1",
        }
    )
).hexdigest()
_DUPLICATE_RETRY_WINDOW_SECONDS = 5.0
_DUPLICATE_RETRY_INTERVAL_SECONDS = 0.01


class StoreContractError(ValueError):
    """Raised when a store contract or publication evidence is invalid."""


class ArtifactIdentityMismatch(StoreContractError):
    """Raised when staged bytes do not equal the caller's expected identity."""


class ValidatorBindingMismatch(StoreContractError):
    """Raised when a validator binding is not owned by an approved authority."""


class StoreCapabilityV1(str, Enum):
    ATOMIC_ONLY = "ATOMIC_ONLY"
    IMMUTABLE_CONTENT = "IMMUTABLE_CONTENT"


class PublicationStateV1(str, Enum):
    ATOMIC_ONLY_PUBLISHED = "ATOMIC_ONLY_PUBLISHED"
    IMMUTABLE_PUBLISHED = "IMMUTABLE_PUBLISHED"


class PublicationStatus(str, Enum):
    PUBLISHED = "PUBLISHED"
    ALREADY_PUBLISHED = "ALREADY_PUBLISHED"
    CONFLICT = "CONFLICT"
    RETRYABLE = "RETRYABLE"
    NOT_ELIGIBLE = "NOT_ELIGIBLE"


class OpenExactStatus(str, Enum):
    TRUSTED_IMMUTABLE_HANDLE = "TRUSTED_IMMUTABLE_HANDLE"
    FULL_VALIDATION_REQUIRED = "FULL_VALIDATION_REQUIRED"
    NOT_FOUND = "NOT_FOUND"
    CONFLICT = "CONFLICT"


def _require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise StoreContractError(f"{label} must be a non-empty string")
    return value


def _require_sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        raise StoreContractError(f"{label} must be lowercase SHA-256 hex")
    return value


def _require_commit(value: Any, label: str) -> str:
    if not isinstance(value, str) or _GIT_SHA1.fullmatch(value) is None:
        raise StoreContractError(f"{label} must be lowercase Git SHA-1")
    return value


def _require_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise StoreContractError(f"{label} must be a non-negative integer")
    return value


def _require_exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise StoreContractError(f"{label} fields are not exact")


@dataclass(frozen=True)
class ArtifactIdentityV1:
    """The exact content identity requested by a later consumer."""

    artifact_kind: str
    format_version: int
    derived_artifact_id: str
    manifest_content_digest: str
    samples_content_digest: str
    samples_byte_count: int
    sample_count: int
    derived_view_schema_identity: str
    model_facing_contract_identity: str
    trajectory_schema_identity: str
    split_contract_identity: str
    source_dataset_id: str
    source_manifest_content_digest: str
    materializer_implementation_identity: str
    materializer_source_commit: str
    materializer_config_digest: str

    def __post_init__(self) -> None:
        _require_int(self.format_version, "format_version")
        if self.artifact_kind != "DERIVED_LEARNER_VIEW":
            raise StoreContractError("unsupported artifact kind")
        if self.format_version != 1:
            raise StoreContractError("unsupported artifact identity version")
        for field_name in (
            "derived_artifact_id",
            "manifest_content_digest",
            "samples_content_digest",
            "source_dataset_id",
            "source_manifest_content_digest",
            "materializer_config_digest",
        ):
            _require_sha(getattr(self, field_name), field_name)
        _require_string(self.derived_view_schema_identity, "derived_view_schema_identity")
        _require_string(self.model_facing_contract_identity, "model_facing_contract_identity")
        _require_string(self.trajectory_schema_identity, "trajectory_schema_identity")
        _require_string(self.split_contract_identity, "split_contract_identity")
        _require_string(
            self.materializer_implementation_identity,
            "materializer_implementation_identity",
        )
        _require_commit(self.materializer_source_commit, "materializer_source_commit")
        _require_int(self.samples_byte_count, "samples_byte_count")
        _require_int(self.sample_count, "sample_count")

    @classmethod
    def from_manifest(cls, manifest: Mapping[str, Any]) -> "ArtifactIdentityV1":
        implementation = manifest.get("materializerImplementationIdentity")
        if not isinstance(implementation, Mapping):
            raise StoreContractError("materializer identity is missing")
        return cls(
            artifact_kind="DERIVED_LEARNER_VIEW",
            format_version=manifest["version"],
            derived_artifact_id=manifest["derivedArtifactId"],
            manifest_content_digest=manifest["manifestContentDigest"],
            samples_content_digest=manifest["samplesContentDigest"],
            samples_byte_count=manifest["samplesByteCount"],
            sample_count=manifest["sampleCount"],
            derived_view_schema_identity=manifest["derivedViewSchemaIdentity"],
            model_facing_contract_identity=manifest["modelFacingContractIdentity"],
            trajectory_schema_identity=manifest["trajectorySchemaIdentity"],
            split_contract_identity=manifest["splitContractIdentity"],
            source_dataset_id=manifest["sourceDatasetId"],
            source_manifest_content_digest=manifest["sourceManifestContentDigest"],
            materializer_implementation_identity=implementation["implementation"],
            materializer_source_commit=implementation["sourceCommit"],
            materializer_config_digest=manifest["materializerConfigDigest"],
        )

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ArtifactIdentityV1":
        expected = {
            "artifactKind",
            "formatVersion",
            "derivedArtifactId",
            "manifestContentDigest",
            "samplesContentDigest",
            "samplesByteCount",
            "sampleCount",
            "derivedViewSchemaIdentity",
            "modelFacingContractIdentity",
            "trajectorySchemaIdentity",
            "splitContractIdentity",
            "sourceDatasetId",
            "sourceManifestContentDigest",
            "materializerImplementationIdentity",
            "materializerSourceCommit",
            "materializerConfigDigest",
        }
        _require_exact_keys(value, expected, "artifactIdentity")
        return cls(
            artifact_kind=value["artifactKind"],
            format_version=value["formatVersion"],
            derived_artifact_id=value["derivedArtifactId"],
            manifest_content_digest=value["manifestContentDigest"],
            samples_content_digest=value["samplesContentDigest"],
            samples_byte_count=value["samplesByteCount"],
            sample_count=value["sampleCount"],
            derived_view_schema_identity=value["derivedViewSchemaIdentity"],
            model_facing_contract_identity=value["modelFacingContractIdentity"],
            trajectory_schema_identity=value["trajectorySchemaIdentity"],
            split_contract_identity=value["splitContractIdentity"],
            source_dataset_id=value["sourceDatasetId"],
            source_manifest_content_digest=value["sourceManifestContentDigest"],
            materializer_implementation_identity=value["materializerImplementationIdentity"],
            materializer_source_commit=value["materializerSourceCommit"],
            materializer_config_digest=value["materializerConfigDigest"],
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "artifactKind": self.artifact_kind,
            "formatVersion": self.format_version,
            "derivedArtifactId": self.derived_artifact_id,
            "manifestContentDigest": self.manifest_content_digest,
            "samplesContentDigest": self.samples_content_digest,
            "samplesByteCount": self.samples_byte_count,
            "sampleCount": self.sample_count,
            "derivedViewSchemaIdentity": self.derived_view_schema_identity,
            "modelFacingContractIdentity": self.model_facing_contract_identity,
            "trajectorySchemaIdentity": self.trajectory_schema_identity,
            "splitContractIdentity": self.split_contract_identity,
            "sourceDatasetId": self.source_dataset_id,
            "sourceManifestContentDigest": self.source_manifest_content_digest,
            "materializerImplementationIdentity": self.materializer_implementation_identity,
            "materializerSourceCommit": self.materializer_source_commit,
            "materializerConfigDigest": self.materializer_config_digest,
        }

    @property
    def identity_digest(self) -> str:
        return hashlib.sha256(canonical_bytes(self.to_dict())).hexdigest()


@dataclass(frozen=True)
class ValidatorBindingV1:
    """Versioned identity of the validator that established reusable trust."""

    validator_contract_identity: str
    validator_implementation_identity: str
    validator_source_commit: str
    validator_policy_digest: str
    validator_binding_version: int = 1

    def __post_init__(self) -> None:
        _require_int(self.validator_binding_version, "validator_binding_version")
        if self.validator_binding_version != 1:
            raise StoreContractError("unsupported validator binding version")
        _require_string(self.validator_contract_identity, "validator_contract_identity")
        _require_string(
            self.validator_implementation_identity,
            "validator_implementation_identity",
        )
        _require_commit(self.validator_source_commit, "validator_source_commit")
        _require_sha(self.validator_policy_digest, "validator_policy_digest")

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ValidatorBindingV1":
        expected = {
            "validatorBindingVersion",
            "validatorContractIdentity",
            "validatorImplementationIdentity",
            "validatorSourceCommit",
            "validatorPolicyDigest",
        }
        _require_exact_keys(value, expected, "validatorBinding")
        return cls(
            validator_binding_version=value["validatorBindingVersion"],
            validator_contract_identity=value["validatorContractIdentity"],
            validator_implementation_identity=value["validatorImplementationIdentity"],
            validator_source_commit=value["validatorSourceCommit"],
            validator_policy_digest=value["validatorPolicyDigest"],
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "validatorBindingVersion": self.validator_binding_version,
            "validatorContractIdentity": self.validator_contract_identity,
            "validatorImplementationIdentity": self.validator_implementation_identity,
            "validatorSourceCommit": self.validator_source_commit,
            "validatorPolicyDigest": self.validator_policy_digest,
        }


_VALIDATOR_BINDING = ValidatorBindingV1(
    validator_contract_identity=_VALIDATOR_CONTRACT_IDENTITY,
    validator_implementation_identity=_VALIDATOR_IMPLEMENTATION_IDENTITY,
    validator_source_commit=_VALIDATOR_SOURCE_COMMIT,
    validator_policy_digest=_VALIDATOR_POLICY_DIGEST,
)


class ValidatorAuthorityV1(ABC):
    """Code-owned validator seam whose binding describes its actual execution."""

    __slots__ = ()

    @property
    @abstractmethod
    def binding(self) -> ValidatorBindingV1:
        raise NotImplementedError

    @abstractmethod
    def validate(self, root: Path) -> ArtifactIdentityV1:
        raise NotImplementedError


class DerivedArtifactValidatorAuthorityV1(ValidatorAuthorityV1):
    """The approved full-strict validator used by the local artifact store."""

    __slots__ = ()

    @property
    def binding(self) -> ValidatorBindingV1:
        return _VALIDATOR_BINDING

    def validate(self, root: Path) -> ArtifactIdentityV1:
        reader = DerivedArtifactReader.open(Path(root))
        try:
            return ArtifactIdentityV1.from_manifest(reader.manifest)
        finally:
            reader.close()


@dataclass(frozen=True)
class ProviderIdentityV1:
    """Provider locator/version; deliberately not the content identity."""

    provider_kind: str
    locator: str
    version: str

    def __post_init__(self) -> None:
        _require_string(self.provider_kind, "provider_kind")
        _require_string(self.locator, "locator")
        _require_string(self.version, "version")

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ProviderIdentityV1":
        expected = {"providerKind", "locator", "version"}
        _require_exact_keys(value, expected, "providerIdentity")
        return cls(
            provider_kind=value["providerKind"],
            locator=value["locator"],
            version=value["version"],
        )

    def to_dict(self) -> dict[str, str]:
        return {
            "providerKind": self.provider_kind,
            "locator": self.locator,
            "version": self.version,
        }


@dataclass(frozen=True)
class PublicationReceiptV1:
    """Store-owned publication evidence, including its capability level."""

    artifact_identity: ArtifactIdentityV1
    validator_binding: ValidatorBindingV1
    capability: StoreCapabilityV1
    publication_state: PublicationStateV1
    provider_identity: ProviderIdentityV1
    receipt_version: int = 1

    def __post_init__(self) -> None:
        _require_int(self.receipt_version, "receipt_version")
        if self.receipt_version != 1:
            raise StoreContractError("unsupported publication receipt version")
        if self.capability == StoreCapabilityV1.ATOMIC_ONLY:
            if self.publication_state != PublicationStateV1.ATOMIC_ONLY_PUBLISHED:
                raise StoreContractError("ATOMIC_ONLY receipt has an invalid publication state")
        elif self.capability == StoreCapabilityV1.IMMUTABLE_CONTENT:
            if self.publication_state != PublicationStateV1.IMMUTABLE_PUBLISHED:
                raise StoreContractError("immutable receipt has an invalid publication state")
        else:
            raise StoreContractError("unsupported store capability")

    @classmethod
    def issue(
        cls,
        *,
        artifact_identity: ArtifactIdentityV1,
        validator_binding: ValidatorBindingV1,
        capability: StoreCapabilityV1,
        provider_identity: ProviderIdentityV1,
    ) -> "PublicationReceiptV1":
        state = (
            PublicationStateV1.ATOMIC_ONLY_PUBLISHED
            if capability == StoreCapabilityV1.ATOMIC_ONLY
            else PublicationStateV1.IMMUTABLE_PUBLISHED
        )
        return cls(
            artifact_identity=artifact_identity,
            validator_binding=validator_binding,
            capability=capability,
            publication_state=state,
            provider_identity=provider_identity,
        )

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "PublicationReceiptV1":
        expected = {
            "receiptVersion",
            "artifactIdentity",
            "validatorBinding",
            "storeCapability",
            "publicationState",
            "providerIdentity",
            "receiptId",
        }
        _require_exact_keys(value, expected, "publication receipt")
        try:
            capability = StoreCapabilityV1(value["storeCapability"])
            state = PublicationStateV1(value["publicationState"])
        except (TypeError, ValueError) as exc:
            raise StoreContractError("unknown receipt capability or publication state") from exc
        receipt = cls(
            receipt_version=value["receiptVersion"],
            artifact_identity=ArtifactIdentityV1.from_dict(value["artifactIdentity"]),
            validator_binding=ValidatorBindingV1.from_dict(value["validatorBinding"]),
            capability=capability,
            publication_state=state,
            provider_identity=ProviderIdentityV1.from_dict(value["providerIdentity"]),
        )
        if value["receiptId"] != receipt.receipt_id:
            raise StoreContractError("publication receipt identity mismatch")
        return receipt

    def _payload(self) -> dict[str, Any]:
        return {
            "receiptVersion": self.receipt_version,
            "artifactIdentity": self.artifact_identity.to_dict(),
            "validatorBinding": self.validator_binding.to_dict(),
            "storeCapability": self.capability.value,
            "publicationState": self.publication_state.value,
            "providerIdentity": self.provider_identity.to_dict(),
        }

    @property
    def receipt_id(self) -> str:
        return hashlib.sha256(canonical_bytes(self._payload())).hexdigest()

    def to_dict(self) -> dict[str, Any]:
        return {**self._payload(), "receiptId": self.receipt_id}


@dataclass(frozen=True)
class PublicationResult:
    status: PublicationStatus
    artifact_root: Path | None
    receipt: PublicationReceiptV1 | None
    reason: str | None = None


@dataclass(frozen=True)
class OpenExactResult:
    status: OpenExactStatus
    handle: "TrustedImmutableHandleV1 | None" = None
    reason: str | None = None


class TrustedImmutableHandleV1:
    """Opaque future fast-open authority; only an immutable adapter may issue it."""

    __slots__ = ("_artifact_identity", "_validator_binding", "_artifact_root", "_provider")

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError("trusted handle can only be issued by an immutable store")

    def __setattr__(self, name: str, value: Any) -> None:
        raise AttributeError("trusted immutable handle is read-only")

    @property
    def artifact_identity(self) -> ArtifactIdentityV1:
        return self._artifact_identity

    @property
    def validator_binding(self) -> ValidatorBindingV1:
        return self._validator_binding

    @property
    def artifact_root(self) -> Path:
        return self._artifact_root

    @property
    def provider_identity(self) -> ProviderIdentityV1:
        return self._provider


class ContentAddressedArtifactStore(ABC):
    """Small seam between producer publication and learner consumption."""

    @property
    @abstractmethod
    def capability(self) -> StoreCapabilityV1:
        raise NotImplementedError

    @property
    @abstractmethod
    def trusted_fast_open(self) -> bool:
        raise NotImplementedError

    @abstractmethod
    def create_staging(self) -> Path:
        raise NotImplementedError

    @abstractmethod
    def publish_validated(
        self,
        staging_root: Path,
        *,
        expected_artifact_identity: ArtifactIdentityV1,
    ) -> PublicationResult:
        raise NotImplementedError

    @abstractmethod
    def open_exact(
        self,
        *,
        expected_artifact_identity: ArtifactIdentityV1,
        expected_validator_binding: ValidatorBindingV1,
    ) -> OpenExactResult:
        raise NotImplementedError


class ImmutableContentArtifactStore(ContentAddressedArtifactStore, ABC):
    """Explicit future seam for a provider with trusted immutable authority."""

    @property
    def capability(self) -> StoreCapabilityV1:
        return StoreCapabilityV1.IMMUTABLE_CONTENT

    @property
    @abstractmethod
    def trusted_fast_open(self) -> bool:
        """Return true only when the concrete adapter proves immutable authority."""
        raise NotImplementedError


@dataclass(frozen=True)
class RecoveryRecord:
    original_path: Path
    quarantined_path: Path | None
    reason: str


class AtomicOnlyArtifactStore(ContentAddressedArtifactStore):
    """A local commit-marker store that never grants trusted fast-open authority."""

    def __init__(
        self,
        root: Path | str,
        *,
        validator_authority: ValidatorAuthorityV1 | None = None,
    ) -> None:
        self._root = Path(root)
        if self._root.exists() and (self._root.is_symlink() or not self._root.is_dir()):
            raise StoreContractError("store root must be a real directory")
        self._root.mkdir(parents=True, exist_ok=True)
        self._staging_root = self._root / _STAGING_DIRNAME
        self._objects_root = self._root / _OBJECTS_DIRNAME / _DERIVED_NAMESPACE
        self._quarantine_root = self._root / _QUARANTINE_DIRNAME
        for path in (self._staging_root, self._objects_root, self._quarantine_root):
            if path.exists() and path.is_symlink():
                raise StoreContractError("store internal directories must not be symlinks")
            path.mkdir(parents=True, exist_ok=True)
        authority = validator_authority or DerivedArtifactValidatorAuthorityV1()
        if type(authority) is not DerivedArtifactValidatorAuthorityV1:
            raise StoreContractError(
                "local store requires the approved code-owned derived-artifact validator"
            )
        self._validator_authority = authority

    @property
    def root(self) -> Path:
        return self._root

    @property
    def capability(self) -> StoreCapabilityV1:
        return StoreCapabilityV1.ATOMIC_ONLY

    @property
    def trusted_fast_open(self) -> bool:
        return False

    @property
    def validator_binding(self) -> ValidatorBindingV1:
        return self._validator_authority.binding

    def create_staging(self) -> Path:
        return Path(tempfile.mkdtemp(prefix="artifact-", dir=self._staging_root))

    def object_path(self, artifact_identity: ArtifactIdentityV1) -> Path:
        if not isinstance(artifact_identity, ArtifactIdentityV1):
            raise StoreContractError("object path requires a typed artifact identity")
        return self._objects_root / artifact_identity.derived_artifact_id

    def publish_validated(
        self,
        staging_root: Path,
        *,
        expected_artifact_identity: ArtifactIdentityV1,
    ) -> PublicationResult:
        if not isinstance(expected_artifact_identity, ArtifactIdentityV1):
            raise StoreContractError("publication requires a typed expected artifact identity")
        staging = self._require_staging(staging_root)
        actual_identity = self._validate_artifact(staging)
        if actual_identity != expected_artifact_identity:
            raise ArtifactIdentityMismatch(
                "staged artifact does not equal the expected content identity"
            )

        final_root = self.object_path(actual_identity)
        receipt = PublicationReceiptV1.issue(
            artifact_identity=actual_identity,
            validator_binding=self.validator_binding,
            capability=self.capability,
            provider_identity=self._provider_identity(actual_identity),
        )
        if final_root.exists() or final_root.is_symlink():
            result = self._compare_existing(final_root, receipt)
            self._dispose_staging(staging, "duplicate-or-conflicting-staging")
            return result

        try:
            # Directory creation is the no-overwrite claim. Payload files become visible
            # only inside an object with no commit marker; open_exact rejects that state.
            final_root.mkdir(parents=False)
            for filename in ("manifest.json", "samples.ndjson"):
                source = staging / filename
                if source.is_symlink() or not source.is_file():
                    raise StoreContractError(f"staged {filename} is not a regular file")
                os.replace(source, final_root / filename)
            receipt_path = final_root / _RECEIPT_FILENAME
            temporary_receipt = final_root / f".{_RECEIPT_FILENAME}.{uuid.uuid4().hex}.tmp"
            fd = os.open(
                temporary_receipt,
                os.O_CREAT | os.O_EXCL | os.O_WRONLY,
                0o600,
            )
            try:
                payload = canonical_bytes(receipt.to_dict())
                os.write(fd, payload)
                os.fsync(fd)
            finally:
                os.close(fd)
            os.replace(temporary_receipt, receipt_path)
            self._dispose_staging(staging, "published-staging")
            return PublicationResult(PublicationStatus.PUBLISHED, final_root, receipt)
        except FileExistsError:
            result = self._compare_existing_after_race(final_root, receipt)
            self._dispose_staging(staging, "publication-race")
            return result
        except Exception:
            if final_root.exists() or final_root.is_symlink():
                self._quarantine(final_root, "publication-failure")
            self._dispose_staging(staging, "publication-failure-staging")
            raise

    def open_exact(
        self,
        *,
        expected_artifact_identity: ArtifactIdentityV1,
        expected_validator_binding: ValidatorBindingV1,
    ) -> OpenExactResult:
        if not isinstance(expected_artifact_identity, ArtifactIdentityV1):
            raise StoreContractError("exact open requires a typed expected artifact identity")
        if not isinstance(expected_validator_binding, ValidatorBindingV1):
            raise StoreContractError("exact open requires a typed expected validator binding")
        if expected_validator_binding != self.validator_binding:
            return OpenExactResult(
                OpenExactStatus.CONFLICT,
                reason="expected validator binding is not owned by this store's validator authority",
            )
        final_root = self.object_path(expected_artifact_identity)
        if final_root.is_symlink() or not final_root.is_dir():
            return OpenExactResult(OpenExactStatus.NOT_FOUND, reason="published object is absent")
        receipt_path = final_root / _RECEIPT_FILENAME
        if receipt_path.is_symlink() or not receipt_path.is_file():
            return OpenExactResult(
                OpenExactStatus.FULL_VALIDATION_REQUIRED,
                reason="publication commit marker is missing",
            )
        try:
            receipt = self._read_receipt(receipt_path)
        except StoreContractError as exc:
            return OpenExactResult(OpenExactStatus.CONFLICT, reason=str(exc))
        if receipt.artifact_identity != expected_artifact_identity:
            return OpenExactResult(OpenExactStatus.CONFLICT, reason="artifact identity differs")
        if receipt.validator_binding != expected_validator_binding:
            return OpenExactResult(OpenExactStatus.CONFLICT, reason="validator binding differs")
        if receipt.validator_binding != self.validator_binding:
            return OpenExactResult(
                OpenExactStatus.CONFLICT,
                reason="receipt validator binding is not owned by this store's validator authority",
            )
        if receipt.provider_identity != self._provider_identity(expected_artifact_identity):
            return OpenExactResult(OpenExactStatus.CONFLICT, reason="provider locator differs")
        if receipt.capability != StoreCapabilityV1.ATOMIC_ONLY:
            return OpenExactResult(
                OpenExactStatus.CONFLICT,
                reason="ATOMIC_ONLY adapter cannot honor an immutable capability claim",
            )
        return OpenExactResult(
            OpenExactStatus.FULL_VALIDATION_REQUIRED,
            reason="ATOMIC_ONLY publication has no trusted immutable authority",
        )

    def recover(self) -> tuple[RecoveryRecord, ...]:
        records: list[RecoveryRecord] = []
        for staging in tuple(self._staging_root.iterdir()):
            destination = self._quarantine(staging, "abandoned-staging")
            records.append(RecoveryRecord(staging, destination, "ABANDONED_STAGING"))
        for published in tuple(self._objects_root.iterdir()):
            if published.is_symlink() or not published.is_dir():
                destination = self._quarantine(published, "invalid-published-root")
                records.append(RecoveryRecord(published, destination, "INVALID_PUBLISHED_ROOT"))
                continue
            receipt_path = published / _RECEIPT_FILENAME
            try:
                receipt = self._read_receipt(receipt_path)
                actual_identity = self._validate_artifact(published)
                if (
                    receipt.capability != self.capability
                    or receipt.artifact_identity != actual_identity
                    or receipt.validator_binding != self.validator_binding
                    or receipt.provider_identity != self._provider_identity(actual_identity)
                ):
                    raise StoreContractError("published receipt does not match its object")
            except Exception:
                destination = self._quarantine(published, "invalid-published-object")
                records.append(RecoveryRecord(published, destination, "INVALID_PUBLISHED_OBJECT"))
        return tuple(records)

    def _require_staging(self, staging_root: Path) -> Path:
        staging = Path(staging_root)
        if staging.is_symlink() or not staging.is_dir():
            raise StoreContractError("staging root must be a real directory")
        try:
            staging.resolve().relative_to(self._staging_root.resolve())
        except ValueError as exc:
            raise StoreContractError("staging root is outside the store staging namespace") from exc
        return staging

    def _validate_artifact(self, root: Path) -> ArtifactIdentityV1:
        return self._validator_authority.validate(root)

    def _provider_identity(self, identity: ArtifactIdentityV1) -> ProviderIdentityV1:
        locator = self.object_path(identity).relative_to(self._root).as_posix()
        return ProviderIdentityV1(
            provider_kind="local-filesystem",
            locator=locator,
            version="atomic-only-commit-marker-v1",
        )

    def _read_receipt(self, path: Path) -> PublicationReceiptV1:
        if path.is_symlink() or not path.is_file():
            raise StoreContractError("publication commit marker is missing")
        try:
            raw = path.read_bytes()
            value = json.loads(raw.decode("utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise StoreContractError("publication commit marker is malformed") from exc
        if not isinstance(value, Mapping):
            raise StoreContractError("publication commit marker must be an object")
        try:
            canonical = canonical_bytes(value)
        except (TypeError, ValueError, UnicodeError) as exc:
            raise StoreContractError("publication commit marker is not canonical JSON") from exc
        if canonical != raw:
            raise StoreContractError("publication commit marker is not canonical JSON")
        return PublicationReceiptV1.from_dict(value)

    def _compare_existing_after_race(
        self,
        final_root: Path,
        expected_receipt: PublicationReceiptV1,
    ) -> PublicationResult:
        deadline = time.monotonic() + _DUPLICATE_RETRY_WINDOW_SECONDS
        while True:
            result = self._compare_existing(final_root, expected_receipt)
            if result.status != PublicationStatus.RETRYABLE:
                return result
            if time.monotonic() >= deadline:
                return result
            time.sleep(_DUPLICATE_RETRY_INTERVAL_SECONDS)

    def _compare_existing(
        self,
        final_root: Path,
        expected_receipt: PublicationReceiptV1,
    ) -> PublicationResult:
        if final_root.is_symlink() or not final_root.is_dir():
            return PublicationResult(
                PublicationStatus.CONFLICT,
                final_root if final_root.exists() else None,
                None,
                "existing publication root is not a regular directory",
            )
        receipt_path = final_root / _RECEIPT_FILENAME
        if receipt_path.is_symlink() or not receipt_path.is_file():
            return PublicationResult(
                PublicationStatus.RETRYABLE,
                final_root,
                None,
                "existing publication is in-flight or lacks a commit marker; retry",
            )
        try:
            existing_receipt = self._read_receipt(receipt_path)
            existing_identity = self._validate_artifact(final_root)
            if (
                existing_identity != expected_receipt.artifact_identity
                or existing_receipt != expected_receipt
            ):
                return PublicationResult(
                    PublicationStatus.CONFLICT,
                    final_root,
                    None,
                    "existing object does not match the requested publication",
                )
            return PublicationResult(
                PublicationStatus.ALREADY_PUBLISHED,
                final_root,
                existing_receipt,
            )
        except Exception as exc:
            return PublicationResult(
                PublicationStatus.CONFLICT,
                final_root,
                None,
                f"existing object or receipt is invalid: {exc}",
            )

    def _dispose_staging(self, staging: Path, reason: str) -> None:
        if not staging.exists() and not staging.is_symlink():
            return
        leftovers = tuple(staging.iterdir()) if staging.is_dir() and not staging.is_symlink() else ()
        for child in leftovers:
            self._quarantine(child, reason)
        if staging.is_dir() and not staging.is_symlink():
            staging.rmdir()
        elif staging.exists() or staging.is_symlink():
            self._quarantine(staging, reason)

    def _quarantine(self, path: Path, reason: str) -> Path:
        destination = self._quarantine_root / f"{reason.lower()}-{uuid.uuid4().hex}-{path.name}"
        path.rename(destination)
        return destination


__all__ = [
    "ArtifactIdentityMismatch",
    "ArtifactIdentityV1",
    "AtomicOnlyArtifactStore",
    "ContentAddressedArtifactStore",
    "ImmutableContentArtifactStore",
    "OpenExactResult",
    "OpenExactStatus",
    "PublicationReceiptV1",
    "PublicationResult",
    "PublicationStateV1",
    "PublicationStatus",
    "ProviderIdentityV1",
    "RecoveryRecord",
    "StoreCapabilityV1",
    "StoreContractError",
    "TrustedImmutableHandleV1",
    "ValidatorAuthorityV1",
    "DerivedArtifactValidatorAuthorityV1",
    "ValidatorBindingMismatch",
    "ValidatorBindingV1",
]
