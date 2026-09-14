"""Frozen C1_05 supervised-target value objects."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..contracts.canonical_json import canonical_bytes, canonical_json, sha256_hex
from ..contracts.identities import (
    LABEL_ARTIFACT_SCHEMA_IDENTITY,
    SOURCE_DECISION_KEY_IDENTITY,
    SUPERVISED_POLICY_TARGET_IDENTITY,
)
from ..selection.selection_v2 import ExactSemanticSourceBinding
from .variable_batch import _deep_freeze as _deep_freeze_json


class LabelContractError(ValueError):
    """Raised when a C1_05 target is outside the frozen C0 shape."""


LABEL_MATERIALIZER_IMPLEMENTATION_IDENTITY = "argentum-ml-label-materializer@v1"
LABEL_MATERIALIZER_CONFIG_IDENTITY = "argentum-ml-label-materializer-config@v1"


@dataclass(frozen=True)
class LabelMaterializerConfigV1:
    """The exact V1 behavior/configuration preimage for label materialization."""

    version: int
    schema_identity: str
    allowed_partitions: tuple[str, ...]
    supported_decision_families: tuple[str, ...]
    unsupported_decision_policy: str
    structured_decision_policy: str
    target_source: str
    source_binding_ordinal_role: str
    source_join_key_identity: str
    teacher_result_schema_identity: str
    selection_contract_identity: str
    policy_rng_identity: str
    supervised_policy_target_contract_identity: str
    label_artifact_schema_identity: str
    test_partition_policy: str

    def __post_init__(self) -> None:
        if self.version != 1 or isinstance(self.version, bool):
            raise LabelContractError("only label materializer configuration version 1 is supported")
        if self.schema_identity != LABEL_MATERIALIZER_CONFIG_IDENTITY:
            raise LabelContractError("unsupported label materializer configuration identity")
        if self.allowed_partitions != ("TRAIN", "VALIDATION"):
            raise LabelContractError("unsupported label materializer partition set")
        if self.supported_decision_families != (
            "ACTION_CANDIDATES",
            "FOLDED_DECISION_OPTIONS",
        ):
            raise LabelContractError("unsupported label materializer decision-family set")
        if self.unsupported_decision_policy != "NO_LABEL":
            raise LabelContractError("unsupported decision policy must be NO_LABEL")
        if self.structured_decision_policy != "NO_LABEL":
            raise LabelContractError("structured decision policy must be NO_LABEL")
        if self.target_source != "SelectedTeacherResultV1.exact_source_binding":
            raise LabelContractError("unsupported label target source")
        if self.source_binding_ordinal_role != "binding_audit_only":
            raise LabelContractError("unsupported source-binding ordinal role")
        if self.source_join_key_identity != SOURCE_DECISION_KEY_IDENTITY:
            raise LabelContractError("unsupported source join key identity")
        if self.teacher_result_schema_identity != "argentum-ml-public-observation-teacher-result@v1":
            raise LabelContractError("unsupported Teacher result schema identity")
        if self.selection_contract_identity != "argentum-ml-policy-selection@v2":
            raise LabelContractError("unsupported Selection identity")
        if self.policy_rng_identity != "argentum-ml-policy-tie-rng@v1":
            raise LabelContractError("unsupported PolicyTieRng identity")
        if self.supervised_policy_target_contract_identity != SUPERVISED_POLICY_TARGET_IDENTITY:
            raise LabelContractError("unsupported supervised target identity")
        if self.label_artifact_schema_identity != LABEL_ARTIFACT_SCHEMA_IDENTITY:
            raise LabelContractError("unsupported label artifact schema identity")
        if self.test_partition_policy != "NO_TEACHER_CALL_NO_RESULT_NO_LABEL":
            raise LabelContractError("unsupported TEST partition policy")

    @classmethod
    def reference(cls) -> "LabelMaterializerConfigV1":
        return cls(
            version=1,
            schema_identity=LABEL_MATERIALIZER_CONFIG_IDENTITY,
            allowed_partitions=("TRAIN", "VALIDATION"),
            supported_decision_families=("ACTION_CANDIDATES", "FOLDED_DECISION_OPTIONS"),
            unsupported_decision_policy="NO_LABEL",
            structured_decision_policy="NO_LABEL",
            target_source="SelectedTeacherResultV1.exact_source_binding",
            source_binding_ordinal_role="binding_audit_only",
            source_join_key_identity=SOURCE_DECISION_KEY_IDENTITY,
            teacher_result_schema_identity="argentum-ml-public-observation-teacher-result@v1",
            selection_contract_identity="argentum-ml-policy-selection@v2",
            policy_rng_identity="argentum-ml-policy-tie-rng@v1",
            supervised_policy_target_contract_identity=SUPERVISED_POLICY_TARGET_IDENTITY,
            label_artifact_schema_identity=LABEL_ARTIFACT_SCHEMA_IDENTITY,
            test_partition_policy="NO_TEACHER_CALL_NO_RESULT_NO_LABEL",
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "schemaIdentity": self.schema_identity,
            "allowedPartitions": list(self.allowed_partitions),
            "supportedDecisionFamilies": list(self.supported_decision_families),
            "unsupportedDecisionPolicy": self.unsupported_decision_policy,
            "structuredDecisionPolicy": self.structured_decision_policy,
            "targetSource": self.target_source,
            "sourceBindingOrdinalRole": self.source_binding_ordinal_role,
            "sourceJoinKeyIdentity": self.source_join_key_identity,
            "teacherResultSchemaIdentity": self.teacher_result_schema_identity,
            "selectionContractIdentity": self.selection_contract_identity,
            "policyRngIdentity": self.policy_rng_identity,
            "supervisedPolicyTargetContractIdentity": self.supervised_policy_target_contract_identity,
            "labelArtifactSchemaIdentity": self.label_artifact_schema_identity,
            "testPartitionPolicy": self.test_partition_policy,
        }

    @property
    def digest(self) -> str:
        return sha256_hex(canonical_bytes(self.to_dict()))


@dataclass(frozen=True)
class SupervisedPolicyTargetV1:
    """The exact Teacher source binding, kept out of model input."""

    chosen_semantic_action: dict[str, Any] | None
    chosen_semantic_response: dict[str, Any] | None
    _frozen: bool = field(default=True, init=False, repr=False)

    def __post_init__(self) -> None:
        if (self.chosen_semantic_action is None) == (self.chosen_semantic_response is None):
            raise LabelContractError("target requires exactly one action or response")
        selected = (
            self.chosen_semantic_action
            if self.chosen_semantic_action is not None
            else self.chosen_semantic_response
        )
        if not isinstance(selected, dict):
            raise LabelContractError("target value must be an object")
        frozen = _deep_freeze_json(selected)
        canonical_json(frozen)
        if self.chosen_semantic_action is not None:
            object.__setattr__(self, "chosen_semantic_action", frozen)
        else:
            object.__setattr__(self, "chosen_semantic_response", frozen)

    @classmethod
    def from_exact_source_binding(
        cls,
        binding: ExactSemanticSourceBinding,
    ) -> "SupervisedPolicyTargetV1":
        if not isinstance(binding, ExactSemanticSourceBinding):
            raise LabelContractError("target requires ExactSemanticSourceBinding")
        return cls(binding.exact_action, binding.exact_response)

    def to_dict(self) -> dict[str, Any]:
        return {
            "chosenSemanticAction": self.chosen_semantic_action,
            "chosenSemanticResponse": self.chosen_semantic_response,
        }
