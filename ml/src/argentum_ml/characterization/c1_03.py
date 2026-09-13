"""Frozen C1_03 PublicObservationTeacher quality and admission contracts."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from ..contracts.canonical_json import canonical_bytes, sha256_hex
from ..contracts.identities import (
    DERIVED_VIEW_SCHEMA_IDENTITY,
    MODEL_FACING_CONTRACT_IDENTITY,
    POLICY_TIE_RNG_IDENTITY,
    SELECTION_V2_IDENTITY,
    SPLIT_CONTRACT_IDENTITY,
)

C1_03_CHARACTERIZATION_PLAN_IDENTITY = (
    "argentum-ml-c1-03-teacher-quality-and-admission@v1"
)
TEACHER_ADMISSION_PURPOSE_IDENTITY = "argentum-ml-flat-reference-bootstrap@v1"
TEACHER_ADMISSION_SCOPE = "FLAT_REFERENCE_BOOTSTRAP"

SOURCE_DATASET_ID = "69cfd13f7537da2a55e00ef9bdc69d09af9a7c11a7490c3b20c985b231e55d03"
SOURCE_MANIFEST_CONTENT_DIGEST = (
    "de1f3a10fc6476b4db2ec3d76dbfc347f4c268005df7352ac47387442b4211d2"
)
SOURCE_EPISODES = 64
SOURCE_DECISIONS = 125471

BASE_SHA = "b9eb8da182390095d73b9c06d9bbe20049156e9b"
ACCEPTED_C1_02_PR_HEAD = "8cad4845dc59192dde86849c8ba4ceacc1bb6331"
TEACHER_SOURCE_COMMIT = ACCEPTED_C1_02_PR_HEAD
TEACHER_POLICY_IDENTITY = "argentum-ml-public-observation-bootstrap-teacher@v1"
TEACHER_CONTRACT_IDENTITY = "argentum-ml-teacher-bootstrap@v1"
TEACHER_CONFIG_DIGEST = (
    "fa358597c09ce466e1be1823de485e0accd84be622184fa1d6505806aff0d7d8"
)

MATERIALIZER_IMPLEMENTATION_IDENTITY = "c1-materializer@v1"
MATERIALIZER_SOURCE_COMMIT = BASE_SHA
MATERIALIZER_CONFIG_DIGEST = (
    "7d5fbfd0bfe71844fefbd25d3fcce7beac3de8de281d9a2f02cf225aef27364c"
)

TEACHER_POLICY_TIE_SCHEDULE_IDENTITY = (
    "argentum-ml-c1-03-teacher-policy-tie-schedule@v1"
)
SOURCE_POLICY_RNG_IDENTITY = "explicit-seed/kotlin-policy-state-v1"
C1_03_TEACHER_POLICY_TIE_SEED = 0
C1_03_INITIAL_POLICY_TIE_CURSOR = 0
LEGACY_A9_POLICY_SEED_REUSED = False

ALLOWED_OFFLINE_PARTITIONS = ("TRAIN", "VALIDATION")
TEST_ROWS_SUBMITTED_TO_TEACHER = 0
FIRST_DIVERGENCE_LIMIT = 32
FIRST_DIVERGENCE_PER_EPISODE = 1

GAMEPLAY_EVALUATION_CONTRACT_ID = "argentum-ml-gameplay-evaluation@v1"
POLICY_A_IDENTITY = "C1_03_EVAL_COMPOSITE_V1"
POLICY_B_IDENTITY = "b2-a9-deterministic-external-policy@v1"
FIXED_OPPONENT_IDENTITY = POLICY_B_IDENTITY
STRUCTURED_COMPLETION_POLICY_IDENTITY = "argentum-ml-c1-03-a9-structured-completion@v1"
PAIRING_KEYS = 16
PAIRING_KEYS_PER_CELL = 4
POLICY_A_EXECUTIONS = 16
POLICY_B_EXECUTIONS = 16
TOTAL_GAME_EXECUTIONS = 32


_PLAN_FIELDS = frozenset(
    {
        "planIdentity",
        "admissionPurposeIdentity",
        "admissionScope",
        "sourceDatasetId",
        "sourceManifestContentDigest",
        "splitContractIdentity",
        "teacherPolicyIdentity",
        "teacherContractIdentity",
        "teacherSourceCommit",
        "teacherConfigDigest",
        "selectionContractIdentity",
        "policyRngIdentity",
        "derivedViewSchemaIdentity",
        "materializerImplementationIdentity",
        "materializerSourceCommit",
        "materializerConfigDigest",
        "teacherPolicyTieScheduleIdentity",
        "sourcePolicyRngIdentity",
        "teacherPolicyTieSeed",
        "initialPolicyTieCursor",
        "legacyA9PolicySeedReused",
        "allowedOfflinePartitions",
        "testRowsSubmittedToTeacher",
        "firstDivergenceLimit",
        "firstDivergencePerEpisode",
        "gameplayEvaluationContractId",
        "policyAIdentity",
        "policyBIdentity",
        "fixedOpponentIdentity",
        "structuredCompletionPolicyIdentity",
        "pairingKeys",
        "pairingKeysPerCell",
        "policyAExecutions",
        "policyBExecutions",
        "totalGameExecutions",
    }
)


@dataclass(frozen=True)
class C1_03PlanV1:
    plan_identity: str
    admission_purpose_identity: str
    admission_scope: str
    source_dataset_id: str
    source_manifest_content_digest: str
    split_contract_identity: str
    teacher_policy_identity: str
    teacher_contract_identity: str
    teacher_source_commit: str
    teacher_config_digest: str
    selection_contract_identity: str
    policy_rng_identity: str
    derived_view_schema_identity: str
    materializer_implementation_identity: str
    materializer_source_commit: str
    materializer_config_digest: str
    teacher_policy_tie_schedule_identity: str
    source_policy_rng_identity: str
    teacher_policy_tie_seed: int
    initial_policy_tie_cursor: int
    legacy_a9_policy_seed_reused: bool
    allowed_partitions: tuple[str, ...]
    test_rows_submitted_to_teacher: int
    first_divergence_limit: int
    first_divergence_per_episode: int
    gameplay_evaluation_contract_id: str
    policy_a_identity: str
    policy_b_identity: str
    fixed_opponent_identity: str
    structured_completion_policy_identity: str
    pairing_keys: int
    pairing_keys_per_cell: int
    policy_a_executions: int
    policy_b_executions: int
    total_game_executions: int

    @classmethod
    def reference(cls) -> "C1_03PlanV1":
        return cls(
            plan_identity=C1_03_CHARACTERIZATION_PLAN_IDENTITY,
            admission_purpose_identity=TEACHER_ADMISSION_PURPOSE_IDENTITY,
            admission_scope=TEACHER_ADMISSION_SCOPE,
            source_dataset_id=SOURCE_DATASET_ID,
            source_manifest_content_digest=SOURCE_MANIFEST_CONTENT_DIGEST,
            split_contract_identity=SPLIT_CONTRACT_IDENTITY,
            teacher_policy_identity=TEACHER_POLICY_IDENTITY,
            teacher_contract_identity=TEACHER_CONTRACT_IDENTITY,
            teacher_source_commit=TEACHER_SOURCE_COMMIT,
            teacher_config_digest=TEACHER_CONFIG_DIGEST,
            selection_contract_identity=SELECTION_V2_IDENTITY,
            policy_rng_identity=POLICY_TIE_RNG_IDENTITY,
            derived_view_schema_identity=DERIVED_VIEW_SCHEMA_IDENTITY,
            materializer_implementation_identity=MATERIALIZER_IMPLEMENTATION_IDENTITY,
            materializer_source_commit=MATERIALIZER_SOURCE_COMMIT,
            materializer_config_digest=MATERIALIZER_CONFIG_DIGEST,
            teacher_policy_tie_schedule_identity=TEACHER_POLICY_TIE_SCHEDULE_IDENTITY,
            source_policy_rng_identity=SOURCE_POLICY_RNG_IDENTITY,
            teacher_policy_tie_seed=C1_03_TEACHER_POLICY_TIE_SEED,
            initial_policy_tie_cursor=C1_03_INITIAL_POLICY_TIE_CURSOR,
            legacy_a9_policy_seed_reused=LEGACY_A9_POLICY_SEED_REUSED,
            allowed_partitions=ALLOWED_OFFLINE_PARTITIONS,
            test_rows_submitted_to_teacher=TEST_ROWS_SUBMITTED_TO_TEACHER,
            first_divergence_limit=FIRST_DIVERGENCE_LIMIT,
            first_divergence_per_episode=FIRST_DIVERGENCE_PER_EPISODE,
            gameplay_evaluation_contract_id=GAMEPLAY_EVALUATION_CONTRACT_ID,
            policy_a_identity=POLICY_A_IDENTITY,
            policy_b_identity=POLICY_B_IDENTITY,
            fixed_opponent_identity=FIXED_OPPONENT_IDENTITY,
            structured_completion_policy_identity=STRUCTURED_COMPLETION_POLICY_IDENTITY,
            pairing_keys=PAIRING_KEYS,
            pairing_keys_per_cell=PAIRING_KEYS_PER_CELL,
            policy_a_executions=POLICY_A_EXECUTIONS,
            policy_b_executions=POLICY_B_EXECUTIONS,
            total_game_executions=TOTAL_GAME_EXECUTIONS,
        )

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "C1_03PlanV1":
        if not isinstance(value, Mapping) or set(value) != _PLAN_FIELDS:
            raise ValueError("C1_03 plan fields are not exact")
        parsed = dict(value)
        partitions = parsed["allowedOfflinePartitions"]
        if not isinstance(partitions, list) or any(
            not isinstance(partition, str) for partition in partitions
        ):
            raise ValueError("allowedOfflinePartitions must be a string list")
        parsed["allowedOfflinePartitions"] = tuple(partitions)
        parsed["planIdentity"] = str(parsed["planIdentity"])
        parsed["admissionPurposeIdentity"] = str(parsed["admissionPurposeIdentity"])
        parsed["admissionScope"] = str(parsed["admissionScope"])
        parsed["sourceDatasetId"] = str(parsed["sourceDatasetId"])
        parsed["sourceManifestContentDigest"] = str(parsed["sourceManifestContentDigest"])
        parsed["splitContractIdentity"] = str(parsed["splitContractIdentity"])
        parsed["teacherPolicyIdentity"] = str(parsed["teacherPolicyIdentity"])
        parsed["teacherContractIdentity"] = str(parsed["teacherContractIdentity"])
        parsed["teacherSourceCommit"] = str(parsed["teacherSourceCommit"])
        parsed["teacherConfigDigest"] = str(parsed["teacherConfigDigest"])
        parsed["selectionContractIdentity"] = str(parsed["selectionContractIdentity"])
        parsed["policyRngIdentity"] = str(parsed["policyRngIdentity"])
        parsed["derivedViewSchemaIdentity"] = str(parsed["derivedViewSchemaIdentity"])
        parsed["materializerImplementationIdentity"] = str(
            parsed["materializerImplementationIdentity"]
        )
        parsed["materializerSourceCommit"] = str(parsed["materializerSourceCommit"])
        parsed["materializerConfigDigest"] = str(parsed["materializerConfigDigest"])
        parsed["teacherPolicyTieScheduleIdentity"] = str(
            parsed["teacherPolicyTieScheduleIdentity"]
        )
        parsed["sourcePolicyRngIdentity"] = str(parsed["sourcePolicyRngIdentity"])
        parsed["gameplayEvaluationContractId"] = str(parsed["gameplayEvaluationContractId"])
        parsed["policyAIdentity"] = str(parsed["policyAIdentity"])
        parsed["policyBIdentity"] = str(parsed["policyBIdentity"])
        parsed["fixedOpponentIdentity"] = str(parsed["fixedOpponentIdentity"])
        parsed["structuredCompletionPolicyIdentity"] = str(
            parsed["structuredCompletionPolicyIdentity"]
        )
        return cls(
            plan_identity=parsed["planIdentity"],
            admission_purpose_identity=parsed["admissionPurposeIdentity"],
            admission_scope=parsed["admissionScope"],
            source_dataset_id=parsed["sourceDatasetId"],
            source_manifest_content_digest=parsed["sourceManifestContentDigest"],
            split_contract_identity=parsed["splitContractIdentity"],
            teacher_policy_identity=parsed["teacherPolicyIdentity"],
            teacher_contract_identity=parsed["teacherContractIdentity"],
            teacher_source_commit=parsed["teacherSourceCommit"],
            teacher_config_digest=parsed["teacherConfigDigest"],
            selection_contract_identity=parsed["selectionContractIdentity"],
            policy_rng_identity=parsed["policyRngIdentity"],
            derived_view_schema_identity=parsed["derivedViewSchemaIdentity"],
            materializer_implementation_identity=parsed["materializerImplementationIdentity"],
            materializer_source_commit=parsed["materializerSourceCommit"],
            materializer_config_digest=parsed["materializerConfigDigest"],
            teacher_policy_tie_schedule_identity=parsed[
                "teacherPolicyTieScheduleIdentity"
            ],
            source_policy_rng_identity=parsed["sourcePolicyRngIdentity"],
            teacher_policy_tie_seed=parsed["teacherPolicyTieSeed"],
            initial_policy_tie_cursor=parsed["initialPolicyTieCursor"],
            legacy_a9_policy_seed_reused=parsed["legacyA9PolicySeedReused"],
            allowed_partitions=parsed["allowedOfflinePartitions"],
            test_rows_submitted_to_teacher=parsed["testRowsSubmittedToTeacher"],
            first_divergence_limit=parsed["firstDivergenceLimit"],
            first_divergence_per_episode=parsed["firstDivergencePerEpisode"],
            gameplay_evaluation_contract_id=parsed["gameplayEvaluationContractId"],
            policy_a_identity=parsed["policyAIdentity"],
            policy_b_identity=parsed["policyBIdentity"],
            fixed_opponent_identity=parsed["fixedOpponentIdentity"],
            structured_completion_policy_identity=parsed[
                "structuredCompletionPolicyIdentity"
            ],
            pairing_keys=parsed["pairingKeys"],
            pairing_keys_per_cell=parsed["pairingKeysPerCell"],
            policy_a_executions=parsed["policyAExecutions"],
            policy_b_executions=parsed["policyBExecutions"],
            total_game_executions=parsed["totalGameExecutions"],
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "planIdentity": self.plan_identity,
            "admissionPurposeIdentity": self.admission_purpose_identity,
            "admissionScope": self.admission_scope,
            "sourceDatasetId": self.source_dataset_id,
            "sourceManifestContentDigest": self.source_manifest_content_digest,
            "splitContractIdentity": self.split_contract_identity,
            "teacherPolicyIdentity": self.teacher_policy_identity,
            "teacherContractIdentity": self.teacher_contract_identity,
            "teacherSourceCommit": self.teacher_source_commit,
            "teacherConfigDigest": self.teacher_config_digest,
            "selectionContractIdentity": self.selection_contract_identity,
            "policyRngIdentity": self.policy_rng_identity,
            "derivedViewSchemaIdentity": self.derived_view_schema_identity,
            "materializerImplementationIdentity": self.materializer_implementation_identity,
            "materializerSourceCommit": self.materializer_source_commit,
            "materializerConfigDigest": self.materializer_config_digest,
            "teacherPolicyTieScheduleIdentity": self.teacher_policy_tie_schedule_identity,
            "sourcePolicyRngIdentity": self.source_policy_rng_identity,
            "teacherPolicyTieSeed": self.teacher_policy_tie_seed,
            "initialPolicyTieCursor": self.initial_policy_tie_cursor,
            "legacyA9PolicySeedReused": self.legacy_a9_policy_seed_reused,
            "allowedOfflinePartitions": list(self.allowed_partitions),
            "testRowsSubmittedToTeacher": self.test_rows_submitted_to_teacher,
            "firstDivergenceLimit": self.first_divergence_limit,
            "firstDivergencePerEpisode": self.first_divergence_per_episode,
            "gameplayEvaluationContractId": self.gameplay_evaluation_contract_id,
            "policyAIdentity": self.policy_a_identity,
            "policyBIdentity": self.policy_b_identity,
            "fixedOpponentIdentity": self.fixed_opponent_identity,
            "structuredCompletionPolicyIdentity": self.structured_completion_policy_identity,
            "pairingKeys": self.pairing_keys,
            "pairingKeysPerCell": self.pairing_keys_per_cell,
            "policyAExecutions": self.policy_a_executions,
            "policyBExecutions": self.policy_b_executions,
            "totalGameExecutions": self.total_game_executions,
        }

    @property
    def digest(self) -> str:
        return sha256_hex(canonical_bytes(self.to_dict()))
