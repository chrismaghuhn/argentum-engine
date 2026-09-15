"""Server-owned C1_07B profile and strict checkpoint-artifact authority."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from ..checkpoint.manifest import ArgentumCheckpointManifestV1, CheckpointManifestError
from ..contracts.canonical_json import canonical_bytes
from ..contracts.identities import (
    INFERENCE_CONTRACT_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    POLICY_TIE_RNG_IDENTITY,
    SELECTION_V2_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)
from ..learner.c1_06 import (
    MODEL_ARCHITECTURE_IDENTITY,
    MODEL_IMPLEMENTATION_IDENTITY,
    TRAINING_RECIPE_IDENTITY,
    C1_06ModelConfigV1,
)
from ..learner.weights import SAFETENSORS_CONTAINER_IDENTITY
from .errors import LivePolicyCheckpointError
from .numeric_profile import C1_REFERENCE_NUMERIC_PROFILE, C1ReferenceNumericExecutionProfileV1


LIVE_PROTOCOL_VERSION = 1
LIVE_DECISION_REQUEST_SCHEMA_IDENTITY = "argentum-ml-live-policy-decision-request@v1"
LIVE_DECISION_RESPONSE_SCHEMA_IDENTITY = "argentum-ml-live-policy-decision-response@v1"
LIVE_PROFILE_IDENTITY = "argentum-mtg-ml-akiri-vs-engine-chevill@v1"
LIVE_SELECTION_ADDRESS_CONTRACT_IDENTITY = "argentum-ml-live-selection-address@v1"
LIVE_HEALTH_MESSAGE_TYPE = "HEALTH"
LIVE_REQUEST_MESSAGE_TYPE = "REQUEST"
LIVE_RESPONSE_MESSAGE_TYPE = "RESPONSE"
LIVE_ERROR_MESSAGE_TYPE = "ERROR"
LIVE_SHUTDOWN_MESSAGE_TYPE = "SHUTDOWN"
LIVE_SHUTDOWN_ACK_MESSAGE_TYPE = "SHUTDOWN_ACK"


@dataclass(frozen=True)
class ValidatedCheckpointArtifact:
    """The exact validated checkpoint snapshot used for model loading."""

    root: Path
    manifest_path: Path
    weight_path: Path
    manifest: ArgentumCheckpointManifestV1
    manifest_bytes: bytes
    weight_bytes: bytes


@dataclass(frozen=True, init=False)
class C1_07BPolicyProfile:
    """Immutable server-owned identity for the one accepted C1_06 policy checkpoint."""

    profile_identity: str
    expected_checkpoint_id: str
    expected_manifest_content_digest: str
    expected_weight_content_digest: str
    expected_model_architecture_identity: str
    expected_model_config_digest: str
    expected_model_implementation_identity: str
    expected_source_commit: str
    expected_source_dataset_identity: str
    expected_label_artifact_id: str
    expected_labels_content_digest: str
    expected_source_manifest_content_digest: str
    expected_training_recipe_identity: str
    expected_training_run_identity: str
    expected_inference_contract_identity: str
    expected_selection_contract_identity: str
    expected_selection_address_contract_identity: str
    expected_policy_rng_contract_identity: str
    expected_numeric_profile_class: str

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        raise TypeError("C1_07BPolicyProfile is server-owned; use reference()")

    @classmethod
    def reference(cls) -> "C1_07BPolicyProfile":
        values = {
            "profile_identity": LIVE_PROFILE_IDENTITY,
            "expected_checkpoint_id": "f4b191d99734af66a5643c988ce3c2f2198a2957be24e2a717287006a43124c5",
            "expected_manifest_content_digest": "973231cc16f8de28f618889b94cabe6ac12da67485a246207db16c8c902ed9a3",
            "expected_weight_content_digest": "02027b495f609a268b2d6d169250a66cdaab5f8658c4794d1e2669b484ea0168",
            "expected_model_architecture_identity": MODEL_ARCHITECTURE_IDENTITY,
            "expected_model_config_digest": "542b74694061b07c8adc397e27ea3a57b71edaaa33af24b05b99099d95d3c966",
            "expected_model_implementation_identity": MODEL_IMPLEMENTATION_IDENTITY,
            "expected_source_commit": "943338abbaf47f289cfe606acd50caf0a2b15ef5",
            "expected_source_dataset_identity": "be545edcb7809a34d78ab9be03476b3cd29ab42e54e6c7f7e20b25b5bcc05a4a",
            "expected_label_artifact_id": "22c6d18fa05301010b7057a4f524ef689a89626aa7dfb7582d73572a4f0f1c52",
            "expected_labels_content_digest": "5f683753dd01f8a7e20c6b6b2ef31c38e6bb4949cac116a49c7ccfbb577f3a24",
            "expected_source_manifest_content_digest": "8bbd0060ca18108eb48ca3954e913743cc27fdd062ba78d4228db76a0b448d2d",
            "expected_training_recipe_identity": TRAINING_RECIPE_IDENTITY,
            "expected_training_run_identity": "e1ef9daddcfb4d2444c800a3be2674123962f6be068feec6d6af6f1a9ba6dd64",
            "expected_inference_contract_identity": INFERENCE_CONTRACT_IDENTITY,
            "expected_selection_contract_identity": SELECTION_V2_IDENTITY,
            "expected_selection_address_contract_identity": LIVE_SELECTION_ADDRESS_CONTRACT_IDENTITY,
            "expected_policy_rng_contract_identity": POLICY_TIE_RNG_IDENTITY,
            "expected_numeric_profile_class": "C1_REFERENCE_NUMERIC_PROFILE",
        }
        instance = object.__new__(cls)
        for key, value in values.items():
            object.__setattr__(instance, key, value)
        return instance

    @property
    def model_config(self) -> C1_06ModelConfigV1:
        config = C1_06ModelConfigV1.reference()
        if (
            config.architecture_identity != self.expected_model_architecture_identity
            or config.digest != self.expected_model_config_digest
        ):
            raise LivePolicyCheckpointError(
                "C1_06 reference model config drifted from the server-owned profile",
                code="PROFILE_MODEL_CONFIG_MISMATCH",
            )
        return config

    def envelope_fields(self) -> dict[str, Any]:
        return {
            "profileIdentity": self.profile_identity,
            "checkpointId": self.expected_checkpoint_id,
            "modelArchitectureIdentity": self.expected_model_architecture_identity,
            "modelConfigDigest": self.expected_model_config_digest,
            "inferenceContractIdentity": self.expected_inference_contract_identity,
            "selectionContractIdentity": self.expected_selection_contract_identity,
            "selectionAddressContractIdentity": self.expected_selection_address_contract_identity,
            "policyRngContractIdentity": self.expected_policy_rng_contract_identity,
            "numericProfileClass": self.expected_numeric_profile_class,
        }

    @property
    def numeric_execution_profile(self) -> C1ReferenceNumericExecutionProfileV1:
        return C1_REFERENCE_NUMERIC_PROFILE

    def health_envelope(self) -> dict[str, Any]:
        return {
            "protocolVersion": LIVE_PROTOCOL_VERSION,
            "messageType": LIVE_HEALTH_MESSAGE_TYPE,
            "status": "READY",
            **self.envelope_fields(),
        }

    def response_envelope(
        self,
        *,
        request_id: str,
        response: Mapping[str, Any],
        scored_candidate_count: int,
    ) -> dict[str, Any]:
        return {
            "protocolVersion": LIVE_PROTOCOL_VERSION,
            "messageType": LIVE_RESPONSE_MESSAGE_TYPE,
            **self.envelope_fields(),
            "requestId": request_id,
            "response": dict(response),
            "scoredCandidateCount": scored_candidate_count,
        }

    def shutdown_envelope(self) -> dict[str, Any]:
        return {
            "protocolVersion": LIVE_PROTOCOL_VERSION,
            "messageType": LIVE_SHUTDOWN_MESSAGE_TYPE,
            **self.envelope_fields(),
        }

    def shutdown_ack_envelope(self) -> dict[str, Any]:
        return {
            "protocolVersion": LIVE_PROTOCOL_VERSION,
            "messageType": LIVE_SHUTDOWN_ACK_MESSAGE_TYPE,
            **self.envelope_fields(),
        }

    def error_envelope(
        self,
        *,
        code: str,
        phase: str,
        message: str,
        request_id: str | None = None,
    ) -> dict[str, Any]:
        envelope: dict[str, Any] = {
            "protocolVersion": LIVE_PROTOCOL_VERSION,
            "messageType": LIVE_ERROR_MESSAGE_TYPE,
            **self.envelope_fields(),
            "errorCode": code,
            "phase": phase,
            "message": message,
        }
        if request_id is not None:
            envelope["requestId"] = request_id
        return envelope

    def validate_checkpoint_artifact(
        self,
        checkpoint_dir: Path | str,
    ) -> ValidatedCheckpointArtifact:
        root = _resolve_regular_directory(checkpoint_dir)
        manifest_path = root / "manifest.json"
        weight_path = root / "weights.safetensors"
        _require_regular_file(manifest_path, "checkpoint manifest")
        _require_regular_file(weight_path, "checkpoint weights")
        try:
            raw_manifest = manifest_path.read_bytes()
        except OSError as exc:
            raise LivePolicyCheckpointError(
                "checkpoint manifest could not be read",
                code="CHECKPOINT_MANIFEST_READ_FAILURE",
            ) from exc
        manifest_digest = hashlib.sha256(raw_manifest).hexdigest()
        if manifest_digest != self.expected_manifest_content_digest:
            raise LivePolicyCheckpointError(
                "checkpoint manifest content digest differs from the fixed profile",
                code="CHECKPOINT_MANIFEST_DIGEST_MISMATCH",
            )
        try:
            manifest = ArgentumCheckpointManifestV1.from_json(raw_manifest)
        except CheckpointManifestError as exc:
            raise LivePolicyCheckpointError(
                "checkpoint manifest is not a valid canonical C1 manifest",
                code="CHECKPOINT_MANIFEST_INVALID",
            ) from exc
        if raw_manifest != canonical_bytes(manifest.to_dict()):
            raise LivePolicyCheckpointError(
                "checkpoint manifest bytes are not canonical",
                code="CHECKPOINT_MANIFEST_NONCANONICAL",
            )
        self.require_manifest_identity(manifest)
        try:
            raw_weights = weight_path.read_bytes()
            self._require_weight_digest(raw_weights, manifest)
        except OSError as exc:
            raise LivePolicyCheckpointError(
                "checkpoint weights could not be read",
                code="CHECKPOINT_WEIGHT_READ_FAILURE",
            ) from exc
        return ValidatedCheckpointArtifact(
            root,
            manifest_path,
            weight_path,
            manifest,
            raw_manifest,
            raw_weights,
        )

    def require_manifest_identity(self, manifest: ArgentumCheckpointManifestV1) -> None:
        if not isinstance(manifest, ArgentumCheckpointManifestV1):
            raise LivePolicyCheckpointError(
                "checkpoint identity requires a validated manifest",
                code="CHECKPOINT_MANIFEST_INVALID",
            )
        data = manifest.to_dict()
        expected: dict[str, Any] = {
            "checkpointId": self.expected_checkpoint_id,
            "modelArchitectureIdentity": self.expected_model_architecture_identity,
            "modelConfigDigest": self.expected_model_config_digest,
            "modelFacingContractIdentity": MODEL_FACING_CONTRACT_IDENTITY,
            "candidateScoringContractIdentity": MODEL_FACING_CONTRACT_IDENTITY,
            "splitContractIdentity": SPLIT_CONTRACT_IDENTITY,
            "sourceDatasetIdentity": self.expected_source_dataset_identity,
            "recurrentSequenceContractIdentity": "NONE_FOR_FEED_FORWARD",
            "inferenceContractIdentity": self.expected_inference_contract_identity,
            "selectionContractIdentity": self.expected_selection_contract_identity,
            "requiredNumericProfileClass": self.expected_numeric_profile_class,
            "policyRngContractIdentity": self.expected_policy_rng_contract_identity,
            "trainingRecipeIdentity": self.expected_training_recipe_identity,
            "trainingRunIdentity": self.expected_training_run_identity,
            "weightContentDigest": self.expected_weight_content_digest,
        }
        for key, value in expected.items():
            if data.get(key) != value:
                raise LivePolicyCheckpointError(
                    f"checkpoint manifest field {key} differs from the fixed profile",
                    code="CHECKPOINT_PROFILE_MISMATCH",
                )
        implementation = data.get("modelImplementationIdentity")
        if not isinstance(implementation, Mapping) or implementation.get("implementation") != self.expected_model_implementation_identity or implementation.get("sourceCommit") != self.expected_source_commit:
            raise LivePolicyCheckpointError(
                "checkpoint implementation/source commit differs from the fixed profile",
                code="CHECKPOINT_IMPLEMENTATION_MISMATCH",
            )
        weight_identity = data.get("weightArtifactIdentity")
        if not isinstance(weight_identity, Mapping) or weight_identity.get("container") != SAFETENSORS_CONTAINER_IDENTITY or weight_identity.get("artifact") != "c1-06-feed-forward.weights":
            raise LivePolicyCheckpointError(
                "checkpoint weight container differs from the Safetensors profile",
                code="CHECKPOINT_WEIGHT_CONTAINER_MISMATCH",
            )

    def _require_weight_digest(
        self,
        raw_weights: bytes,
        manifest: ArgentumCheckpointManifestV1,
    ) -> None:
        if hashlib.sha256(raw_weights).hexdigest() != self.expected_weight_content_digest:
            raise LivePolicyCheckpointError(
                "checkpoint weight content digest differs from the fixed profile",
                code="CHECKPOINT_WEIGHT_DIGEST_MISMATCH",
            )
        try:
            manifest.validate_weight_bytes(raw_weights)
        except CheckpointManifestError as exc:
            raise LivePolicyCheckpointError(
                "checkpoint weights do not match the manifest digest",
                code="CHECKPOINT_WEIGHT_DIGEST_MISMATCH",
            ) from exc


C1_07B_POLICY_PROFILE = C1_07BPolicyProfile.reference()


def _resolve_regular_directory(value: Path | str) -> Path:
    try:
        path = Path(value)
    except (TypeError, ValueError) as exc:
        raise LivePolicyCheckpointError(
            "checkpoint directory must be path-like",
            code="CHECKPOINT_PATH_INVALID",
        ) from exc
    if path.is_symlink() or not path.is_dir():
        raise LivePolicyCheckpointError(
            "checkpoint directory must be a regular directory",
            code="CHECKPOINT_PATH_INVALID",
        )
    for ancestor in (path, *path.parents):
        if ancestor.is_symlink():
            raise LivePolicyCheckpointError(
                "checkpoint directory cannot contain a symlink ancestor",
                code="CHECKPOINT_PATH_UNSAFE",
            )
    try:
        return path.resolve(strict=True)
    except OSError as exc:
        raise LivePolicyCheckpointError(
            "checkpoint directory could not be resolved",
            code="CHECKPOINT_PATH_INVALID",
        ) from exc


def _require_regular_file(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise LivePolicyCheckpointError(
            f"{label} must be a regular file",
            code="CHECKPOINT_ARTIFACT_MISSING",
        )
